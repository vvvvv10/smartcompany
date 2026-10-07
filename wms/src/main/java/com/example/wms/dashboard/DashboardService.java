package com.example.wms.dashboard;

import com.example.wms.tenant.TenantContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * WMS 统计看板数据。
 *
 * <p>所有数字都来自 wms 库实时聚合，数据量小时直接 COUNT 即可，
 * 不引入额外的缓存/异步统计组件。</p>
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;

    /** 看板总览：仓库数、库存总量、商品种类数。 */
    public Dashboard summary() {
        Long warehouseCount = queryLong(
                "SELECT COUNT(*) FROM wms_warehouses WHERE status = 'ACTIVE' AND tenant_id = ?",
                TenantContext.get());
        Long totalQuantity = queryLong(
                "SELECT COALESCE(SUM(quantity), 0) FROM wms_inventory WHERE tenant_id = ?",
                TenantContext.get());
        Long productTypes = queryLong(
                "SELECT COUNT(DISTINCT product_name) FROM wms_inventory WHERE tenant_id = ?",
                TenantContext.get());

        return new Dashboard(warehouseCount, totalQuantity, productTypes);
    }

    /** 库存汇总：总库存量、仓库数、商品种类数。 */
    public StockSummary stockSummary() {
        Long totalQuantity = queryLong(
                "SELECT COALESCE(SUM(quantity), 0) FROM wms_inventory WHERE tenant_id = ?",
                TenantContext.get());
        Long warehouseCount = queryLong(
                "SELECT COUNT(*) FROM wms_warehouses WHERE status = 'ACTIVE' AND tenant_id = ?",
                TenantContext.get());
        Long productTypes = queryLong(
                "SELECT COUNT(DISTINCT product_name) FROM wms_inventory WHERE tenant_id = ?",
                TenantContext.get());
        Long lowStockCount = queryLong(
                "SELECT COUNT(*) FROM wms_inventory WHERE quantity < 10 AND tenant_id = ?",
                TenantContext.get());

        return new StockSummary(totalQuantity, warehouseCount, productTypes, lowStockCount);
    }

    /** 出入库统计：今日入库、今日出库、本月入库、本月出库。 */
    public RecordSummary recordSummary() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        Long todayIn = queryLong("""
                SELECT COALESCE(SUM(quantity), 0) FROM wms_stock_records
                 WHERE type = 'IN' AND created_at >= ? AND tenant_id = ?
                """, todayStart, TenantContext.get());

        Long todayOut = queryLong("""
                SELECT COALESCE(SUM(quantity), 0) FROM wms_stock_records
                 WHERE type = 'OUT' AND created_at >= ? AND tenant_id = ?
                """, todayStart, TenantContext.get());

        Long monthIn = queryLong("""
                SELECT COALESCE(SUM(quantity), 0) FROM wms_stock_records
                 WHERE type = 'IN' AND created_at >= ? AND tenant_id = ?
                """, monthStart, TenantContext.get());

        Long monthOut = queryLong("""
                SELECT COALESCE(SUM(quantity), 0) FROM wms_stock_records
                 WHERE type = 'OUT' AND created_at >= ? AND tenant_id = ?
                """, monthStart, TenantContext.get());

        return new RecordSummary(todayIn, todayOut, monthIn, monthOut);
    }

    private Long queryLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private Long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /** 看板总览：仓库数、库存总量、商品种类数 */
    public record Dashboard(long warehouseCount, long totalQuantity, long productTypes) {
    }

    /** 库存汇总：总库存量、仓库数、商品种类数、低库存数 */
    public record StockSummary(long totalQuantity, long warehouseCount, long productTypes, long lowStockCount) {
    }

    /** 出入库统计：今日入库、今日出库、本月入库、本月出库 */
    public record RecordSummary(long todayIn, long todayOut, long monthIn, long monthOut) {
    }
}
