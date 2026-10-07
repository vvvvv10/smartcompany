package com.example.oms.dashboard;

import com.example.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * OMS 统计看板数据。
 *
 * <p>所有数字都来自 oms 库实时聚合，数据量小时直接 COUNT/SUM 即可，
 * 不引入额外的缓存/异步统计组件。看板金额与款号排行都不统计已取消订单
 * （sales 的 today/month 维度与 topStyles 按约定排除 CANCELLED）。</p>
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;

    /** 订单概览：各状态计数 + 状态/渠道分布。 */
    public OrderSummary orderSummary() {
        long total = count("SELECT COUNT(*) FROM oms_orders WHERE tenant_id = ?", TenantContext.get());
        long unpaid = countByStatus("UNPAID");
        long toShip = countByStatus("TO_SHIP");
        long shipped = countByStatus("SHIPPED");
        long done = countByStatus("DONE");
        long afterSale = countByStatus("AFTER_SALE");
        long cancelled = countByStatus("CANCELLED");

        List<StatusCount> statusDistribution = jdbc.query(
                """
                SELECT status, COUNT(*) AS cnt
                  FROM oms_orders
                 WHERE tenant_id = ?
                 GROUP BY status
                """,
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("cnt")),
                TenantContext.get());

        List<ChannelCount> channelDistribution = jdbc.query(
                """
                SELECT channel, COUNT(*) AS cnt, COALESCE(SUM(total_amount), 0) AS amount
                  FROM oms_orders
                 WHERE tenant_id = ?
                 GROUP BY channel
                """,
                (rs, i) -> new ChannelCount(
                        rs.getString("channel"),
                        rs.getLong("cnt"),
                        rs.getBigDecimal("amount")),
                TenantContext.get());

        return new OrderSummary(total, unpaid, toShip, shipped, done, afterSale, cancelled,
                statusDistribution, channelDistribution);
    }

    /** 销售汇总：累计金额/件数 + 今日、本月（今日/本月不统计已取消订单）。 */
    public SalesSummary salesSummary() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        BigDecimal totalAmount = queryDecimal(
                "SELECT COALESCE(SUM(total_amount), 0) FROM oms_orders WHERE tenant_id = ?",
                TenantContext.get());
        long totalQty = count("SELECT COALESCE(SUM(total_qty), 0) FROM oms_orders WHERE tenant_id = ?",
                TenantContext.get());

        BigDecimal todayAmount = queryDecimal("""
                SELECT COALESCE(SUM(total_amount), 0) FROM oms_orders
                 WHERE status <> 'CANCELLED' AND created_at >= ? AND tenant_id = ?
                """, todayStart, TenantContext.get());
        BigDecimal monthAmount = queryDecimal("""
                SELECT COALESCE(SUM(total_amount), 0) FROM oms_orders
                 WHERE status <> 'CANCELLED' AND created_at >= ? AND tenant_id = ?
                """, monthStart, TenantContext.get());
        long todayQty = count("""
                SELECT COALESCE(SUM(total_qty), 0) FROM oms_orders
                 WHERE created_at >= ? AND tenant_id = ?
                """, todayStart, TenantContext.get());
        long todayOrderCount = count("""
                SELECT COUNT(*) FROM oms_orders
                 WHERE created_at >= ? AND tenant_id = ?
                """, todayStart, TenantContext.get());

        return new SalesSummary(totalAmount, todayAmount, monthAmount, totalQty, todayQty, todayOrderCount);
    }

    /** 款号销量 TOP5：按明细聚合，排除已取消订单。 */
    public List<TopStyle> topStyles() {
        return jdbc.query("""
                SELECT i.style_no AS style_no,
                       MAX(i.product_name) AS product_name,
                       SUM(i.quantity) AS qty,
                       SUM(i.quantity * i.price) AS amount
                  FROM oms_order_items i
                  JOIN oms_orders o ON o.id = i.order_id
                 WHERE i.tenant_id = ? AND o.status <> 'CANCELLED'
                 GROUP BY i.style_no
                 ORDER BY qty DESC, amount DESC
                 LIMIT 5
                """,
                (rs, i) -> new TopStyle(
                        rs.getString("style_no"),
                        rs.getString("product_name"),
                        rs.getLong("qty"),
                        rs.getBigDecimal("amount")),
                TenantContext.get());
    }

    private long countByStatus(String status) {
        return count("SELECT COUNT(*) FROM oms_orders WHERE status = ? AND tenant_id = ?",
                status, TenantContext.get());
    }

    private long count(String sql, Object... args) {
        Long value = args.length == 0
                ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal queryDecimal(String sql, Object... args) {
        BigDecimal value = args.length == 0
                ? jdbc.queryForObject(sql, BigDecimal.class)
                : jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 订单概览：总数 + 六个状态计数 + 状态/渠道分布 */
    public record OrderSummary(
            long total,
            long unpaid,
            long toShip,
            long shipped,
            long done,
            long afterSale,
            long cancelled,
            List<StatusCount> statusDistribution,
            List<ChannelCount> channelDistribution) {
    }

    /** 单个状态的订单数 */
    public record StatusCount(String status, long count) {
    }

    /** 单个渠道的订单数与金额 */
    public record ChannelCount(String channel, long count, BigDecimal amount) {
    }

    /** 销售汇总：累计/今日/本月金额、累计/今日件数、今日订单数 */
    public record SalesSummary(
            BigDecimal totalAmount,
            BigDecimal todayAmount,
            BigDecimal monthAmount,
            long totalQty,
            long todayQty,
            long todayOrderCount) {
    }

    /** 款号 TOP：款号、品名、件数、金额 */
    public record TopStyle(String styleNo, String name, long qty, BigDecimal amount) {
    }
}
