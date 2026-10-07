package com.example.wms.stockrecord;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 出入库记录。
 *
 * <p>type: IN 入库 / OUT 出库</p>
 *
 * <p>orderNo 关联的 OMS 订单号（39 号关联方案）：OMS 发货时自动建的出库记录
 * 带 OMS 订单号，手工建的保持空串——WMS 列表靠它反查 OMS 订单。</p>
 */
public record StockRecord(
        Long id,
        Long warehouseId,
        String warehouseName,
        String productName,
        String type,
        Integer quantity,
        String remark,
        String orderNo,
        LocalDateTime createdAt) {

    public static final RowMapper<StockRecord> ROW_MAPPER = (rs, i) -> new StockRecord(
            rs.getLong("id"),
            rs.getLong("warehouse_id"),
            rs.getString("warehouse_name"),
            rs.getString("product_name"),
            rs.getString("type"),
            rs.getObject("quantity", Integer.class),
            rs.getString("remark"),
            rs.getString("order_no"),
            rs.getObject("created_at", LocalDateTime.class));
}
