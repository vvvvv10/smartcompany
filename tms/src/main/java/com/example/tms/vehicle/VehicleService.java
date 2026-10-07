package com.example.tms.vehicle;

import com.example.tms.NotFoundException;
import com.example.tms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 车辆数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/status 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT v.id, v.plate_no, v.type, v.status, v.driver_name, v.remark,
                   v.created_at, v.updated_at,
                   v.cost_per_km, v.daily_fixed_cost, v.is_external
            FROM tms_vehicles v
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Vehicle> search(String keyword, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (v.plate_no LIKE :kw OR v.driver_name LIKE :kw OR v.type LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND v.status = :status ");
            params.addValue("status", status);
        }
        // 租户条件进公共 where：COUNT 与分页列表共用，两条查询各过滤一次
        where.append(" AND v.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM tms_vehicles v" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Vehicle> list = named.query(
                BASE_SELECT + where + " ORDER BY v.id DESC LIMIT :limit OFFSET :offset",
                params, Vehicle.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Vehicle get(long id) {
        List<Vehicle> rows = jdbc.query(
                BASE_SELECT + " WHERE v.id = ? AND v.tenant_id = ?", Vehicle.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("车辆不存在: " + id);
        }
        return rows.get(0);
    }

    public Vehicle create(VehiclePayload payload) {
        jdbc.update("""
                INSERT INTO tms_vehicles (tenant_id, plate_no, type, status, driver_name, remark,
                                          cost_per_km, daily_fixed_cost, is_external)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                payload.plateNo().trim(),
                nullSafe(payload.type()),
                defaultIfBlank(payload.status(), "IDLE"),
                nullSafe(payload.driverName()),
                nullSafe(payload.remark()),
                payload.costPerKm(),
                payload.dailyFixedCost(),
                payload.isExternal() != null && payload.isExternal());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Vehicle update(VehiclePayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少车辆 id");
        }
        int updated = jdbc.update("""
                UPDATE tms_vehicles
                   SET plate_no = ?, type = ?, status = ?, driver_name = ?, remark = ?,
                       cost_per_km = ?, daily_fixed_cost = ?, is_external = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.plateNo().trim(),
                nullSafe(payload.type()),
                defaultIfBlank(payload.status(), "IDLE"),
                nullSafe(payload.driverName()),
                nullSafe(payload.remark()),
                payload.costPerKm(),
                payload.dailyFixedCost(),
                payload.isExternal() != null && payload.isExternal(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("车辆不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM tms_vehicles WHERE id = ? AND tenant_id = ?", id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("车辆不存在: " + id);
        }
    }

    /** 全部车辆下拉选择用（只要 id + plateNo）。 */
    public List<Vehicle> brief() {
        return jdbc.query(
                "SELECT v.id, v.plate_no, v.type, v.status, v.driver_name, v.remark, "
                        + "v.created_at, v.updated_at, "
                        + "v.cost_per_km, v.daily_fixed_cost, v.is_external "
                        + "FROM tms_vehicles v WHERE v.tenant_id = ? ORDER BY v.id DESC LIMIT 500",
                Vehicle.ROW_MAPPER, TenantContext.get());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
