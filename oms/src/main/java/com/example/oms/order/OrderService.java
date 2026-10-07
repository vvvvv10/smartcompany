package com.example.oms.order;

import com.example.oms.NotFoundException;
import com.example.oms.linkage.LinkageService;
import com.example.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/status/channel 全部可选筛选（keyword 匹配订单号/客户名）。
 *
 * <p>订单是主表 + 明细两张表，写操作都在事务里：先写 oms_orders，
 * 再整批处理 oms_order_items，避免出现没有明细的半截订单。</p>
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext；明细的插入与父订单
 * 同请求同租户（订单与明细必须同租户），级联删明细前先用 get() 过父订单租户闸。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final LinkageService linkage;

    private static final String BASE_SELECT = """
            SELECT id, order_no, channel, customer_name, phone, address, status,
                   total_qty, total_amount, remark, created_at, updated_at,
                   NULL AS tms_status, NULL AS tms_origin, NULL AS tms_destination,
                   NULL AS out_qty
            FROM oms_orders
            """;

    private static final String ITEM_SELECT = """
            SELECT id, order_id, product_id, style_no, product_name, color, size, quantity, price
            FROM oms_order_items
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Order> search(String keyword, String status, String channel, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (order_no LIKE :kw OR customer_name LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }
        if (channel != null && !channel.isBlank()) {
            where.append(" AND channel = :channel ");
            params.addValue("channel", channel.trim());
        }

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM oms_orders" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Order> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, Order.ROW_MAPPER);
        // 「运单/出库」两列按订单号批量聚合回填（失败各自降级 null，不挡列表）
        list = linkage.enrich(list);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Order get(long id) {
        List<Order> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", Order.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("订单不存在: " + id);
        }
        return rows.get(0);
    }

    /** 某订单的全部明细。 */
    public List<OrderItem> items(long orderId) {
        return jdbc.query(ITEM_SELECT + " WHERE order_id = ? AND tenant_id = ? ORDER BY id",
                OrderItem.ROW_MAPPER, orderId, TenantContext.get());
    }

    /** 订单详情 = 主表 + 明细（带 TMS/WMS 关联聚合，供详情接口直接展示）。 */
    public OrderDetail detail(long id) {
        Order order = get(id);
        return OrderDetail.of(linkage.enrichOne(order), items(order.id()));
    }

    /** 不带聚合的原始详情（写路径先看它做联动判定，避免白跑两次 HTTP）。 */
    private OrderDetail rawDetail(long id) {
        Order order = get(id);
        return OrderDetail.of(order, items(order.id()));
    }

    /** 订单下拉/选择器用的轻量列表。 */
    public List<Order> brief() {
        return jdbc.query(BASE_SELECT + " WHERE tenant_id = ? ORDER BY id DESC LIMIT 500",
                Order.ROW_MAPPER, TenantContext.get());
    }

    /**
     * 创建订单：先插 oms_orders，再逐条插 oms_order_items。
     *
     * <p>totalQty = 明细数量之和；totalAmount = payload 给了就用 payload 的，
     * 没给就按明细 quantity × price 合计；orderNo 留空自动生成。</p>
     */
    @Transactional
    public OrderDetail create(OrderPayload payload) {
        List<OrderItem> items = normalizeItems(payload.items());

        int totalQty = items.isEmpty()
                ? (payload.totalQty() == null ? 0 : payload.totalQty())
                : sumQty(items);
        BigDecimal totalAmount = payload.totalAmount() != null
                ? payload.totalAmount()
                : sumAmount(items);

        jdbc.update("""
                INSERT INTO oms_orders
                    (tenant_id, order_no, channel, customer_name, phone, address, status, total_qty, total_amount, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                defaultIfBlank(payload.orderNo(), generateOrderNo()),
                defaultIfBlank(payload.channel(), "OFFLINE"),
                nullSafe(payload.customerName()),
                nullSafe(payload.phone()),
                nullSafe(payload.address()),
                defaultIfBlank(payload.status(), "UNPAID"),
                totalQty,
                totalAmount,
                nullSafe(payload.remark()));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        insertItems(id, items);
        // 终态发货联动（事务内）：失败抛 LinkageException 回滚，重试靠先查后建
        linkage.ensureLinkage(rawDetail(id));
        return detail(id);
    }

    /**
     * 更新订单主表；payload 带 items 时删掉旧明细整批重插（并按新明细重算件数/金额），
     * items 为 null 表示不动明细。
     */
    @Transactional
    public OrderDetail update(OrderPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少订单 id");
        }
        Order existing = get(payload.id());
        boolean replaceItems = payload.items() != null;
        List<OrderItem> items = replaceItems ? normalizeItems(payload.items()) : null;

        int totalQty;
        BigDecimal totalAmount;
        if (replaceItems && !items.isEmpty()) {
            totalQty = sumQty(items);
            totalAmount = payload.totalAmount() != null ? payload.totalAmount() : sumAmount(items);
        } else {
            totalQty = payload.totalQty() != null ? payload.totalQty() : existing.totalQty();
            totalAmount = payload.totalAmount() != null ? payload.totalAmount() : existing.totalAmount();
        }

        int updated = jdbc.update("""
                UPDATE oms_orders
                   SET order_no = ?, channel = ?, customer_name = ?, phone = ?, address = ?,
                       status = ?, total_qty = ?, total_amount = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                defaultIfBlank(payload.orderNo(), existing.orderNo()),
                defaultIfBlank(payload.channel(), "OFFLINE"),
                nullSafe(payload.customerName()),
                nullSafe(payload.phone()),
                nullSafe(payload.address()),
                defaultIfBlank(payload.status(), "UNPAID"),
                totalQty,
                totalAmount,
                nullSafe(payload.remark()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("订单不存在: " + payload.id());
        }

        if (replaceItems) {
            // 级联删旧明细：上面 get(payload.id()) 已过父订单租户闸，明细按 order_id 锚定父行即可
            jdbc.update("DELETE FROM oms_order_items WHERE order_id = ?", payload.id());
            insertItems(payload.id(), items);
        }
        // 终态发货联动（事务内）：状态仍是 SHIPPED/DONE 的每次编辑都幂等补一遍，
        // 确保运单/出库记录在被下游删掉后能自愈
        linkage.ensureLinkage(rawDetail(payload.id()));
        return detail(payload.id());
    }

    /** 删除订单，级联删明细。 */
    @Transactional
    public void delete(long id) {
        get(id); // 先过租户闸：跨租户按 id 猜测删会在这里抛 NotFound
        jdbc.update("DELETE FROM oms_order_items WHERE order_id = ?", id);
        int deleted = jdbc.update("DELETE FROM oms_orders WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("订单不存在: " + id);
        }
    }

    /** "OMS" + yyyyMMddHHmmss 后 6 位 + 6 位随机，同秒并发下单也不会撞唯一键。 */
    private static String generateOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "OMS" + ts.substring(ts.length() - 6)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /**
     * 归一化明细：给了 productId 时，缺哪项就回表从 oms_products 补哪项；
     * 数量缺省 1，价格缺省取商品价（商品也没有则 0）。
     */
    private List<OrderItem> normalizeItems(List<OrderItemPayload> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<OrderItem> items = new ArrayList<>(raw.size());
        for (OrderItemPayload item : raw) {
            ProductRef product = findProduct(item.productId());

            String styleNo = nullSafe(item.styleNo());
            String productName = nullSafe(item.productName());
            String color = nullSafe(item.color());
            String size = nullSafe(item.size());
            BigDecimal price = item.price();
            if (product != null) {
                if (styleNo.isBlank()) {
                    styleNo = product.styleNo();
                }
                if (productName.isBlank()) {
                    productName = product.name();
                }
                if (color.isBlank()) {
                    color = product.color();
                }
                if (size.isBlank()) {
                    size = product.size();
                }
                if (price == null) {
                    price = product.price();
                }
            }
            if (price == null) {
                price = BigDecimal.ZERO;
            }

            items.add(new OrderItem(
                    null,
                    null,
                    item.productId(),
                    styleNo,
                    productName,
                    color,
                    size,
                    item.quantity() == null ? 1 : item.quantity(),
                    price));
        }
        return items;
    }

    private void insertItems(long orderId, List<OrderItem> items) {
        for (OrderItem item : items) {
            jdbc.update("""
                    INSERT INTO oms_order_items
                        (tenant_id, order_id, product_id, style_no, product_name, color, size, quantity, price)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    TenantContext.get(),
                    orderId,
                    item.productId(),
                    item.styleNo(),
                    item.productName(),
                    item.color(),
                    item.size(),
                    item.quantity(),
                    item.price());
        }
    }

    private record ProductRef(String styleNo, String name, String color, String size, BigDecimal price) {
    }

    private ProductRef findProduct(Long productId) {
        if (productId == null) {
            return null;
        }
        List<ProductRef> rows = jdbc.query(
                "SELECT style_no, name, color, size, price FROM oms_products WHERE id = ? AND tenant_id = ?",
                (rs, i) -> new ProductRef(
                        rs.getString("style_no"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getString("size"),
                        rs.getBigDecimal("price")),
                productId, TenantContext.get());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int sumQty(List<OrderItem> items) {
        return items.stream().mapToInt(OrderItem::quantity).sum();
    }

    private static BigDecimal sumAmount(List<OrderItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            total = total.add(item.price().multiply(BigDecimal.valueOf(item.quantity())));
        }
        return total;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
