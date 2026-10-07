package com.example.tms.driver;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 司机。
 *
 * <p>status: ACTIVE 在职 / INACTIVE 停用</p>
 */
public record Driver(
        Long id,
        String name,
        String phone,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<Driver> ROW_MAPPER = (rs, i) -> new Driver(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("phone"),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
