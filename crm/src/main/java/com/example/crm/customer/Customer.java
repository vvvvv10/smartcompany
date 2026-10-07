package com.example.crm.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 客户。
 *
 * <p>status: POTENTIAL 潜在 / FOLLOWING 跟进中 / DEAL 成交 / LOST 流失
 * level:   KEY 重点 / NORMAL 普通 / LOW 低优先级</p>
 *
 * <p>末尾 8 个字段是 46 号迁移加的国际属性（客户类型/国家/税号/账期/授信/默认币种/IOSS）。
 * 它们默认给存量客户兜底值（SELLER/CN/账期 0/额度 0/USD），
 * 所以 22 号迁移那 5 条国内客户在页面上照样能正常显示，只是这几列都是「—」。</p>
 */
public record Customer(
        Long id,
        String name,
        String industry,
        String level,
        String source,
        String status,
        String phone,
        String email,
        String address,
        String remark,
        Long ownerId,
        String ownerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long contactCount,
        Long opportunityCount,
        String customerType,
        String country,
        String taxNo,
        Integer paymentTermDays,
        BigDecimal creditLimit,
        BigDecimal creditUsed,
        String defaultCurrency,
        String iossNo) {

    public static final RowMapper<Customer> ROW_MAPPER = (rs, i) -> new Customer(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("industry"),
            rs.getString("level"),
            rs.getString("source"),
            rs.getString("status"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getString("address"),
            rs.getString("remark"),
            rs.getLong("owner_id"),
            rs.getString("owner_name"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getLong("contact_count"),
            rs.getLong("opportunity_count"),
            rs.getString("customer_type"),
            rs.getString("country"),
            rs.getString("tax_no"),
            rs.getObject("payment_term_days", Integer.class),
            rs.getBigDecimal("credit_limit"),
            rs.getBigDecimal("credit_used"),
            rs.getString("default_currency"),
            rs.getString("ioss_no"));
}
