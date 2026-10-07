package com.example.oms.product;

import java.math.BigDecimal;
import org.springframework.jdbc.core.RowMapper;

/**
 * 商品下拉/选择器用的轻量投影：只要款号、品名、颜色、尺码、价格。
 */
public record ProductBrief(
        Long id,
        String styleNo,
        String name,
        String color,
        String size,
        BigDecimal price) {

    public static final RowMapper<ProductBrief> ROW_MAPPER = (rs, i) -> new ProductBrief(
            rs.getLong("id"),
            rs.getString("style_no"),
            rs.getString("name"),
            rs.getString("color"),
            rs.getString("size"),
            rs.getBigDecimal("price"));
}
