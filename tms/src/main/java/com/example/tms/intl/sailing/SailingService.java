package com.example.tms.intl.sailing;

import com.example.tms.ConflictException;
import com.example.tms.NotFoundException;
import com.example.tms.intl.IntlPage;
import com.example.tms.intl.IntlFields;
import com.example.tms.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 船期数据访问。
 *
 * <p>筛选时间窗（etdFrom/etdTo）用 **UTC_DATE()** 而不是 CURDATE()：
 * 本库所有新表时间列存 UTC，用服务器本地时区算「今天出港」会差 8 小时。
 * 这个与存量代码（用 CURDATE()）故意不同，改动前先确认自己在动新表。</p>
 */
@Service
@RequiredArgsConstructor
public class SailingService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT s.id, s.route_id, r.route_code, r.pol_code, r.pod_code, s.vessel_name, s.voyage_no,
                   s.etd_at, s.eta_at, s.cutoff_at, s.free_time_days, s.space_left, s.status,
                   s.remark, s.created_at
            FROM tms_sailings s
            LEFT JOIN tms_routes r ON r.id = s.route_id
            """;

    public IntlPage<Sailing> search(Long routeId, String status, String etdFrom, String etdTo,
                                    int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND s.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (routeId != null) {
            where.append(" AND s.route_id = :routeId ");
            params.addValue("routeId", routeId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND s.status = :status ");
            params.addValue("status", status.trim());
        }
        if (etdFrom != null && !etdFrom.isBlank()) {
            where.append(" AND s.etd_at >= :etdFrom ");
            params.addValue("etdFrom", etdFrom.trim() + " 00:00:00");
        }
        if (etdTo != null && !etdTo.isBlank()) {
            where.append(" AND s.etd_at <= :etdTo ");
            params.addValue("etdTo", etdTo.trim() + " 23:59:59");
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM tms_sailings s" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Sailing> list = named.query(
                BASE_SELECT + where + " ORDER BY s.etd_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params, Sailing.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Sailing get(long id) {
        List<Sailing> rows = jdbc.query(
                BASE_SELECT + " WHERE s.id = ? AND s.tenant_id = ?", Sailing.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("船期不存在: " + id);
        }
        return rows.get(0);
    }

    /** 订舱表单的下拉：某条航线上还没截关的船期，按 ETD 升序。 */
    public List<Sailing> brief(Long routeId) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get());
        sql.append(" WHERE s.tenant_id = ?");
        if (routeId != null) {
            sql.append(" AND s.route_id = ?");
            args.add(routeId);
        }
        sql.append(" AND s.status IN ('SCHEDULED','BOOKING')");
        sql.append(" ORDER BY s.etd_at ASC LIMIT 500");
        return jdbc.query(sql.toString(), Sailing.ROW_MAPPER, args.toArray());
    }

    public Sailing create(SailingPayload payload) {
        named.update("""
                INSERT INTO tms_sailings
                    (tenant_id, route_id, vessel_name, voyage_no, etd_at, eta_at, cutoff_at, free_time_days, space_left, status, remark)
                VALUES (:tenant_id, :route_id, :vessel_name, :voyage_no, :etd_at, :eta_at, :cutoff_at, :free_time_days, :space_left, :status, :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("route_id", payload.routeId())
                        .addValue("vessel_name", IntlFields.text(payload.vesselName()))
                        .addValue("voyage_no", IntlFields.text(payload.voyageNo()))
                        .addValue("etd_at", payload.etdAt())
                        .addValue("eta_at", payload.etaAt())
                        .addValue("cutoff_at", payload.cutoffAt())
                        .addValue("free_time_days", IntlFields.zero(payload.freeTimeDays()))
                        .addValue("space_left", IntlFields.zero(payload.spaceLeft()))
                        .addValue("status", IntlFields.orDefault(payload.status(), "SCHEDULED"))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Sailing update(SailingPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少船期 id");
        }
        int updated = jdbc.update("""
                UPDATE tms_sailings
                   SET route_id = ?, vessel_name = ?, voyage_no = ?, etd_at = ?, eta_at = ?,
                       cutoff_at = ?, free_time_days = ?, space_left = ?, status = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.routeId(),
                IntlFields.text(payload.vesselName()),
                IntlFields.text(payload.voyageNo()),
                payload.etdAt(),
                payload.etaAt(),
                payload.cutoffAt(),
                IntlFields.zero(payload.freeTimeDays()),
                IntlFields.zero(payload.spaceLeft()),
                IntlFields.orDefault(payload.status(), "SCHEDULED"),
                IntlFields.text(payload.remark()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("船期不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        get(id);
        Integer inUse = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tms_shipments WHERE sailing_id = ? AND tenant_id = ?",
                Integer.class, id, TenantContext.get());
        if (inUse != null && inUse > 0) {
            throw new ConflictException("该船期已有运单占用，不能删除");
        }
        int deleted = jdbc.update("DELETE FROM tms_sailings WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("船期不存在: " + id);
        }
    }
}
