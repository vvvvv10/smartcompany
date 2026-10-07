package com.example.tms.order;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 运输订单。
 *
 * <p>status: PENDING 待处理 / IN_TRANSIT 运输中 / DELIVERED 已送达 / CANCELLED 已取消</p>
 */
public record Order(
        Long id,
        String orderNo,
        String customerName,
        String origin,
        String destination,
        String status,
        Long driverId,
        Long vehicleId,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        java.math.BigDecimal distanceKm,
        java.math.BigDecimal cost) {

    public static final RowMapper<Order> ROW_MAPPER = (rs, i) -> new Order(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("customer_name"),
            rs.getString("origin"),
            rs.getString("destination"),
            rs.getString("status"),
            rs.getLong("driver_id"),
            rs.getLong("vehicle_id"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getBigDecimal("distance_km"),
            rs.getBigDecimal("cost"));
}
