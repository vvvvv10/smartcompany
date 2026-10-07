package com.example.tms.intl.carrier;

import com.example.tms.ConflictException;
import com.example.tms.NotFoundException;
import com.example.tms.intl.IntlPage;
import com.example.tms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 承运商主数据访问。
 *
 * <p>所有查询按 tenant_id 行级隔离，且租户条件拼在**筛选之前**，
 * 这样 COUNT(*) 与分页列表两条 SQL 共用同一个 where，不会出现「总数算了全租户、
 * 列表只给了本租户」的错位（沿用 OrderService.search 的写法）。</p>
 *
 * <p>删除要反查引用：有航线或运单引用的承运商不能删，否则那些行的 carrier_id
 * 会指向空气。跨库引用（OMS 母单也存了 carrier_id）查不到，本服务只保自己的库。</p>
 */
@Service
@RequiredArgsConstructor
public class CarrierService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT id, code, name_zh, name_en, carrier_type, country, contact_name, contact_phone,
                   contact_email, service_level, transit_days, cut_off_hours, free_time_days,
                   status, remark, created_at, updated_at
            FROM tms_carriers
            """;

    public IntlPage<Carrier> search(String keyword, String carrierType, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code LIKE :kw OR name_zh LIKE :kw OR name_en LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (carrierType != null && !carrierType.isBlank()) {
            where.append(" AND carrier_type = :type ");
            params.addValue("type", carrierType.trim());
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM tms_carriers" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Carrier> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, Carrier.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Carrier get(long id) {
        List<Carrier> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", Carrier.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("承运商不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 下拉选择器用的轻量列表（≤500 行，只带列表要显示的字段）。
     * 运单/航线表单每次都要拉承运商，接口要轻，否则开一个编辑弹窗就是一次重量级响应。
     */
    public List<Carrier> brief(String carrierType) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new java.util.ArrayList<>();
        args.add(TenantContext.get());
        sql.append(" WHERE tenant_id = ?");
        if (carrierType != null && !carrierType.isBlank()) {
            sql.append(" AND carrier_type = ?");
            args.add(carrierType.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        return jdbc.query(sql.toString(), Carrier.ROW_MAPPER, args.toArray());
    }

    public Carrier create(CarrierPayload payload) {
        named.update("""
                INSERT INTO tms_carriers
                    (tenant_id, code, name_zh, name_en, carrier_type, country, contact_name, contact_phone, contact_email, service_level, transit_days, cut_off_hours, free_time_days, status, remark)
                VALUES (:tenant_id, :code, :name_zh, :name_en, :carrier_type, :country, :contact_name, :contact_phone, :contact_email, :service_level, :transit_days, :cut_off_hours, :free_time_days, :status, :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("code", payload.code().trim())
                        .addValue("name_zh", payload.nameZh().trim())
                        .addValue("name_en", nullSafe(payload.nameEn()))
                        .addValue("carrier_type", defaultIfBlank(payload.carrierType(), "OCEAN"))
                        .addValue("country", nullSafe(payload.country()))
                        .addValue("contact_name", nullSafe(payload.contactName()))
                        .addValue("contact_phone", nullSafe(payload.contactPhone()))
                        .addValue("contact_email", nullSafe(payload.contactEmail()))
                        .addValue("service_level", defaultIfBlank(payload.serviceLevel(), "STANDARD"))
                        .addValue("transit_days", orZero(payload.transitDays()))
                        .addValue("cut_off_hours", orZero(payload.cutOffHours()))
                        .addValue("free_time_days", orZero(payload.freeTimeDays()))
                        .addValue("status", defaultIfBlank(payload.status(), "ACTIVE"))
                        .addValue("remark", nullSafe(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Carrier update(CarrierPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少承运商 id");
        }
        int updated = jdbc.update("""
                UPDATE tms_carriers
                   SET code = ?, name_zh = ?, name_en = ?, carrier_type = ?, country = ?,
                       contact_name = ?, contact_phone = ?, contact_email = ?, service_level = ?,
                       transit_days = ?, cut_off_hours = ?, free_time_days = ?, status = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.code().trim(),
                payload.nameZh().trim(),
                nullSafe(payload.nameEn()),
                defaultIfBlank(payload.carrierType(), "OCEAN"),
                nullSafe(payload.country()),
                nullSafe(payload.contactName()),
                nullSafe(payload.contactPhone()),
                nullSafe(payload.contactEmail()),
                defaultIfBlank(payload.serviceLevel(), "STANDARD"),
                orZero(payload.transitDays()),
                orZero(payload.cutOffHours()),
                orZero(payload.freeTimeDays()),
                defaultIfBlank(payload.status(), "ACTIVE"),
                nullSafe(payload.remark()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("承运商不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        get(id); // 先过租户闸：跨租户按 id 猜测删会在这里抛 NotFound
        Integer inUse = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM tms_routes WHERE carrier_id = ? AND tenant_id = ?)
                     + (SELECT COUNT(*) FROM tms_shipments WHERE carrier_id = ? AND tenant_id = ?)
                """, Integer.class, id, TenantContext.get(), id, TenantContext.get());
        if (inUse != null && inUse > 0) {
            throw new ConflictException("该承运商还有 " + inUse + " 条航线/运单在用，不能删除；可改为「停用」");
        }
        int deleted = jdbc.update("DELETE FROM tms_carriers WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("承运商不存在: " + id);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
