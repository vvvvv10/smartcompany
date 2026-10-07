package com.example.oms.order;

import java.math.BigDecimal;
import org.springframework.jdbc.core.RowMapper;

/** 订单明细：一行 = 一个款号下某种颜色/尺码的成交记录。 */
public record OrderItem(
        Long id,
        Long orderId,
        Long productId,
        String styleNo,
        String productName,
        String color,
        String size,
        Integer quantity,
        BigDecimal price) {

    public static final RowMapper<OrderItem> ROW_MAPPER = (rs, i) -> new OrderItem(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getObject("product_id", Long.class),
            rs.getString("style_no"),
            rs.getString("product_name"),
            rs.getString("color"),
            rs.getString("size"),
            rs.getObject("quantity", Integer.class),
            rs.getBigDecimal("price"));
}
