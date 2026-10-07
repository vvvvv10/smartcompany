package com.example.tms.driver;

import com.example.tms.NotFoundException;
import com.example.tms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 司机数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/status 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class DriverService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT d.id, d.name, d.phone, d.status, d.remark, d.created_at, d.updated_at
            FROM tms_drivers d
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Driver> search(String keyword, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (d.name LIKE :kw OR d.phone LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND d.status = :status ");
            params.addValue("status", status);
        }
        // 租户条件进公共 where：COUNT 与分页列表共用，两条查询各过滤一次
        where.append(" AND d.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM tms_drivers d" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Driver> list = named.query(
                BASE_SELECT + where + " ORDER BY d.id DESC LIMIT :limit OFFSET :offset",
                params, Driver.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Driver get(long id) {
        List<Driver> rows = jdbc.query(
                BASE_SELECT + " WHERE d.id = ? AND d.tenant_id = ?", Driver.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("司机不存在: " + id);
        }
        return rows.get(0);
    }

    public Driver create(DriverPayload payload) {
        jdbc.update("""
                INSERT INTO tms_drivers (tenant_id, name, phone, status, remark)
                VALUES (?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                payload.name().trim(),
                nullSafe(payload.phone()),
                defaultIfBlank(payload.status(), "ACTIVE"),
                nullSafe(payload.remark()));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Driver update(DriverPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少司机 id");
        }
        int updated = jdbc.update("""
                UPDATE tms_drivers
                   SET name = ?, phone = ?, status = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.name().trim(),
                nullSafe(payload.phone()),
                defaultIfBlank(payload.status(), "ACTIVE"),
                nullSafe(payload.remark()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("司机不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM tms_drivers WHERE id = ? AND tenant_id = ?", id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("司机不存在: " + id);
        }
    }

    /** 全部司机下拉选择用（只要 id + name）。 */
    public List<Driver> brief() {
        return jdbc.query(
                "SELECT d.id, d.name, d.phone, d.status, d.remark, d.created_at, d.updated_at "
                        + "FROM tms_drivers d WHERE d.tenant_id = ? ORDER BY d.id DESC LIMIT 500",
                Driver.ROW_MAPPER, TenantContext.get());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
