package com.example.tms.intl.route;

import com.example.tms.ConflictException;
import com.example.tms.NotFoundException;
import com.example.tms.intl.IntlFields;
import com.example.tms.intl.IntlPage;
import com.example.tms.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 航线主数据访问。
 *
 * <p>carrierName 是 LEFT JOIN 出来的展示列：承运商被删/跨租户不可见时它为 null，
 * 前端显示「—」而**不抛异常**——航线弱引用的 carrier_id 本来就可能悬空，
 * 因为承运商是别的库/别的租户在维护的事（见计划 §3.7 跨库弱引用三条硬规则）。</p>
 */
@Service
@RequiredArgsConstructor
public class RouteService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT r.id, r.route_code, r.carrier_id, c.name_zh AS carrier_name, r.mode,
                   r.pol_code, r.pod_code, r.transit_days, r.volumetric_divisor, r.via_ports,
                   r.status, r.remark, r.created_at, r.updated_at
            FROM tms_routes r
            LEFT JOIN tms_carriers c ON c.id = r.carrier_id
            """;

    public IntlPage<Route> search(String keyword, String mode, String polCode, String podCode,
                                  int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND r.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (r.route_code LIKE :kw OR r.via_ports LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (mode != null && !mode.isBlank()) {
            where.append(" AND r.mode = :mode ");
            params.addValue("mode", mode.trim());
        }
        if (polCode != null && !polCode.isBlank()) {
            where.append(" AND r.pol_code = :pol ");
            params.addValue("pol", polCode.trim());
        }
        if (podCode != null && !podCode.isBlank()) {
            where.append(" AND r.pod_code = :pod ");
            params.addValue("pod", podCode.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM tms_routes r" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Route> list = named.query(
                BASE_SELECT + where + " ORDER BY r.id DESC LIMIT :limit OFFSET :offset",
                params, Route.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Route get(long id) {
        List<Route> rows = jdbc.query(
                BASE_SELECT + " WHERE r.id = ? AND r.tenant_id = ?", Route.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("航线不存在: " + id);
        }
        return rows.get(0);
    }

    /** 下拉选择器（航线表单选完就知道起运港/目的港与时效）。 */
    public List<Route> brief(String mode) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get());
        sql.append(" WHERE r.tenant_id = ?");
        if (mode != null && !mode.isBlank()) {
            sql.append(" AND r.mode = ?");
            args.add(mode.trim());
        }
        sql.append(" ORDER BY r.id DESC LIMIT 500");
        return jdbc.query(sql.toString(), Route.ROW_MAPPER, args.toArray());
    }

    public Route create(RoutePayload payload) {
        named.update("""
                INSERT INTO tms_routes
                    (tenant_id, route_code, carrier_id, mode, pol_code, pod_code, transit_days, volumetric_divisor, via_ports, status, remark)
                VALUES (:tenant_id, :route_code, :carrier_id, :mode, :pol_code, :pod_code, :transit_days, :volumetric_divisor, :via_ports, :status, :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("route_code", payload.routeCode().trim())
                        .addValue("carrier_id", payload.carrierId())
                        .addValue("mode", IntlFields.orDefault(payload.mode(), "SEA"))
                        .addValue("pol_code", payload.polCode().trim())
                        .addValue("pod_code", payload.podCode().trim())
                        .addValue("transit_days", IntlFields.zero(payload.transitDays()))
                        .addValue("volumetric_divisor", IntlFields.zero(payload.volumetricDivisor()) == 0 ? 6000 : payload.volumetricDivisor())
                        .addValue("via_ports", IntlFields.text(payload.viaPorts()))
                        .addValue("status", IntlFields.orDefault(payload.status(), "ACTIVE"))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Route update(RoutePayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少航线 id");
        }
        int updated = jdbc.update("""
                UPDATE tms_routes
                   SET route_code = ?, carrier_id = ?, mode = ?, pol_code = ?, pod_code = ?,
                       transit_days = ?, volumetric_divisor = ?, via_ports = ?, status = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.routeCode().trim(),
                payload.carrierId(),
                IntlFields.orDefault(payload.mode(), "SEA"),
                payload.polCode().trim(),
                payload.podCode().trim(),
                IntlFields.zero(payload.transitDays()),
                IntlFields.zero(payload.volumetricDivisor()) == 0 ? 6000 : payload.volumetricDivisor(),
                IntlFields.text(payload.viaPorts()),
                IntlFields.orDefault(payload.status(), "ACTIVE"),
                IntlFields.text(payload.remark()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("航线不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        get(id);
        Integer inUse = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM tms_sailings WHERE route_id = ? AND tenant_id = ?)
                     + (SELECT COUNT(*) FROM tms_shipments WHERE pol_code = (
                            SELECT pol_code FROM tms_routes WHERE id = ? AND tenant_id = ?)
                            AND pod_code = (SELECT pod_code FROM tms_routes WHERE id = ? AND tenant_id = ?)
                            AND tenant_id = ?)
                """, Integer.class, id, TenantContext.get(), id, TenantContext.get(), id, TenantContext.get(),
                TenantContext.get());
        if (inUse != null && inUse > 0) {
            throw new ConflictException("该航线还有 " + inUse + " 条船期/运单在用，不能删除；可改为「停用」");
        }
        int deleted = jdbc.update("DELETE FROM tms_routes WHERE id = ? AND tenant_id = ?", id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("航线不存在: " + id);
        }
    }
}
