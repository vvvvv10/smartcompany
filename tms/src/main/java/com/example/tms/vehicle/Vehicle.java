package com.example.tms.vehicle;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 车辆。
 *
 * <p>status: IDLE 空闲 / BUSY 运输中 / MAINTENANCE 维修中</p>
 */
public record Vehicle(
        Long id,
        String plateNo,
        String type,
        String status,
        String driverName,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        java.math.BigDecimal costPerKm,
        java.math.BigDecimal dailyFixedCost,
        Boolean isExternal) {

    public static final RowMapper<Vehicle> ROW_MAPPER = (rs, i) -> new Vehicle(
            rs.getLong("id"),
            rs.getString("plate_no"),
            rs.getString("type"),
            rs.getString("status"),
            rs.getString("driver_name"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getBigDecimal("cost_per_km"),
            rs.getBigDecimal("daily_fixed_cost"),
            rs.getBoolean("is_external"));
}
