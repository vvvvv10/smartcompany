package com.example.wms.intl;

import com.example.wms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 国际仓储看板（备货任务 / 库存批次 / 在途 / 仓库结构）。
 *
 * <p>「临期」的口径写死在这里：{@code expiry_date <= UTC_DATE() + 7 天且未过期}。
 * 为什么不做成参数：看板是给人看「现在要不要催处理」的，阈值是一个业务判断，
 * 一旦让人能在看板上随手调，页面上的数字就不再有可比性了。
 * 列表页那个「只看临期 N 天」的筛选是另一回事，那是筛选不是看板。</p>
 */
@Service
@RequiredArgsConstructor
public class IntlDashboardService {

    private final JdbcTemplate jdbc;

    public record StatusCount(String status, long count) {
    }

    public record PackTaskStat(
            long total, long pending, long picking, long packed, long labelled,
            long handedOver, long shortage, List<StatusCount> statusDistribution) {
    }

    public record BatchStat(long total, long nearExpiring, long expired, long frozen) {
    }

    public record WarehouseQty(String warehouseName, long qty) {
    }

    public record InTransitStat(long total, long inTransit, long arrived, long lost,
                                List<WarehouseQty> byWarehouse) {
    }

    public record WarehouseStat(long total, long oversea, long bonded, long domestic) {
    }

    public record Dashboard(PackTaskStat packTask, BatchStat batch, InTransitStat inTransit,
                            WarehouseStat warehouse) {
    }

    public Dashboard dashboard() {
        String tenant = TenantContext.get();

        List<StatusCount> taskDistribution = jdbc.query(
                "SELECT status, COUNT(*) AS c FROM wms_pack_tasks WHERE tenant_id = ? GROUP BY status",
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("c")), tenant);
        PackTaskStat packTask = new PackTaskStat(
                sum(taskDistribution),
                count(taskDistribution, "PENDING"),
                count(taskDistribution, "PICKING"),
                count(taskDistribution, "PACKED"),
                count(taskDistribution, "LABELLED"),
                count(taskDistribution, "HANDED_OVER"),
                count(taskDistribution, "SHORTAGE"),
                taskDistribution);

        BatchStat batch = jdbc.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(expiry_date IS NOT NULL
                            AND expiry_date <= UTC_DATE() + INTERVAL 7 DAY
                            AND expiry_date >= UTC_DATE()), 0),
                       COALESCE(SUM(expiry_date IS NOT NULL AND expiry_date < UTC_DATE()), 0),
                       COALESCE(SUM(status = 'FROZEN'), 0)
                  FROM wms_inventory_batches WHERE tenant_id = ?
                """, (rs, i) -> new BatchStat(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                tenant);

        List<WarehouseQty> byWarehouse = jdbc.query("""
                SELECT COALESCE(w.name, '未指定') AS warehouse_name, SUM(t.quantity) AS qty
                  FROM wms_in_transit t
                  LEFT JOIN wms_warehouses w ON w.id = t.to_warehouse_id
                 WHERE t.tenant_id = ? AND t.status IN ('IN_TRANSIT','ARRIVED')
                 GROUP BY t.to_warehouse_id, w.name
                 ORDER BY qty DESC LIMIT 8
                """, (rs, i) -> new WarehouseQty(rs.getString("warehouse_name"), rs.getLong("qty")), tenant);
        List<StatusCount> transitDistribution = jdbc.query(
                "SELECT status, COUNT(*) AS c FROM wms_in_transit WHERE tenant_id = ? GROUP BY status",
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("c")), tenant);
        InTransitStat inTransit = new InTransitStat(
                sum(transitDistribution),
                count(transitDistribution, "IN_TRANSIT"),
                count(transitDistribution, "ARRIVED") + count(transitDistribution, "RECEIVED"),
                count(transitDistribution, "LOST"),
                byWarehouse);

        WarehouseStat warehouse = jdbc.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(warehouse_type = 'OVERSEAS'), 0),
                       COALESCE(SUM(warehouse_type = 'BONDED'), 0),
                       COALESCE(SUM(warehouse_type = 'DOMESTIC'), 0)
                  FROM wms_warehouses WHERE tenant_id = ? AND status = 'ACTIVE'
                """, (rs, i) -> new WarehouseStat(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                tenant);

        return new Dashboard(packTask, batch, inTransit, warehouse);
    }

    private static long sum(List<StatusCount> distribution) {
        return distribution.stream().mapToLong(StatusCount::count).sum();
    }

    private static long count(List<StatusCount> distribution, String status) {
        return distribution.stream().filter(d -> status.equals(d.status()))
                .mapToLong(StatusCount::count).sum();
    }
}
