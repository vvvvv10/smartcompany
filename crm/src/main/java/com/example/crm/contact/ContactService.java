package com.example.crm.contact;

import com.example.crm.NotFoundException;
import com.example.crm.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 联系人数据访问。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class ContactService {

    private final JdbcTemplate jdbc;

    public List<Contact> listByCustomer(long customerId) {
        return jdbc.query(
                "SELECT id, customer_id, name, position, phone, email, is_primary, created_at "
                        + "FROM contacts WHERE customer_id = ? AND tenant_id = ? "
                        + "ORDER BY is_primary DESC, id ASC",
                Contact.ROW_MAPPER, customerId, TenantContext.get());
    }

    public Contact create(long customerId, ContactPayload payload, long ownerId, String ownerName) {
        jdbc.update("""
                INSERT INTO contacts (tenant_id, customer_id, name, position, phone, email, is_primary, owner_id, owner_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                customerId,
                payload.name().trim(),
                safe(payload.position()),
                safe(payload.phone()),
                safe(payload.email()),
                payload.isPrimary() != null && payload.isPrimary() ? 1 : 0,
                ownerId,
                ownerName);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Contact update(long id, ContactPayload payload) {
        int updated = jdbc.update("""
                UPDATE contacts SET name = ?, position = ?, phone = ?, email = ?, is_primary = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.name().trim(),
                safe(payload.position()),
                safe(payload.phone()),
                safe(payload.email()),
                payload.isPrimary() != null && payload.isPrimary() ? 1 : 0,
                id,
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("联系人不存在: " + id);
        }
        return get(id);
    }

    public void delete(long id) {
        if (jdbc.update("DELETE FROM contacts WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get()) == 0) {
            throw new NotFoundException("联系人不存在: " + id);
        }
    }

    private Contact get(long id) {
        List<Contact> rows = jdbc.query(
                "SELECT id, customer_id, name, position, phone, email, is_primary, created_at "
                        + "FROM contacts WHERE id = ? AND tenant_id = ?",
                Contact.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("联系人不存在: " + id);
        }
        return rows.get(0);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
