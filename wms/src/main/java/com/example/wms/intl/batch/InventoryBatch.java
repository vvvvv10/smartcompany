package com.example.wms.intl.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 库存批次（效期/FEFO）。
 *
 * <p>available（可用量）**不落库、查询时算** = quantity - reservedQty。
 * 理由：多一列就多一处「谁负责维护」的问题——本项目没有触发器、没有定时对账，
 * 而算出来的口径不会漂（两个数都在同一行 UPDATE 里事务内改）。</p>
 *
 * <p>warehouseName 是 LEFT JOIN 的展示列，仓库不可见时为 null，前端显「—」。</p>
 */
public record InventoryBatch(
        Long id,
        Long warehouseId,
        String warehouseName,
        String sku,
        String productName,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        Integer quantity,
        Integer reservedQty,
        Integer availableQty,
        BigDecimal unitCost,
        String locationCode,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<InventoryBatch> ROW_MAPPER = (rs, i) -> new InventoryBatch(
            rs.getLong("id"),
            rs.getObject("warehouse_id", Long.class),
            rs.getString("warehouse_name"),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getString("batch_no"),
            rs.getObject("production_date", LocalDate.class),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getObject("quantity", Integer.class),
            rs.getObject("reserved_qty", Integer.class),
            rs.getObject("available_qty", Integer.class),
            rs.getBigDecimal("unit_cost"),
            rs.getString("location_code"),
            rs.getString("status"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
