package com.example.crm.opportunity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 商机。
 *
 * <p>stage: LEAD 线索 / PROPOSAL 方案 / NEGOTIATION 谈判 / WON 赢单 / LOST 输单</p>
 */
public record Opportunity(
        Long id,
        Long customerId,
        String customerName,
        String name,
        BigDecimal amount,
        String stage,
        Integer probability,
        LocalDate expectedCloseDate,
        String remark,
        Long ownerId,
        String ownerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<Opportunity> ROW_MAPPER = (rs, i) -> new Opportunity(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getString("customer_name"),
            rs.getString("name"),
            rs.getBigDecimal("amount"),
            rs.getString("stage"),
            rs.getInt("probability"),
            rs.getDate("expected_close_date") == null ? null : rs.getDate("expected_close_date").toLocalDate(),
            rs.getString("remark"),
            rs.getLong("owner_id"),
            rs.getString("owner_name"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
