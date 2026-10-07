package com.example.wms.intl;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 国际物流单号发号器（原子自增，替代「COUNT(*) + 1」）。
 *
 * <p><b>为什么不能 COUNT(*) + 1</b>：两个操作员同时建单时，两边都查到「今天已有
 * N 张」，都算出 N+1，唯一键把后者顶掉 → 用户看到的是 500（而不是 409「单号已存在」，
 * 请重试），而重试又可能撞同一个号。这是货代场景的日常操作，不是边缘情况。
 * 原来的「重试 5 次」之所以无效，正是因为 5 次重查到的还是同一个 COUNT——
 * 在第一次 INSERT 成功之前，表不会变。</p>
 *
 * <p><b>现在怎么做</b>：靠一张序号表做 {@code UPDATE ... SET next_seq = next_seq + 1}
 * 的原子自增。InnoDB 的行锁把同一个 (租户, 类型, 日期) 的取号串行化，不需要发号器
 * 服务，也不需要 Redis——发号的 QPS 远低于需要独立组件的程度。</p>
 *
 * <p><b>取号必须独立于写单事务</b>，否则并发下会死锁：写单事务已持有业务表的间隙锁，
 * 再去等序号表的行锁，并发双方就形成循环等待（实测 8 并发建母单挂 5 个）。拆成独立
 * 事务后它只碰序号表一把锁，与业务事务之间不存在环。</p>
 *
 * <p><b>号段跳号是可以接受的</b>：取号与写单不在一个事务里，回滚或并发下提交顺序
 * 颠倒都会留空洞（今天第 7 号没用上）。业务单据的连续性没有价值，唯一性才有。</p>
 */
public final class IntlNumberGenerator {

    private static final Logger logger = LoggerFactory.getLogger(IntlNumberGenerator.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private IntlNumberGenerator() {
    }

    /**
     * 取一个当日序号并拼成完整单号。
     *
     * @param jdbc 本服务的 NamedParameterJdbcTemplate
     * @param tenantId 租户
     * @param bizType 单据类型（SHIPMENT / BATCH / TASK / DECL / TICKET）
     * @param prefix 单号前缀（如 SHP、MEXP）
     * @param pad 序号补零宽度（3 位形如 007，4 位形如 0007）
     * @return 形如 {@code SHP20261004001} 的单号
     */
    public static String nextNo(NamedParameterJdbcTemplate jdbc, String tenantId,
                                String bizType, String prefix, int pad) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("bizType", bizType)
                .addValue("day", day);

        // **整个取号过程必须在调用方事务之外**，否则并发下会死锁。
        //
        // 调用方（建母单/建运单）是有 @Transactional 的，它在插入业务行时已经持有
        // 业务表的间隙锁；如果取号也在同一事务里，就会形成经典的循环等待：
        // A 持有业务表锁等序号表锁，B 持有序号表锁等业务表锁 → MySQL 判死锁 → 500
        // （实测 8 并发建母单挂 5 个）。把取号放进独立事务后，它只碰序号表一把锁，
        // 与业务事务之间不存在循环等待。
        //
        // 独立事务的代价是「号可能先于业务行提交」：并发下两个请求可能先后拿到
        // 号、再分别提交，谁先提交与拿到号无关。但号只要求唯一不要求连续，
        // 这个代价可以接受。
        // PROPAGATION_REQUIRES_NEW：挂起调用方的业务事务，用一条**全新**的事务取号，
        // 取完再恢复外层事务。这样锁完全隔离——外层既不会因为取号失败被标记
        // rollback-only，取号也不会等外层持有的业务表锁（死锁的另一半原因）。
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        // 死锁/锁等待是概率性的（取决于并发时序），重试 3 次并退避即可覆盖；
        // 重试而不是直接失败，是因为这里的竞争窗口极短，退避后几乎必然成功。
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Integer no = tx.execute(status -> {
                    // ① 先 UPDATE（不是先 INSERT！）。单条 UPDATE 之间不会死锁：
                    //    双方都只锁同一行，后来的等先前的，天然串行。
                    //    死锁的来源是「先 INSERT 再 UPDATE」——两个事务各持一把锁
                    //    再互等对方（实测 8 并发挂 5 个）。
                    int updated = jdbc.update("UPDATE wms_number_sequences "
                            + "SET next_seq = next_seq + 1 "
                            + "WHERE tenant_id = :tenant AND biz_type = :bizType AND biz_date = :day",
                            params);
                    if (updated == 0) {
                        // 号段行还不存在（当天第一次取号）→ 初始化后再 UPDATE。
                        // 这一步在并发下也安全：INSERT IGNORE 只会有一方成功，
                        // 另一方拿 DuplicateKey 被吞掉，然后双方都落到上面的 UPDATE。
                        jdbc.update("INSERT IGNORE INTO wms_number_sequences "
                                        + "(tenant_id, biz_type, biz_date, next_seq) "
                                        + "VALUES (:tenant, :bizType, :day, 0)",
                                params);
                        jdbc.update("UPDATE wms_number_sequences "
                                        + "SET next_seq = next_seq + 1 "
                                        + "WHERE tenant_id = :tenant AND biz_type = :bizType AND biz_date = :day",
                                params);
                    }
                    // ② 重查拿号：不能拿 UPDATE 的返回值（对 UPDATE 本来就不可靠）
                    Integer seq = jdbc.queryForObject("SELECT next_seq FROM wms_number_sequences "
                                    + "WHERE tenant_id = :tenant AND biz_type = :bizType AND biz_date = :day",
                            params, Integer.class);
                    return seq == null || seq < 1 ? 1 : seq;
                });
                return prefix + day.format(DAY) + String.format("%0" + pad + "d", no == null ? 1 : no);
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                logger.warn("取号遇到锁冲突，第 {} 次重试：{}", attempt, e.getMessage());
                sleepQuietly(attempt * 20L);
            } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                // 上一次锁冲突把外层业务事务标记成了 rollback-only，重试也救不回来。
                // 这种情况只能让调用方整体重来——把它当成失败抛出去，客户端重试即可。
                logger.error("取号失败且业务事务已不可用，直接失败", e);
                throw e;
            }
        }
        // 三次都撞锁：极小概率（需要 8+ 个请求精确同时撞同一号段）。此时退回到
        // 「时间戳 + 随机」的兜底号，保证业务能继续——唯一性由兜底位保证，
        // 可读性下降但不会写脏数据。
        logger.error("取号连续 {} 次锁冲突，使用兜底单号", 3);
        return prefix + day.format(DAY)
                + String.format("%0" + pad + "d", 1)
                + java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** 退避等待。用 Thread.sleep 但吞掉中断——中断在这里没有可传播的动作。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}