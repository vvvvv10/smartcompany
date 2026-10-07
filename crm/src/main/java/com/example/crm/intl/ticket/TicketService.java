package com.example.crm.intl.ticket;

import com.example.crm.intl.IntlNumberGenerator;
import com.example.crm.ConflictException;
import com.example.crm.NotFoundException;
import com.example.crm.intl.IntlFields;
import com.example.crm.intl.IntlPage;
import com.example.crm.intl.IntlStateMachine;
import com.example.crm.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出口异常工单数据访问。
 *
 * <p><b>「我的工单」按 owner_id 过滤</b>（沿用 {@code WorkbenchService} 的个人工作台口径）：
 * 工单有 SLA，不按人过滤就等于没有 SLA——一个所有人都看得到但没人负责的列表。
 * 列表默认不过滤 ownerId（页面要看全量），「我的」视图由前端传 ownerId 显式指定。</p>
 *
 * <p>overdue 不是表列，是 SELECT 里现算的
 * {@code (due_at < UTC_DATE() AND status NOT IN ('RESOLVED','CLOSED'))}。</p>
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT id, ticket_no, customer_id, customer_name, order_no, shipment_no, category,
                   severity, status, title, detail, resolution, owner_id, owner_name, due_at,
                   (due_at IS NOT NULL AND due_at < UTC_DATE() AND status NOT IN ('RESOLVED','CLOSED'))
                       AS overdue,
                   resolved_at, created_at, updated_at
            FROM crm_tickets
            """;

    public IntlPage<Ticket> search(String keyword, String status, String category, String severity,
                                   Long customerId, String orderNos, String shipmentNos, Long ownerId,
                                   int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (ticket_no LIKE :kw OR title LIKE :kw OR customer_name LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = :category ");
            params.addValue("category", category.trim());
        }
        if (severity != null && !severity.isBlank()) {
            where.append(" AND severity = :severity ");
            params.addValue("severity", severity.trim());
        }
        if (customerId != null) {
            where.append(" AND customer_id = :customerId ");
            params.addValue("customerId", customerId);
        }
        if (ownerId != null) {
            where.append(" AND owner_id = :ownerId ");
            params.addValue("ownerId", ownerId);
        }
        appendNos(where, params, "order_no", "orderNos", orderNos);
        appendNos(where, params, "shipment_no", "shipmentNos", shipmentNos);

        Long total = named.queryForObject("SELECT COUNT(*) FROM crm_tickets" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Ticket> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, Ticket.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Ticket get(long id) {
        List<Ticket> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", Ticket.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("异常工单不存在: " + id);
        }
        return rows.get(0);
    }

    public Ticket create(TicketPayload payload) {
        long ownerId = payload.ownerId() == null
                ? com.example.crm.intl.IntlCurrentUser.id() : payload.ownerId();
        String ownerName = IntlFields.text(payload.ownerName());
        if (ownerName.isEmpty() && payload.ownerId() == null) {
            ownerName = "系统";
        }
        named.update("""
                INSERT INTO crm_tickets
                    (tenant_id, ticket_no, customer_id, customer_name, order_no, shipment_no, category, severity, status, title, detail, owner_id, owner_name, due_at)
                VALUES (:tenant_id, :ticket_no, :customer_id, :customer_name, :order_no, :shipment_no, :category, :severity, 'OPEN', :title, :detail, :owner_id, :owner_name, :due_at)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("ticket_no", generateTicketNo())
                        .addValue("customer_id", payload.customerId() == null ? 0L : payload.customerId())
                        .addValue("customer_name", IntlFields.text(payload.customerName()))
                        .addValue("order_no", IntlFields.text(payload.orderNo()))
                        .addValue("shipment_no", IntlFields.text(payload.shipmentNo()))
                        .addValue("category", IntlFields.orDefault(payload.category(), "DELAY"))
                        .addValue("severity", IntlFields.orDefault(payload.severity(), "MEDIUM"))
                        .addValue("title", payload.title().trim())
                        .addValue("detail", IntlFields.text(payload.detail()))
                        .addValue("owner_id", ownerId)
                        .addValue("owner_name", ownerName)
                        .addValue("due_at", payload.dueAt())
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Ticket update(TicketPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少工单 id");
        }
        Ticket existing = get(payload.id());
        if ("CLOSED".equals(existing.status())) {
            throw new ConflictException("工单已关闭，不能再改");
        }
        int updated = jdbc.update("""
                UPDATE crm_tickets
                   SET order_no = ?, shipment_no = ?, customer_id = ?, customer_name = ?,
                       category = ?, severity = ?, title = ?, detail = ?,
                       owner_id = ?, owner_name = ?, due_at = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                IntlFields.text(payload.orderNo()),
                IntlFields.text(payload.shipmentNo()),
                payload.customerId() == null ? existing.customerId() : payload.customerId(),
                payload.customerName() == null ? existing.customerName() : payload.customerName(),
                IntlFields.orDefault(payload.category(), existing.category()),
                IntlFields.orDefault(payload.severity(), existing.severity()),
                payload.title().trim(),
                payload.detail() == null ? existing.detail() : payload.detail(),
                payload.ownerId() == null ? existing.ownerId() : payload.ownerId(),
                payload.ownerName() == null ? existing.ownerName() : payload.ownerName(),
                payload.dueAt() == null ? existing.dueAt() : payload.dueAt(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("异常工单不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        Ticket existing = get(id);
        if ("OPEN".equals(existing.status()) || "FOLLOWING".equals(existing.status())) {
            throw new ConflictException("未处理的工单不能删，请先解决并关闭");
        }
        int deleted = jdbc.update("DELETE FROM crm_tickets WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("异常工单不存在: " + id);
        }
    }

    /** 推进工单状态：OPEN → FOLLOWING → RESOLVED → CLOSED（也可以从 OPEN 直接 RESOLVED）。 */
    @Transactional
    public Ticket changeStatus(long id, TicketStatusPayload payload) {
        Ticket existing = get(id);
        IntlStateMachine.assertTicket(existing.status(), payload.status());
        if ("RESOLVED".equals(payload.status())
                && (payload.resolution() == null || payload.resolution().isBlank())) {
            throw new IllegalArgumentException("标记已解决必须填处理结果");
        }
        jdbc.update("""
                UPDATE crm_tickets
                   SET status = ?,
                       resolution = COALESCE(NULLIF(?, ''), resolution),
                       resolved_at = CASE WHEN ? = 'RESOLVED' THEN ? ELSE resolved_at END
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.status(),
                IntlFields.text(payload.resolution()),
                payload.status(), nowUtc(),
                id, TenantContext.get());
        return get(id);
    }

    /** TK + yyyyMMdd + 4 位序列，走原子自增发号器（见 IntlNumberGenerator）。 */
    private String generateTicketNo() {
        return IntlNumberGenerator.nextNo(named, TenantContext.get(), "TICKET", "TK", 4);
    }

    private static void appendNos(StringBuilder where, MapSqlParameterSource params,
                                  String column, String param, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        List<String> nos = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                nos.add(trimmed);
            }
        }
        if (nos.isEmpty()) {
            return;
        }
        where.append(" AND ").append(column).append(" IN (:").append(param).append(") ");
        params.addValue(param, nos.size() > 200 ? nos.subList(0, 200) : nos);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
