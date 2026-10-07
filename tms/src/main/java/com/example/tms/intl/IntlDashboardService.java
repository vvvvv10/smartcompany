package com.example.tms.intl;

import com.example.tms.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 国际运单看板。
 *
 * <p><b>刻意不加缓存</b>：现有四个服务的 Dashboard 全是实时 COUNT/SUM、无缓存
 * （计划 §11 R4），本表数据量在千行以内，聚合走 idx_status / idx_etd 就够。
 * 为了一千行数据引入 Redis 缓存，运维成本远超收益。</p>
 *
 * <p><b>时间口径</b>：「今日出港 / 未来 7 天」用 UTC_DATE()，与本库新表存 UTC 的
 * 约定对齐（存量代码用 CURDATE()，那是本地时区，新表代码里改回来会被误当 bug）。
 * 「已逾期」= ETD 已过但状态还没到 DEPARTED。</p>
 */
@Service
@RequiredArgsConstructor
public class IntlDashboardService {

    private final JdbcTemplate jdbc;

    /** 状态分布：一次 GROUP BY 出全部状态计数，前端自己按枚举顺序拼卡片。 */
    public record StatusCount(String status, long count) {
    }

    public record ShipmentStat(
            long total,
            long draft,
            long booking,
            long booked,
            long pickedUp,
            long declared,
            long departed,
            long inTransit,
            long arrived,
            long clearing,
            long cleared,
            long lastMile,
            long delivered,
            long exception,
            List<StatusCount> statusDistribution) {
    }

    public record EtdStat(long todayDepart, long next7Days, long overdue) {
    }

    public record WeightStat(BigDecimal totalGross, BigDecimal totalVolume, BigDecimal totalChargeable) {
    }

    public record Dashboard(ShipmentStat shipment, EtdStat etd, Map<String, Long> containers, WeightStat weight) {
    }

    public Dashboard dashboard() {
        String tenant = TenantContext.get();
        List<StatusCount> distribution = jdbc.query(
                "SELECT status, COUNT(*) AS c FROM tms_shipments WHERE tenant_id = ? GROUP BY status",
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("c")), tenant);
        ShipmentStat shipmentStat = new ShipmentStat(
                sum(distribution),
                count(distribution, "DRAFT"),
                count(distribution, "BOOKING"),
                count(distribution, "BOOKED"),
                count(distribution, "PICKED_UP"),
                count(distribution, "EXPORT_DECLARED"),
                count(distribution, "DEPARTED"),
                count(distribution, "IN_TRANSIT"),
                count(distribution, "ARRIVED"),
                count(distribution, "CLEARING"),
                count(distribution, "CLEARED"),
                count(distribution, "LAST_MILE"),
                count(distribution, "DELIVERED"),
                count(distribution, "EXCEPTION"),
                distribution);

        Long todayDepart = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tms_shipments
                 WHERE tenant_id = ? AND etd_at >= UTC_DATE()
                   AND etd_at < UTC_DATE() + INTERVAL 1 DAY
                """, Long.class, tenant);
        Long next7Days = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tms_shipments
                 WHERE tenant_id = ? AND etd_at >= UTC_DATE()
                   AND etd_at < UTC_DATE() + INTERVAL 7 DAY
                """, Long.class, tenant);
        Long overdue = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tms_shipments
                 WHERE tenant_id = ? AND etd_at IS NOT NULL AND etd_at < UTC_DATE()
                   AND status NOT IN ('DEPARTED','IN_TRANSIT','ARRIVED','CLEARING','CLEARED',
                                      'LAST_MILE','DELIVERED','POD_CONFIRMED','CANCELLED')
                """, Long.class, tenant);
        EtdStat etdStat = new EtdStat(orZero(todayDepart), orZero(next7Days), orZero(overdue));

        Map<String, Long> containers = new java.util.HashMap<>();
        jdbc.query("SELECT container_type, COUNT(*) AS c FROM tms_shipments "
                        + "WHERE tenant_id = ? AND container_type <> '' GROUP BY container_type",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> containers.put(rs.getString("container_type"), rs.getLong("c")), tenant);

        WeightStat weightStat = jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_gross_weight), 0), COALESCE(SUM(total_volume), 0),
                       COALESCE(SUM(chargeable_weight), 0)
                  FROM tms_shipments WHERE tenant_id = ?
                """, (rs, i) -> new WeightStat(
                rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), tenant);

        return new Dashboard(shipmentStat, etdStat, containers, weightStat);
    }

    private static long sum(List<StatusCount> distribution) {
        return distribution.stream().mapToLong(StatusCount::count).sum();
    }

    private static long count(List<StatusCount> distribution, String status) {
        return distribution.stream().filter(d -> status.equals(d.status()))
                .mapToLong(StatusCount::count).sum();
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
