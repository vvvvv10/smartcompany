package com.example.crm.dashboard;

import com.example.crm.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 个人工作台数据：全部按 X-User-Id 归属人/创建人过滤，只看"我的"。
 *
 * <p>与 {@link DashboardService}（团队全量聚合）互补：工作台回答
 * "今天我该跟进谁、我手上有多少商机"，看板回答"团队整体经营情况"。</p>
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkbenchService {

    private final JdbcTemplate jdbc;

    /** 我的概览 + 今日待办 + 我的商机 + 我的最近动态，一次请求全返回。 */
    public Workbench summary(long userId) {
        Stats stats = stats(userId);
        return new Workbench(
                stats,
                todos(userId),
                myOpportunities(userId),
                recentCustomers(userId),
                recentFollowUps(userId));
    }

    private Stats stats(long userId) {
        long customers = count("SELECT COUNT(*) FROM customers WHERE owner_id = ? AND tenant_id = ?", userId);
        long following = count(
                "SELECT COUNT(*) FROM customers WHERE owner_id = ? AND status = 'FOLLOWING' AND tenant_id = ?", userId);
        long deal = count(
                "SELECT COUNT(*) FROM customers WHERE owner_id = ? AND status = 'DEAL' AND tenant_id = ?", userId);
        long weekNew = count("""
                SELECT COUNT(*) FROM customers
                 WHERE owner_id = ? AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) AND tenant_id = ?
                """, userId);
        long oppActive = count("""
                SELECT COUNT(*) FROM opportunities
                 WHERE owner_id = ? AND stage NOT IN ('WON', 'LOST') AND tenant_id = ?
                """, userId);
        BigDecimal oppAmount = decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM opportunities
                 WHERE owner_id = ? AND stage NOT IN ('WON', 'LOST') AND tenant_id = ?
                """, userId);
        BigDecimal winAmount = decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM opportunities
                 WHERE owner_id = ? AND stage = 'WON' AND tenant_id = ?
                """, userId);
        long today = count("""
                SELECT COUNT(*) FROM follow_ups
                 WHERE creator_id = ? AND next_follow_at IS NOT NULL
                   AND DATE(next_follow_at) = CURDATE() AND tenant_id = ?
                """, userId);
        long overdue = count("""
                SELECT COUNT(*) FROM follow_ups
                 WHERE creator_id = ? AND next_follow_at IS NOT NULL AND next_follow_at < NOW()
                   AND tenant_id = ?
                """, userId);
        long upcoming = count("""
                SELECT COUNT(*) FROM follow_ups
                 WHERE creator_id = ? AND next_follow_at IS NOT NULL
                   AND next_follow_at >= NOW()
                   AND DATE(next_follow_at) <= DATE_ADD(CURDATE(), INTERVAL 7 DAY)
                   AND tenant_id = ?
                """, userId);
        return new Stats(customers, following, deal, weekNew, oppActive,
                oppAmount, winAmount, today, overdue, upcoming);
    }

    /** 我的跟进待办：逾期 + 今天 + 未来 7 天，按时间升序（逾期排最前）。 */
    public List<Todo> todos(long userId) {
        return jdbc.query("""
                SELECT f.id, f.customer_id, COALESCE(c.name, '已删除客户') AS customer_name,
                       f.type, f.content, f.next_follow_at
                  FROM follow_ups f
                  LEFT JOIN customers c ON c.id = f.customer_id
                 WHERE f.creator_id = ? AND f.next_follow_at IS NOT NULL
                   AND DATE(f.next_follow_at) <= DATE_ADD(CURDATE(), INTERVAL 7 DAY)
                   AND f.tenant_id = ?
                 ORDER BY f.next_follow_at ASC
                 LIMIT 10
                """,
                ps -> {
                    ps.setLong(1, userId);
                    ps.setString(2, TenantContext.get());
                },
                (rs, i) -> new Todo(
                        rs.getLong("id"),
                        rs.getLong("customer_id"),
                        rs.getString("customer_name"),
                        rs.getString("type"),
                        rs.getString("content"),
                        rs.getTimestamp("next_follow_at").toLocalDateTime(),
                        dueOf(rs.getTimestamp("next_follow_at").toLocalDateTime())));
    }

    /** 逾期=时间已过；今天=还剩不到 24h 且落在今天；否则是未来待办。 */
    private static String dueOf(LocalDateTime at) {
        if (at.isBefore(LocalDateTime.now())) {
            return "OVERDUE";
        }
        if (at.toLocalDate().equals(java.time.LocalDate.now())) {
            return "TODAY";
        }
        return "UPCOMING";
    }

    /** 我的进行中商机，按预计成交日期升序（快到期的排前面）。 */
    public List<MyOpportunity> myOpportunities(long userId) {
        return jdbc.query("""
                SELECT o.id, o.name, o.amount, o.stage, o.probability, o.expected_close_date,
                       COALESCE(c.name, '已删除客户') AS customer_name
                  FROM opportunities o
                  LEFT JOIN customers c ON c.id = o.customer_id
                 WHERE o.owner_id = ? AND o.stage NOT IN ('WON', 'LOST') AND o.tenant_id = ?
                 ORDER BY o.expected_close_date IS NULL, o.expected_close_date ASC, o.id DESC
                 LIMIT 6
                """,
                ps -> {
                    ps.setLong(1, userId);
                    ps.setString(2, TenantContext.get());
                },
                (rs, i) -> new MyOpportunity(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBigDecimal("amount"),
                        rs.getString("stage"),
                        rs.getInt("probability"),
                        rs.getDate("expected_close_date") == null
                                ? null : rs.getDate("expected_close_date").toLocalDate(),
                        rs.getString("customer_name")));
    }

    /** 我最近建档的客户。 */
    public List<MyCustomer> recentCustomers(long userId) {
        return jdbc.query("""
                SELECT id, name, level, status, created_at
                  FROM customers
                 WHERE owner_id = ? AND tenant_id = ?
                 ORDER BY id DESC
                 LIMIT 6
                """,
                ps -> {
                    ps.setLong(1, userId);
                    ps.setString(2, TenantContext.get());
                },
                (rs, i) -> new MyCustomer(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("level"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
    }

    /** 我最近写下的跟进记录。 */
    public List<MyFollowUp> recentFollowUps(long userId) {
        return jdbc.query("""
                SELECT f.id, f.type, f.content, f.created_at,
                       COALESCE(c.name, '已删除客户') AS customer_name
                  FROM follow_ups f
                  LEFT JOIN customers c ON c.id = f.customer_id
                 WHERE f.creator_id = ? AND f.tenant_id = ?
                 ORDER BY f.id DESC
                 LIMIT 6
                """,
                ps -> {
                    ps.setLong(1, userId);
                    ps.setString(2, TenantContext.get());
                },
                (rs, i) -> new MyFollowUp(
                        rs.getString("customer_name"),
                        rs.getString("type"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
    }

    /** userId 占第一个 ?，租户占 SQL 末尾的 tenant_id = ?（与各 SQL 的占位顺序约定一致）。 */
    private long count(String sql, long userId) {
        Long value = jdbc.queryForObject(sql, Long.class, userId, TenantContext.get());
        return value == null ? 0L : value;
    }

    private BigDecimal decimal(String sql, long userId) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, userId, TenantContext.get());
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 我的概览指标 */
    public record Stats(long customers, long following, long deal, long weekNew,
                        long oppActive, BigDecimal oppAmount, BigDecimal winAmount,
                        long today, long overdue, long upcoming) {
    }

    /** 今日待办（含逾期与未来 7 天）：due = OVERDUE/TODAY/UPCOMING */
    public record Todo(long id, long customerId, String customerName, String type,
                       String content, LocalDateTime nextFollowAt, String due) {
    }

    /** 我的进行中商机 */
    public record MyOpportunity(long id, String name, BigDecimal amount, String stage,
                                int probability, java.time.LocalDate expectedCloseDate,
                                String customerName) {
    }

    /** 我的最近客户 */
    public record MyCustomer(long id, String name, String level, String status,
                             LocalDateTime createdAt) {
    }

    /** 我的最近跟进 */
    public record MyFollowUp(String customerName, String type, String content,
                             LocalDateTime createdAt) {
    }

    /** 工作台整体返回 */
    public record Workbench(Stats stats, List<Todo> todos, List<MyOpportunity> opportunities,
                            List<MyCustomer> customers, List<MyFollowUp> followUps) {
    }
}
