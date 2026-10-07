package com.example.crm.contact;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/** 联系人：某客户下的对接人。 */
public record Contact(
        Long id,
        Long customerId,
        String name,
        String position,
        String phone,
        String email,
        Integer isPrimary,
        LocalDateTime createdAt) {

    public static final RowMapper<Contact> ROW_MAPPER = (rs, i) -> new Contact(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getString("name"),
            rs.getString("position"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getInt("is_primary"),
            rs.getObject("created_at", LocalDateTime.class));
}
