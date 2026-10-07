package com.example.crm.followup;

import com.example.crm.NotFoundException;
import com.example.crm.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 跟进记录数据访问。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class FollowUpService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT f.id, f.customer_id, c.name AS customer_name, f.type, f.content,
                   f.next_follow_at, f.creator_id, f.creator_name, f.created_at
              FROM follow_ups f
              LEFT JOIN customers c ON c.id = f.customer_id
            """;

    /** 按客户查跟进时间线，倒序。customerId 可为 null 表示查全部。 */
    public List<FollowUp> list(Long customerId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeLimit)
                .addValue("tenant", TenantContext.get());
        StringBuilder where = new StringBuilder(" WHERE f.tenant_id = :tenant");
        if (customerId != null) {
            where.append(" AND f.customer_id = :customerId");
            params.addValue("customerId", customerId);
        }
        return named.query(
                BASE_SELECT + where + " ORDER BY f.id DESC LIMIT :limit",
                params, FollowUp.ROW_MAPPER);
    }

    public FollowUp create(long customerId, FollowUpPayload payload, long creatorId, String creatorName) {
        jdbc.update("""
                INSERT INTO follow_ups (tenant_id, customer_id, type, content, next_follow_at, creator_id, creator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                customerId,
                typeOrDefault(payload.type()),
                payload.content().trim(),
                parseTime(payload.nextFollowAt()),
                creatorId,
                creatorName);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public void delete(long id) {
        if (jdbc.update("DELETE FROM follow_ups WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get()) == 0) {
            throw new NotFoundException("跟进记录不存在: " + id);
        }
    }

    private FollowUp get(long id) {
        List<FollowUp> rows = jdbc.query(
                BASE_SELECT + " WHERE f.id = ? AND f.tenant_id = ?", FollowUp.ROW_MAPPER,
                id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("跟进记录不存在: " + id);
        }
        return rows.get(0);
    }

    private static String typeOrDefault(String type) {
        if (type == null || type.isBlank()) {
            return "OTHER";
        }
        return type.trim().toUpperCase();
    }

    /**
     * 兼容 "yyyy-MM-dd HH:mm"、"yyyy-MM-ddTHH:mm"、"yyyy-MM-ddTHH:mm:ss"、"yyyy-MM-dd"
     * 四种前端常见格式（T 与空格等价）。
     * 空串返回 null（暂不安排下次跟进）。
     */
    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().replace(' ', 'T');
        try {
            if (v.length() == 10) {
                return LocalDateTime.parse(v + "T00:00:00");
            }
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("时间格式不正确: " + value);
        }
    }
}
