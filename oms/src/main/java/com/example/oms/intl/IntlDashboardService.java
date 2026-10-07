package com.example.oms.intl;

import com.example.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 国际出口看板。
 *
 * <p><b>禁止跨币种聚合</b>（计划 §11 R3）：「本月出口额」这一栏只汇总 USD，
 * 其他币种单列出现。USD + CNY 直接 SUM 出来的数字没有意义，
 * 与其给一个看起来很真的错数字，不如明确写清口径。</p>
 *
 * <p>与现有 {@code /api/oms/dashboard} 并存：那个是国内零售销售额口径，
 * 混进来会让「本月出口额」既不是零售额也不是出口额。</p>
 */
@Service
@RequiredArgsConstructor
public class IntlDashboardService {

    private final JdbcTemplate jdbc;

    public record StatusCount(String status, long count) {
    }

    public record CountryCount(String country, long count) {
    }

    public record CurrencyAmount(String currency, BigDecimal amount) {
    }

    public record OrderStat(
            long total,
            long draft,
            long confirmed,
            long picking,
            long packed,
            long declared,
            long shipped,
            long inTransit,
            long arrived,
            long clearing,
            long cleared,
            long lastMile,
            long delivered,
            long exception,
            List<StatusCount> statusDistribution,
            List<CountryCount> topCountries) {
    }

    public record Stat(
            OrderStat order,
            long monthAmountUsd,
            int monthQty,
            long inTransit,
            long exceptionCount,
            List<CurrencyAmount> monthAmountByCurrency) {
    }

    public Stat dashboard() {
        String tenant = TenantContext.get();
        List<StatusCount> distribution = jdbc.query(
                "SELECT status, COUNT(*) AS c FROM oms_export_orders WHERE tenant_id = ? GROUP BY status",
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("c")), tenant);
        List<CountryCount> countries = jdbc.query(
                "SELECT consignee_country, COUNT(*) AS c FROM oms_export_orders "
                        + "WHERE tenant_id = ? AND consignee_country <> '' "
                        + "GROUP BY consignee_country ORDER BY c DESC LIMIT 8",
                (rs, i) -> new CountryCount(rs.getString("consignee_country"), rs.getLong("c")), tenant);
        // 月度金额分币种汇总，前端挑 USD 显示「本月出口额」，其余币种在下面一行显示
        List<CurrencyAmount> byCurrency = jdbc.query(
                "SELECT currency, COALESCE(SUM(total_amount), 0) AS amt FROM oms_export_orders "
                        + "WHERE tenant_id = ? AND created_at >= UTC_DATE() - INTERVAL 30 DAY "
                        + "GROUP BY currency ORDER BY amt DESC",
                (rs, i) -> new CurrencyAmount(rs.getString("currency"), rs.getBigDecimal("amt")), tenant);

        BigDecimal usd = BigDecimal.ZERO;
        int monthQty = 0;
        for (CurrencyAmount c : byCurrency) {
            if ("USD".equals(c.currency())) {
                usd = c.amount();
            }
        }
        Integer qty = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_qty), 0) FROM oms_export_orders "
                        + "WHERE tenant_id = ? AND created_at >= UTC_DATE() - INTERVAL 30 DAY",
                Integer.class, tenant);

        OrderStat orderStat = new OrderStat(
                sum(distribution),
                count(distribution, "DRAFT"),
                count(distribution, "CONFIRMED"),
                count(distribution, "PICKING"),
                count(distribution, "PACKED"),
                count(distribution, "EXPORT_DECLARED"),
                count(distribution, "SHIPPED"),
                count(distribution, "IN_TRANSIT"),
                count(distribution, "ARRIVED"),
                count(distribution, "CUSTOMS_CLEARING"),
                count(distribution, "CUSTOMS_CLEARED"),
                count(distribution, "LAST_MILE"),
                count(distribution, "DELIVERED"),
                count(distribution, "EXCEPTION"),
                distribution,
                countries);

        Long inTransit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_export_orders WHERE tenant_id = ? "
                        + "AND status IN ('EXPORT_DECLARED','SHIPPED','IN_TRANSIT','ARRIVED')",
                Long.class, tenant);

        return new Stat(orderStat, usd.longValue(), qty == null ? 0 : qty,
                inTransit == null ? 0 : inTransit, count(distribution, "EXCEPTION"), byCurrency);
    }

    private static long sum(List<StatusCount> distribution) {
        return distribution.stream().mapToLong(StatusCount::count).sum();
    }

    private static long count(List<StatusCount> distribution, String status) {
        return distribution.stream().filter(d -> status.equals(d.status()))
                .mapToLong(StatusCount::count).sum();
    }
}
