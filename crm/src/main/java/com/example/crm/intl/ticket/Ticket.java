package com.example.crm.intl.ticket;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 出口异常工单。
 *
 * <p>orderNo / shipmentNo 是跨库弱引用字符串（OMS 子单号 / TMS 运单号）；
 * customerName 是快照——客户改名或删档不影响历史工单可读。</p>
 *
 * <p>dueAt 是 SLA 到期（UTC）。逾期判定在 SQL 里用 {@code UTC_DATE()}，
 * 与库表 UTC 口径一致。</p>
 */
public record Ticket(
        Long id,
        String ticketNo,
        Long customerId,
        String customerName,
        String orderNo,
        String shipmentNo,
        String category,
        String severity,
        String status,
        String title,
        String detail,
        String resolution,
        Long ownerId,
        String ownerName,
        LocalDateTime dueAt,
        Boolean overdue,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<Ticket> ROW_MAPPER = (rs, i) -> new Ticket(
            rs.getLong("id"),
            rs.getString("ticket_no"),
            rs.getObject("customer_id", Long.class),
            rs.getString("customer_name"),
            rs.getString("order_no"),
            rs.getString("shipment_no"),
            rs.getString("category"),
            rs.getString("severity"),
            rs.getString("status"),
            rs.getString("title"),
            rs.getString("detail"),
            rs.getString("resolution"),
            rs.getObject("owner_id", Long.class),
            rs.getString("owner_name"),
            rs.getObject("due_at", LocalDateTime.class),
            rs.getObject("overdue", Boolean.class),
            rs.getObject("resolved_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
