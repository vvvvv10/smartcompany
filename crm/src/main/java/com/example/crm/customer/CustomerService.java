package com.example.crm.customer;

import com.example.crm.NotFoundException;
import com.example.crm.intl.IntlFields;
import com.example.crm.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 客户数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/status/level 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT c.id, c.name, c.industry, c.level, c.source, c.status,
                   c.phone, c.email, c.address, c.remark, c.owner_id, c.owner_name,
                   c.created_at, c.updated_at,
                   (SELECT COUNT(*) FROM contacts t WHERE t.customer_id = c.id) AS contact_count,
                   (SELECT COUNT(*) FROM opportunities o WHERE o.customer_id = c.id) AS opportunity_count,
                   c.customer_type, c.country, c.tax_no, c.payment_term_days,
                   c.credit_limit, c.credit_used, c.default_currency, c.ioss_no
            FROM customers c
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Customer> search(String keyword, String status, String level, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (c.name LIKE :kw OR c.phone LIKE :kw OR c.industry LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = :status ");
            params.addValue("status", status);
        }
        if (level != null && !level.isBlank()) {
            where.append(" AND c.level = :level ");
            params.addValue("level", level);
        }
        where.append(" AND c.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM customers c" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Customer> list = named.query(
                BASE_SELECT + where + " ORDER BY c.id DESC LIMIT :limit OFFSET :offset",
                params, Customer.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Customer get(long id) {
        List<Customer> rows = jdbc.query(
                BASE_SELECT + " WHERE c.id = ? AND c.tenant_id = ?", Customer.ROW_MAPPER,
                id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("客户不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 创建客户。归属人取网关注入的 X-User-Id（本服务不做角色限制，任何登录用户可读写）。
     */
    public Customer create(CustomerPayload payload, long ownerId, String ownerName) {
        named.update("""
                INSERT INTO customers
                    (tenant_id, name, industry, level, source, status, phone, email, address, remark, owner_id, owner_name, customer_type, country, tax_no, payment_term_days, credit_limit, credit_used, default_currency, ioss_no)
                VALUES (:tenant_id, :name, :industry, :level, :source, :status, :phone, :email, :address, :remark, :owner_id, :owner_name, :customer_type, :country, :tax_no, :payment_term_days, :credit_limit, :credit_used, :default_currency, :ioss_no)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("name", payload.name().trim())
                        .addValue("industry", nullSafe(payload.industry()))
                        .addValue("level", defaultIfBlank(payload.level(), "NORMAL"))
                        .addValue("source", defaultIfBlank(payload.source(), "其他"))
                        .addValue("status", defaultIfBlank(payload.status(), "POTENTIAL"))
                        .addValue("phone", nullSafe(payload.phone()))
                        .addValue("email", nullSafe(payload.email()))
                        .addValue("address", nullSafe(payload.address()))
                        .addValue("remark", nullSafe(payload.remark()))
                        .addValue("owner_id", ownerId)
                        .addValue("owner_name", ownerName)
                        .addValue("customer_type", defaultIfBlank(payload.customerType(), "SELLER"))
                        .addValue("country", defaultIfBlank(payload.country(), "CN"))
                        .addValue("tax_no", nullSafe(payload.taxNo()))
                        .addValue("payment_term_days", payload.paymentTermDays() == null ? 0 : payload.paymentTermDays())
                        .addValue("credit_limit", IntlFields.decimal(payload.creditLimit()))
                        .addValue("credit_used", IntlFields.decimal(payload.creditUsed()))
                        .addValue("default_currency", defaultIfBlank(payload.defaultCurrency(), "USD"))
                        .addValue("ioss_no", nullSafe(payload.iossNo()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Customer update(CustomerPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少客户 id");
        }
        int updated = jdbc.update("""
                UPDATE customers
                   SET name = ?, industry = ?, level = ?, source = ?, status = ?,
                       phone = ?, email = ?, address = ?, remark = ?,
                       customer_type = ?, country = ?, tax_no = ?, payment_term_days = ?,
                       credit_limit = ?, credit_used = ?, default_currency = ?, ioss_no = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.name().trim(),
                nullSafe(payload.industry()),
                defaultIfBlank(payload.level(), "NORMAL"),
                defaultIfBlank(payload.source(), "其他"),
                defaultIfBlank(payload.status(), "POTENTIAL"),
                nullSafe(payload.phone()),
                nullSafe(payload.email()),
                nullSafe(payload.address()),
                nullSafe(payload.remark()),
                defaultIfBlank(payload.customerType(), "SELLER"),
                defaultIfBlank(payload.country(), "CN"),
                nullSafe(payload.taxNo()),
                payload.paymentTermDays() == null ? 0 : payload.paymentTermDays(),
                payload.creditLimit() == null ? java.math.BigDecimal.ZERO : payload.creditLimit(),
                payload.creditUsed() == null ? java.math.BigDecimal.ZERO : payload.creditUsed(),
                defaultIfBlank(payload.defaultCurrency(), "USD"),
                nullSafe(payload.iossNo()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("客户不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM customers WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("客户不存在: " + id);
        }
        // 级联清理子表，避免留下孤儿记录（父行已过租户闸，子行同租户才可能存在）
        jdbc.update("DELETE FROM contacts WHERE customer_id = ? AND tenant_id = ?",
                id, TenantContext.get());
        jdbc.update("DELETE FROM opportunities WHERE customer_id = ? AND tenant_id = ?",
                id, TenantContext.get());
        jdbc.update("DELETE FROM follow_ups WHERE customer_id = ? AND tenant_id = ?",
                id, TenantContext.get());
    }

    /** 全部客户下拉选择用（只要 id + name）。 */
    public List<Customer> brief() {
        return jdbc.query(
                "SELECT c.id, c.name, c.industry, c.level, c.source, c.status, c.phone, c.email, "
                        + "c.address, c.remark, c.owner_id, c.owner_name, c.created_at, c.updated_at, "
                        + "0 AS contact_count, 0 AS opportunity_count, "
                        + "c.customer_type, c.country, c.tax_no, c.payment_term_days, "
                        + "c.credit_limit, c.credit_used, c.default_currency, c.ioss_no "
                        + "FROM customers c WHERE c.tenant_id = ? ORDER BY c.id DESC LIMIT 500",
                Customer.ROW_MAPPER, TenantContext.get());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
