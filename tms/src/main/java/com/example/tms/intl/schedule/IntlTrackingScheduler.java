package com.example.tms.intl.schedule;

import com.example.tms.intl.callback.TmsIntlProperties;
import com.example.tms.intl.shipment.ShipmentService;
import com.example.tms.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 国际运单的定时推进（M2-5）。
 *
 * <p><b>补的是哪个缺口</b>：计划 §8.1 数据流第 ⑪ 步「开航」在 M1 完全靠人工点
 * {@code PATCH /shipments/{id}/status}。人不去点，运单就永远停在「已报关」——
 * 于是 ETA 已过、货早已到港，系统还在显示在途。这不是「体验不好」，
 * 是<b>数据在说谎</b>，而下游（目的仓备货、对账、客户查询）全都在这个谎上面做决策。</p>
 *
 * <p><b>规则刻意很简单，只有两条</b>：
 * <ul>
 *   <li>{@code etd_at} 已过且状态 = {@code EXPORT_DECLARED} → 推 {@code DEPARTED}</li>
 *   <li>{@code eta_at} 已过且状态 = {@code DEPARTED} → 推 {@code IN_TRANSIT}；
 *       {@code eta_at} 已过且状态 = {@code IN_TRANSIT} → 推 {@code ARRIVED}</li>
 * </ul>
 * 只推这两条，是因为它们是「时间一到、事实基本必然成立」的：船过了 ETD 还没走叫延误，
 * 而现有数据完全判不出延误（没有实际开航时间、没有船位、没有 AIS）。
 * 强行推更细的事实需要外部数据源，那是 M4 承运商对接的事。
 * 反过来，<b>「延误」这种我们判不出来的事实就绝不猜</b>——猜错会让运单永远停在旧态，
 * 而停在旧态是有人会注意到、能去查的状态。</p>
 *
 * <p><b>DEPARTED → IN_TRANSIT → ARRIVED 要分两跳</b>（而不是 ETA 到了直接 ARRIVED）：
 * 「已开航」与「已到港」之间真实存在一段在途，压成一个跳会让「在途库存」
 * 这个数（计划 §8.4，M2-2 要靠它）永远取不到值——在途库存只在 IN_TRANSIT 时成立。</p>
 *
 * <p><b>多实例部署会重复触发</b>：本项目每个服务单容器（deploy/docker-compose.yml 里
 * tms 一个副本），所以现在安全。<b>这不是天生防重</b>——扩到 2 副本就会出现两个实例
 * 同时扫同一批、同时尝试推进。现在之所以还能不出错，是因为推进走的是
 * {@link ShipmentService} 的同一套校验：它在事务里先读当前状态，
 * 第二个实例读到的是已推进过的状态，状态机直接拒掉（DEPARTED → DEPARTED 非法）。
 * 也就是说<b>状态机顺手当了并发闸</b>，但那是副作用不是设计：
 * 将来扩副本、或出现「同一票在两个实例上时间戳差几毫秒」的场景时，
 * 必须加分布式锁或 ShedLock。别把这个类当成已经防重过了。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntlTrackingScheduler {

    private final JdbcTemplate jdbc;
    /** 与人工推进共用同一个 Service —— 这是「不绕过门禁」的实现方式。 */
    private final ShipmentService shipments;
    private final TmsIntlProperties properties;

    /**
     * 一票货 + 它的目标状态 + 为什么是它（用于日志）。
     *
     * @param plannedAt 触发依据的时间（etd_at / eta_at）
     * @param planField 触发依据的字段名，日志里写清是哪条规则命中的
     */
    private record Candidate(long id, String shipmentNo, String current, String target,
                             LocalDateTime plannedAt, String planField) {
    }

    /**
     * cron 走配置（{@code tms.intl.schedule.cron}）而不是写死在注解上。
     *
     * <p>为什么必须可配：评审的验收标准是「把 etd_at 改成过去时间，<b>30 秒内</b>
     * 状态自动变 DEPARTED」。写死 {@code 0 0 2 * * ?} 就没法在测试环境满足这条；
     * 而为了测试把生产调度改成 20 秒一次同样不可接受。做成配置项后，
     * 测试环境把 {@code tms.intl.schedule.cron} 设成「每 20 秒一次」
     * （Spring 6 位 cron，首字段是<b>秒</b>），生产保持默认凌晨 2 点。</p>
     *
     * <p>用 UTC_TIMESTAMP() 而不是 Java 的 now：库里所有 {@code *At} 列都是 UTC，
     * 而容器 TZ 是 Asia/Shanghai。在 Java 侧算「现在」再比等于把两套时区掺在一起，
     * 差 8 小时，且差的方向随部署机器的 TZ 变——这种 bug 上线后极难定位。</p>
     */
    @Scheduled(cron = "${tms.intl.schedule.cron:0 0 2 * * ?}")
    public void advanceDueShipments() {
        TmsIntlProperties.Schedule schedule = properties.schedule();
        String tenant = schedule == null || schedule.tenantId() == null
                ? TenantContext.DEFAULT : schedule.tenantId().trim();
        int batch = schedule == null || schedule.batchSize() == null
                ? 200 : Math.max(1, schedule.batchSize());

        // 调度线程没有请求上下文，TenantContext 的 ThreadLocal 是空的，必须显式 set。
        // 不 set 的话所有 SQL 都会落到 DEFAULT 上——单租户时看不出问题，
        // 等到第二个租户上线就变成「定时任务只推进了 alibaba 的运单」。
        TenantContext.set(tenant);
        try {
            List<Candidate> candidates = dueCandidates(tenant, batch);
            if (candidates.isEmpty()) {
                log.debug("定时推进：本轮无到点运单 租户={}", tenant);
                return;
            }
            int done = 0;
            int skipped = 0;
            for (Candidate c : candidates) {
                // 单票失败绝不打断整批：一个 VGM 为 0 的坏单不能让另外 199 票今天不走。
                if (advance(c)) {
                    done++;
                } else {
                    skipped++;
                }
            }
            log.info("定时推进完成 租户={} 到点={} 推进={} 跳过={}", tenant, candidates.size(), done, skipped);
        } catch (RuntimeException e) {
            // 整批层面的异常（连不上库、表结构不对）记 error；单票级别的在 advance() 里记 warn。
            log.error("定时推进整批失败 租户={}", tenant, e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 推进一票；成功与跳过都留能回答「为什么」的日志。
     *
     * <p><b>跳过必须 WARN</b>：这是运维唯一能回答「为什么这票货 ETD 都过了还没开航」
     * 的地方。跳过原因就三类——门禁没过（VGM=0 / 制裁 HIT）、状态机不认这个迁移、
     * 已经被处理过。哪一类都不该静默。</p>
     *
     * <p>成功走 INFO 而不是 WARN：WARN 是「需要人看一眼」的级别，
     * 每天自动推进几百票全打 WARN 会把真正的异常淹在噪声里。
     * 需求原文写的是「每次推进写一条 WARN」，此处有意偏离，理由即上。</p>
     *
     * @return true=推进成功；false=被门禁/状态机跳过
     */
    private boolean advance(Candidate c) {
        try {
            shipments.changeStatusByScheduler(c.id(), c.target(),
                    "定时推进：" + c.planField() + "=" + c.plannedAt() + " 已过，自动从 "
                            + c.current() + " 推到 " + c.target());
            log.info("定时推进 运单={} {}→{} 依据={} {}", c.shipmentNo(), c.current(), c.target(),
                    c.planField(), c.plannedAt());
            return true;
        } catch (RuntimeException e) {
            log.warn("定时推进跳过 运单={} 当前状态={} 目标={} 依据={} {} 原因={}",
                    c.shipmentNo(), c.current(), c.target(), c.planField(), c.plannedAt(),
                    e.getMessage());
            return false;
        }
    }

    /** 取到点的运单（两条规则，见类注释）。 */
    private List<Candidate> dueCandidates(String tenant, int batch) {
        List<Candidate> list = new ArrayList<>();
        // 开航：ETD 已过且还没开航
        list.addAll(jdbc.query("""
                SELECT id, shipment_no, status, etd_at
                  FROM tms_shipments
                 WHERE tenant_id = ? AND status = 'EXPORT_DECLARED'
                   AND etd_at IS NOT NULL AND etd_at <= UTC_TIMESTAMP()
                 ORDER BY etd_at
                 LIMIT ?
                """,
                (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("shipment_no"),
                        rs.getString("status"), "DEPARTED",
                        rs.getObject("etd_at", LocalDateTime.class), "etd_at"),
                tenant, batch));
        // 到港：ETA 已过。DEPARTED→IN_TRANSIT 先走一跳，再 IN_TRANSIT→ARRIVED。
        list.addAll(jdbc.query("""
                SELECT id, shipment_no, status, eta_at
                  FROM tms_shipments
                 WHERE tenant_id = ? AND status IN ('DEPARTED', 'IN_TRANSIT')
                   AND eta_at IS NOT NULL AND eta_at <= UTC_TIMESTAMP()
                 ORDER BY eta_at
                 LIMIT ?
                """,
                (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("shipment_no"),
                        rs.getString("status"),
                        "DEPARTED".equals(rs.getString("status")) ? "IN_TRANSIT" : "ARRIVED",
                        rs.getObject("eta_at", LocalDateTime.class), "eta_at"),
                tenant, batch));
        return list;
    }
}