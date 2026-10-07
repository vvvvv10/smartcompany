package com.example.crm.dashboard;

import com.example.crm.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * CRM 统计看板数据。
 *
 * <p>所有数字都来自 crm 库实时聚合，数据量小时直接 COUNT 即可，
 * 不引入额外的缓存/异步统计组件。</p>
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;

    /** 看板总览：客户、商机、跟进三块的核心指标。 */
    public Dashboard summary() {
        String tenant = TenantContext.get();
        Long customerTotal = queryLong("SELECT COUNT(*) FROM customers WHERE tenant_id = ?", tenant);
        Long customerMonthNew = queryLong("""
                SELECT COUNT(*) FROM customers
                 WHERE created_at >= DATE_FORMAT(NOW(), '%Y-%m-01') AND tenant_id = ?
                """, tenant);
        Long dealCount = queryLong("SELECT COUNT(*) FROM customers WHERE status = 'DEAL' AND tenant_id = ?", tenant);
        Long followingCount = queryLong(
                "SELECT COUNT(*) FROM customers WHERE status = 'FOLLOWING' AND tenant_id = ?", tenant);

        BigDecimal amountTotal = queryDecimal(
                "SELECT COALESCE(SUM(amount), 0) FROM opportunities WHERE tenant_id = ?", tenant);
        BigDecimal amountWin = queryDecimal(
                "SELECT COALESCE(SUM(amount), 0) FROM opportunities WHERE stage = 'WON' AND tenant_id = ?", tenant);
        Long oppActive = queryLong(
                "SELECT COUNT(*) FROM opportunities WHERE stage NOT IN ('WON', 'LOST') AND tenant_id = ?", tenant);

        Long followUpTotal = queryLong("SELECT COUNT(*) FROM follow_ups WHERE tenant_id = ?", tenant);
        Long followUpPending = queryLong("""
                SELECT COUNT(*) FROM follow_ups
                 WHERE next_follow_at IS NOT NULL AND next_follow_at < NOW() AND tenant_id = ?
                """, tenant);

        return new Dashboard(
                new Block(customerTotal, customerMonthNew, dealCount, followingCount),
                new OppBlock(oppActive, amountTotal, amountWin, byStage()),
                new FollowBlock(followUpTotal, followUpPending));
    }

    /** 商机阶段分布：前端画漏斗/柱状图用。 */
    public List<StageCount> byStage() {
        return jdbc.query("""
                SELECT stage, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS amount
                  FROM opportunities
                 WHERE tenant_id = ?
                 GROUP BY stage
                 ORDER BY FIELD(stage, 'LEAD', 'PROPOSAL', 'NEGOTIATION', 'WON', 'LOST')
                """,
                (rs, i) -> new StageCount(
                        rs.getString("stage"),
                        rs.getLong("cnt"),
                        rs.getBigDecimal("amount")),
                TenantContext.get());
    }

    /** 客户来源分布。 */
    public List<NameCount> bySource() {
        // 不能写 GROUP BY name：customers 表本身有 name 列，MySQL 会优先解析成
        // 真实列而不是 SELECT 的别名，导致 source 成为未聚合列，撞上 only_full_group_by。
        // 这里按完整表达式分组，语义才是"按来源分组"。
        return jdbc.query("""
                SELECT COALESCE(NULLIF(source, ''), '未知') AS name, COUNT(*) AS cnt
                  FROM customers
                 WHERE tenant_id = ?
                 GROUP BY COALESCE(NULLIF(source, ''), '未知')
                 ORDER BY cnt DESC
                 LIMIT 8
                """,
                (rs, i) -> new NameCount(rs.getString("name"), rs.getLong("cnt")),
                TenantContext.get());
    }

    /** 客户等级分布。 */
    public List<NameCount> byLevel() {
        return jdbc.query("""
                SELECT level AS name, COUNT(*) AS cnt
                  FROM customers
                 WHERE tenant_id = ?
                 GROUP BY level
                 ORDER BY FIELD(level, 'KEY', 'NORMAL', 'LOW')
                """,
                (rs, i) -> new NameCount(rs.getString("name"), rs.getLong("cnt")),
                TenantContext.get());
    }

    /** 最近跟进动态。 */
    public List<RecentItem> recentFollowUps() {
        return jdbc.query("""
                SELECT f.id, c.name AS name, f.type, f.created_at
                  FROM follow_ups f
                  LEFT JOIN customers c ON c.id = f.customer_id
                 WHERE f.tenant_id = ?
                 ORDER BY f.id DESC
                 LIMIT 8
                """,
                (rs, i) -> new RecentItem(
                        rs.getString("name") == null ? "已删除客户" : rs.getString("name"),
                        rs.getString("type"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                TenantContext.get());
    }

    /** 最近新增客户。 */
    public List<RecentItem> recentCustomers() {
        return jdbc.query("""
                SELECT name, status, created_at
                  FROM customers
                 WHERE tenant_id = ?
                 ORDER BY id DESC
                 LIMIT 8
                """,
                (rs, i) -> new RecentItem(
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                TenantContext.get());
    }

    private Long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal queryDecimal(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 客户块：总数、本月新增、成交、跟进中 */
    public record Block(long total, long monthNew, long deal, long following) {
    }

    /** 商机块：进行中数量、总金额、赢单金额、阶段分布 */
    public record OppBlock(long active, BigDecimal amount, BigDecimal winAmount, List<StageCount> stages) {
    }

    /** 跟进块：总条数、逾期未跟进 */
    public record FollowBlock(long total, long overdue) {
    }

    public record Dashboard(Block customers, OppBlock opportunities, FollowBlock followUps) {
    }

    public record StageCount(String stage, long count, BigDecimal amount) {
    }

    public record NameCount(String name, long count) {
    }

    public record RecentItem(String name, String type, java.time.LocalDateTime createdAt) {
    }
}
