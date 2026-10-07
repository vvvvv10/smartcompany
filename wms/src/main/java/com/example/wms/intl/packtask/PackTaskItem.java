package com.example.wms.intl.packtask;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 备货任务明细（一个 SKU 一行，带批次与效期）。
 *
 * <p>quantity 是「应备」，pickedQuantity 是「实备」——两者不等就是缺料。
 * 分两列而不是只留一个，是因为缺料时需要知道「差多少」才能去催采购。</p>
 *
 * <p>expiryDate 是 FEFO（先到期先出）的主排序键。</p>
 */
public record PackTaskItem(
        Long id,
        Long taskId,
        String sku,
        String productName,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        Integer quantity,
        Integer pickedQuantity,
        String hsCode,
        LocalDateTime createdAt) {

    public static final RowMapper<PackTaskItem> ROW_MAPPER = (rs, i) -> new PackTaskItem(
            rs.getLong("id"),
            rs.getObject("task_id", Long.class),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getString("batch_no"),
            rs.getObject("production_date", LocalDate.class),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getObject("quantity", Integer.class),
            rs.getObject("picked_quantity", Integer.class),
            rs.getString("hs_code"),
            rs.getObject("created_at", LocalDateTime.class));
}
