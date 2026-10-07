package com.example.tms.dashboard;

import com.example.tms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * TMS 统计看板数据。
 *
 * <p>所有数字都来自 tms 库实时聚合，数据量小时直接 COUNT 即可，
 * 不引入额外的缓存/异步统计组件。</p>
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext；JOIN 只看顶层 FROM 的表
 * （被驱动表由父行锚定，父行已过租户闸）。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;

    /** 看板总览：订单、车辆、司机三块的核心指标。 */
    public Dashboard summary() {
        Long orderTotal = queryLong("SELECT COUNT(*) FROM tms_orders WHERE tenant_id = ?", TenantContext.get());
        Long orderPending = queryLong("SELECT COUNT(*) FROM tms_orders WHERE status = 'PENDING' AND tenant_id = ?", TenantContext.get());
        Long orderInTransit = queryLong("SELECT COUNT(*) FROM tms_orders WHERE status = 'IN_TRANSIT' AND tenant_id = ?", TenantContext.get());
        Long orderDelivered = queryLong("SELECT COUNT(*) FROM tms_orders WHERE status = 'DELIVERED' AND tenant_id = ?", TenantContext.get());

        Long vehicleTotal = queryLong("SELECT COUNT(*) FROM tms_vehicles WHERE tenant_id = ?", TenantContext.get());
        Long vehicleIdle = queryLong("SELECT COUNT(*) FROM tms_vehicles WHERE status = 'IDLE' AND tenant_id = ?", TenantContext.get());
        Long vehicleBusy = queryLong("SELECT COUNT(*) FROM tms_vehicles WHERE status = 'BUSY' AND tenant_id = ?", TenantContext.get());

        Long driverTotal = queryLong("SELECT COUNT(*) FROM tms_drivers WHERE tenant_id = ?", TenantContext.get());
        Long driverActive = queryLong("SELECT COUNT(*) FROM tms_drivers WHERE status = 'ACTIVE' AND tenant_id = ?", TenantContext.get());

        return new Dashboard(
                new OrderBlock(orderTotal, orderPending, orderInTransit, orderDelivered, byOrderStatus()),
                new VehicleBlock(vehicleTotal, vehicleIdle, vehicleBusy, byVehicleStatus()),
                new DriverBlock(driverTotal, driverActive, byDriverStatus()));
    }

    /** 订单状态分布：前端画饼图/柱状图用。 */
    public List<StatusCount> byOrderStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS cnt
                  FROM tms_orders
                 WHERE tenant_id = ?
                 GROUP BY status
                 ORDER BY FIELD(status, 'PENDING', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')
                """,
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("cnt")),
                TenantContext.get());
    }

    /** 车辆状态分布。 */
    public List<StatusCount> byVehicleStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS cnt
                  FROM tms_vehicles
                 WHERE tenant_id = ?
                 GROUP BY status
                 ORDER BY FIELD(status, 'IDLE', 'BUSY', 'MAINTENANCE')
                """,
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("cnt")),
                TenantContext.get());
    }

    /** 司机状态分布。 */
    public List<StatusCount> byDriverStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS cnt
                  FROM tms_drivers
                 WHERE tenant_id = ?
                 GROUP BY status
                 ORDER BY FIELD(status, 'ACTIVE', 'INACTIVE')
                """,
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("cnt")),
                TenantContext.get());
    }

    /** 成本对比：自有车辆 vs 外部车辆（货拉拉等）。 */
    public CostComparison costComparison() {
        String tenant = TenantContext.get();

        // 自有车辆汇总
        Long selfCount = queryLong("SELECT COUNT(*) FROM tms_vehicles WHERE is_external = 0 AND tenant_id = ?", tenant);
        Double selfAvgCostPerKm = queryDouble(
                "SELECT AVG(cost_per_km) FROM tms_vehicles WHERE is_external = 0 AND tenant_id = ?", tenant);
        Double selfTotalFixedCost = queryDouble(
                "SELECT SUM(daily_fixed_cost) FROM tms_vehicles WHERE is_external = 0 AND tenant_id = ?", tenant);

        // 外部车辆汇总
        Long externalCount = queryLong("SELECT COUNT(*) FROM tms_vehicles WHERE is_external = 1 AND tenant_id = ?", tenant);
        Double externalAvgCostPerKm = queryDouble(
                "SELECT AVG(cost_per_km) FROM tms_vehicles WHERE is_external = 1 AND tenant_id = ?", tenant);
        Double externalTotalFixedCost = queryDouble(
                "SELECT SUM(daily_fixed_cost) FROM tms_vehicles WHERE is_external = 1 AND tenant_id = ?", tenant);

        // 历史订单成本（按车辆类型分组）：顶层 FROM 是订单表，租户条件加在 o 上；
        // 车辆表走 JOIN，由已过租户闸的 o.vehicle_id 锚定，不重复过滤
        Double selfOrderCost = queryDouble("""
                SELECT COALESCE(SUM(o.cost), 0) FROM tms_orders o
                 JOIN tms_vehicles v ON o.vehicle_id = v.id
                WHERE v.is_external = 0 AND o.status != 'CANCELLED'
                  AND o.tenant_id = ?
                """, tenant);
        Double externalOrderCost = queryDouble("""
                SELECT COALESCE(SUM(o.cost), 0) FROM tms_orders o
                 JOIN tms_vehicles v ON o.vehicle_id = v.id
                WHERE v.is_external = 1 AND o.status != 'CANCELLED'
                  AND o.tenant_id = ?
                """, tenant);
        Long selfOrderCount = queryLong("""
                SELECT COUNT(*) FROM tms_orders o
                 JOIN tms_vehicles v ON o.vehicle_id = v.id
                WHERE v.is_external = 0 AND o.status != 'CANCELLED'
                  AND o.tenant_id = ?
                """, tenant);
        Long externalOrderCount = queryLong("""
                SELECT COUNT(*) FROM tms_orders o
                 JOIN tms_vehicles v ON o.vehicle_id = v.id
                WHERE v.is_external = 1 AND o.status != 'CANCELLED'
                  AND o.tenant_id = ?
                """, tenant);

        // 单公里成本对比
        double selfCostPerKm = selfAvgCostPerKm != null ? selfAvgCostPerKm : 0;
        double externalCostPerKm = externalAvgCostPerKm != null ? externalAvgCostPerKm : 0;

        // 单均成本对比
        double selfAvgOrderCost = selfOrderCount > 0 ? selfOrderCost / selfOrderCount : 0;
        double externalAvgOrderCost = externalOrderCount > 0 ? externalOrderCost / externalOrderCount : 0;

        return new CostComparison(
                new VehicleTypeSummary(selfCount, selfCostPerKm, selfTotalFixedCost, selfOrderCost, selfOrderCount, selfAvgOrderCost),
                new VehicleTypeSummary(externalCount, externalCostPerKm, externalTotalFixedCost, externalOrderCost, externalOrderCount, externalAvgOrderCost),
                selfCostPerKm - externalCostPerKm,
                selfAvgOrderCost - externalAvgOrderCost);
    }

    /** 智能派车推荐：根据订单距离和车辆状态推荐最优车辆。 */
    public List<DispatchRecommendation> recommendDispatch(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        // 获取订单信息（跨租户猜 id 在这里就被租户条件挡掉，返回空列表）
        var orderRows = jdbc.queryForList(
                "SELECT id, distance_km, status FROM tms_orders WHERE id = ? AND tenant_id = ?",
                orderId, TenantContext.get());
        if (orderRows.isEmpty()) {
            return List.of();
        }
        var order = orderRows.get(0);
        double distanceKm = order.get("distance_km") != null
                ? ((java.math.BigDecimal) order.get("distance_km")).doubleValue() : 0;
        String orderStatus = (String) order.get("status");
        if (!"PENDING".equals(orderStatus)) {
            return List.of(); // 只有待处理订单才能派车
        }

        // 获取空闲车辆，按预估成本排序
        return jdbc.query("""
                SELECT v.id, v.plate_no, v.type, v.driver_name, v.is_external,
                       v.cost_per_km, v.daily_fixed_cost
                  FROM tms_vehicles v
                 WHERE v.status = 'IDLE' AND v.tenant_id = ?
                 ORDER BY v.is_external ASC, v.cost_per_km ASC
                 LIMIT 10
                """,
                (rs, i) -> {
                    long vehicleId = rs.getLong("id");
                    String plateNo = rs.getString("plate_no");
                    String type = rs.getString("type");
                    String driverName = rs.getString("driver_name");
                    boolean isExternal = rs.getBoolean("is_external");
                    double costPerKm = rs.getBigDecimal("cost_per_km") != null
                            ? rs.getBigDecimal("cost_per_km").doubleValue() : 0;
                    double dailyFixedCost = rs.getBigDecimal("daily_fixed_cost") != null
                            ? rs.getBigDecimal("daily_fixed_cost").doubleValue() : 0;

                    // 预估成本 = 距离 × 每公里成本 + 每日固定成本（自有车辆）
                    double estimatedCost = distanceKm * costPerKm + (isExternal ? 0 : dailyFixedCost);

                    // 推荐理由
                    String reason;
                    if (isExternal) {
                        reason = String.format("外部车辆，按单付费 %.0f 元", estimatedCost);
                    } else {
                        reason = String.format("自有车辆，固定成本 %.0f 元/日", dailyFixedCost);
                    }

                    return new DispatchRecommendation(
                            vehicleId, plateNo, type, driverName, isExternal,
                            costPerKm, estimatedCost, reason);
                },
                TenantContext.get());
    }

    private Double queryDouble(String sql, Object... args) {
        Double value = jdbc.queryForObject(sql, Double.class, args);
        return value == null ? 0.0 : value;
    }

    private Long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /** 订单块：总数、待处理、运输中、已送达、状态分布 */
    public record OrderBlock(long total, long pending, long inTransit, long delivered, List<StatusCount> statusDistribution) {
    }

    /** 车辆块：总数、空闲、运输中、状态分布 */
    public record VehicleBlock(long total, long idle, long busy, List<StatusCount> statusDistribution) {
    }

    /** 司机块：总数、在职、状态分布 */
    public record DriverBlock(long total, long active, List<StatusCount> statusDistribution) {
    }

    public record Dashboard(OrderBlock orders, VehicleBlock vehicles, DriverBlock drivers) {
    }

    public record StatusCount(String status, long count) {
    }

    /** 车辆类型汇总：数量、单公里成本、固定成本、订单成本、订单数、单均成本 */
    public record VehicleTypeSummary(
            long count,
            double avgCostPerKm,
            double totalFixedCost,
            double totalOrderCost,
            long orderCount,
            double avgOrderCost) {
    }

    /** 成本对比：自有 vs 外部 + 差值 */
    public record CostComparison(
            VehicleTypeSummary selfOwned,
            VehicleTypeSummary external,
            double costPerKmDiff,
            double avgOrderCostDiff) {
    }

    /** 派车推荐：车辆信息 + 预估成本 + 推荐理由 */
    public record DispatchRecommendation(
            long vehicleId,
            String plateNo,
            String type,
            String driverName,
            boolean isExternal,
            double costPerKm,
            double estimatedCost,
            String reason) {
    }
}
