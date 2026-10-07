package com.example.tms.order;

import com.example.tms.NotFoundException;
import com.example.tms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 运输订单数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/status 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT o.id, o.order_no, o.customer_name, o.origin, o.destination, o.status,
                   o.driver_id, o.vehicle_id, o.remark, o.created_at, o.updated_at,
                   o.distance_km, o.cost
            FROM tms_orders o
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    /**
     * 运输订单查询。orderNos 为逗号分隔的 OMS 订单号（精确 IN 匹配）：
     * 三系统关联方案里 TMS 运单的 order_no 直接存 OMS 订单号，OMS 列表
     * 聚合运单状态就按它批量反查（与 keyword 模糊搜互补）。
     */
    public PageResult<Order> search(String keyword, String status, String orderNos, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.order_no LIKE :kw OR o.customer_name LIKE :kw OR o.origin LIKE :kw OR o.destination LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND o.status = :status ");
            params.addValue("status", status);
        }
        if (orderNos != null && !orderNos.isBlank()) {
            List<String> nos = java.util.Arrays.stream(orderNos.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(200)
                    .toList();
            if (!nos.isEmpty()) {
                where.append(" AND o.order_no IN (:orderNos) ");
                params.addValue("orderNos", nos);
            }
        }
        // 租户条件进公共 where：COUNT 与分页列表共用，两条查询各过滤一次
        where.append(" AND o.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM tms_orders o" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Order> list = named.query(
                BASE_SELECT + where + " ORDER BY o.id DESC LIMIT :limit OFFSET :offset",
                params, Order.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Order get(long id) {
        List<Order> rows = jdbc.query(
                BASE_SELECT + " WHERE o.id = ? AND o.tenant_id = ?", Order.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("运输订单不存在: " + id);
        }
        return rows.get(0);
    }

    public Order create(OrderPayload payload) {
        jdbc.update("""
                INSERT INTO tms_orders (tenant_id, order_no, customer_name, origin, destination, status,
                                         driver_id, vehicle_id, remark, distance_km, cost)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                payload.orderNo().trim(),
                payload.customerName().trim(),
                payload.origin().trim(),
                payload.destination().trim(),
                defaultIfBlank(payload.status(), "PENDING"),
                payload.driverId(),
                payload.vehicleId(),
                nullSafe(payload.remark()),
                payload.distanceKm(),
                payload.cost());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Order update(OrderPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少订单 id");
        }
        int updated = jdbc.update("""
                UPDATE tms_orders
                   SET order_no = ?, customer_name = ?, origin = ?, destination = ?,
                       status = ?, driver_id = ?, vehicle_id = ?, remark = ?,
                       distance_km = ?, cost = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.orderNo().trim(),
                payload.customerName().trim(),
                payload.origin().trim(),
                payload.destination().trim(),
                defaultIfBlank(payload.status(), "PENDING"),
                payload.driverId(),
                payload.vehicleId(),
                nullSafe(payload.remark()),
                payload.distanceKm(),
                payload.cost(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("运输订单不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM tms_orders WHERE id = ? AND tenant_id = ?", id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("运输订单不存在: " + id);
        }
    }

    /** 全部订单下拉选择用（只要 id + orderNo）。 */
    public List<Order> brief() {
        return jdbc.query(
                "SELECT o.id, o.order_no, o.customer_name, o.origin, o.destination, o.status, "
                        + "o.driver_id, o.vehicle_id, o.remark, o.created_at, o.updated_at, "
                        + "o.distance_km, o.cost "
                        + "FROM tms_orders o WHERE o.tenant_id = ? ORDER BY o.id DESC LIMIT 500",
                Order.ROW_MAPPER, TenantContext.get());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
