package com.example.wms.inventory;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 库存。
 *
 * <p>每个仓库的每个 SKU 一条记录，quantity 为当前库存数量。</p>
 */
public record Inventory(
        Long id,
        Long warehouseId,
        String warehouseName,
        String productName,
        String sku,
        Integer quantity,
        LocalDateTime updatedAt) {

    public static final RowMapper<Inventory> ROW_MAPPER = (rs, i) -> new Inventory(
            rs.getLong("id"),
            rs.getLong("warehouse_id"),
            rs.getString("warehouse_name"),
            rs.getString("product_name"),
            rs.getString("sku"),
            rs.getObject("quantity", Integer.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
