package com.example.wms.warehouse;

import com.example.wms.NotFoundException;
import com.example.wms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 仓库数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/status 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT id, name, location, capacity, status, remark, created_at, updated_at,
                   warehouse_type, country, timezone, oversea_operator, is_bonded
            FROM wms_warehouses
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Warehouse> search(String keyword, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (name LIKE :kw OR location LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status);
        }

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM wms_warehouses" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Warehouse> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, Warehouse.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Warehouse get(long id) {
        List<Warehouse> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", Warehouse.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("仓库不存在: " + id);
        }
        return rows.get(0);
    }

    public Warehouse create(WarehousePayload payload) {
        named.update("""
                INSERT INTO wms_warehouses
                    (tenant_id, name, location, capacity, status, remark, warehouse_type, country, timezone, oversea_operator, is_bonded)
                VALUES (:tenant_id, :name, :location, :capacity, :status, :remark, :warehouse_type, :country, :timezone, :oversea_operator, :is_bonded)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("name", payload.name().trim())
                        .addValue("location", nullSafe(payload.location()))
                        .addValue("capacity", payload.capacity())
                        .addValue("status", defaultIfBlank(payload.status(), "ACTIVE"))
                        .addValue("remark", nullSafe(payload.remark()))
                        .addValue("warehouse_type", defaultIfBlank(payload.warehouseType(), "DOMESTIC"))
                        .addValue("country", defaultIfBlank(payload.country(), "CN"))
                        .addValue("timezone", defaultIfBlank(payload.timezone(), "Asia/Shanghai"))
                        .addValue("oversea_operator", nullSafe(payload.overseaOperator()))
                        .addValue("is_bonded", payload.isBonded() == null ? 0 : payload.isBonded())
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Warehouse update(WarehousePayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少仓库 id");
        }
        int updated = jdbc.update("""
                UPDATE wms_warehouses
                   SET name = ?, location = ?, capacity = ?, status = ?, remark = ?,
                       warehouse_type = ?, country = ?, timezone = ?, oversea_operator = ?, is_bonded = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.name().trim(),
                nullSafe(payload.location()),
                payload.capacity(),
                defaultIfBlank(payload.status(), "ACTIVE"),
                nullSafe(payload.remark()),
                defaultIfBlank(payload.warehouseType(), "DOMESTIC"),
                defaultIfBlank(payload.country(), "CN"),
                defaultIfBlank(payload.timezone(), "Asia/Shanghai"),
                nullSafe(payload.overseaOperator()),
                payload.isBonded() == null ? 0 : payload.isBonded(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("仓库不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM wms_warehouses WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("仓库不存在: " + id);
        }
    }

    /** 全部仓库下拉选择用（只要 id + name）。 */
    public List<Warehouse> brief() {
        return jdbc.query(
                "SELECT id, name, location, capacity, status, remark, created_at, updated_at, "
                        + "warehouse_type, country, timezone, oversea_operator, is_bonded "
                        + "FROM wms_warehouses WHERE tenant_id = ? ORDER BY id DESC LIMIT 500",
                Warehouse.ROW_MAPPER, TenantContext.get());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
