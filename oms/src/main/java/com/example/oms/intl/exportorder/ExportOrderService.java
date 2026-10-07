package com.example.oms.intl.exportorder;

import com.example.oms.intl.IntlNumberGenerator;
import com.example.oms.ConflictException;
import com.example.oms.NotFoundException;
import com.example.oms.intl.IntlFields;
import com.example.oms.intl.IntlPage;
import com.example.oms.intl.IntlStateMachine;
import com.example.oms.intl.exportbatch.ExportBatch;
import com.example.oms.linkage.LinkageService;
import com.example.oms.product.Product;
import com.example.oms.product.ProductService;
import com.example.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出口子单数据访问。
 *
 * <p><b>组批后锁明细</b>：isBatchLocked=1 时拒绝改 items。理由是母单的
 * 件数/毛重/体积是从子单汇总出来的，子单一改母单就对不上了；
 * 要改只能先拆批（把子单从母批里移出来），那一步显式、可追溯。</p>
 *
 * <p><b>汇总列从明细回算</b>：totalQty/totalAmount/totalWeight/totalVolume
 * 一律在写完明细后用一条 UPDATE 重算，而不是相信前端传来的合计数——
 * 前端传来的合计一旦与明细不一致，报关单上的货值就会对不上。</p>
 *
 * <p><b>跨库弱引用不校验</b>：customerId 指向 CRM，本服务不校验它存在
 * （校验要一次跨库 HTTP）。查不到时前端显示 customerName 快照。</p>
 */
@Service
@RequiredArgsConstructor
public class ExportOrderService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final LinkageService linkage;

    private static final String BASE_SELECT = """
            SELECT id, order_no, batch_no, is_batch_locked, customer_id, customer_name,
                   consignee_name, consignee_company, consignee_country, consignee_address,
                   consignee_phone, consignee_email, incoterm, currency, total_amount,
                   freight_amount, total_qty, total_weight, total_volume, volumetric_weight,
                   etd_date, eta_date, ready_date, status, remark, created_by, created_at, updated_at,
                   NULL AS batch_status, NULL AS shipment_no, NULL AS shipment_status,
                   NULL AS awb_no, NULL AS booking_no, NULL AS etd_at, NULL AS eta_at,
                   NULL AS pack_status, NULL AS box_no
            FROM oms_export_orders
            """;

    private static final String ITEM_SELECT = """
            SELECT id, order_id, order_no, line_no, product_id, sku, product_name,
                   customs_name, customs_name_en, hs_code, origin_country, quantity, unit_price,
                   declared_value, net_weight, gross_weight, volume, is_dangerous, un_number,
                   dg_class, packing_instruction, battery_watt_hours, remark, created_at
            FROM oms_export_order_items
            """;

    public IntlPage<ExportOrder> search(String keyword, String status, String batchNo, Long customerId,
                                        String consigneeCountry, String incoterm, String currency,
                                        String etdFrom, String etdTo, boolean hasException,
                                        int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (order_no LIKE :kw OR batch_no LIKE :kw OR customer_name LIKE :kw "
                    + "OR consignee_name LIKE :kw OR consignee_company LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }
        if (batchNo != null && !batchNo.isBlank()) {
            where.append(" AND batch_no = :batchNo ");
            params.addValue("batchNo", batchNo.trim());
        }
        if (customerId != null) {
            where.append(" AND customer_id = :customerId ");
            params.addValue("customerId", customerId);
        }
        if (consigneeCountry != null && !consigneeCountry.isBlank()) {
            where.append(" AND consignee_country = :country ");
            params.addValue("country", consigneeCountry.trim());
        }
        if (incoterm != null && !incoterm.isBlank()) {
            where.append(" AND incoterm = :incoterm ");
            params.addValue("incoterm", incoterm.trim());
        }
        if (currency != null && !currency.isBlank()) {
            where.append(" AND currency = :currency ");
            params.addValue("currency", currency.trim());
        }
        if (hasException) {
            where.append(" AND status = 'EXCEPTION' ");
        }
        // ETD 筛的是 DATE 列（当地日期），所以这里不带时间部分——
        // 前端 DatePicker 给的 yyyy-MM-dd 直接用，不要自己拼 00:00:00
        if (etdFrom != null && !etdFrom.isBlank()) {
            where.append(" AND etd_date >= :etdFrom ");
            params.addValue("etdFrom", etdFrom.trim());
        }
        if (etdTo != null && !etdTo.isBlank()) {
            where.append(" AND etd_date <= :etdTo ");
            params.addValue("etdTo", etdTo.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM oms_export_orders" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<ExportOrder> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, ExportOrder.ROW_MAPPER);
        return IntlPage.of(linkage.enrichExportOrders(list, batchStatusOf(list)), total == null ? 0 : total,
                safePage, safeSize);
    }

    public ExportOrder get(long id) {
        List<ExportOrder> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", ExportOrder.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("出口子单不存在: " + id);
        }
        return rows.get(0);
    }

    public List<ExportOrderItem> items(long orderId) {
        return jdbc.query(ITEM_SELECT + " WHERE order_id = ? AND tenant_id = ? ORDER BY line_no, id",
                ExportOrderItem.ROW_MAPPER, orderId, TenantContext.get());
    }

    /** 下拉选择器（组批选子单、母单选成员都用它）。 */
    public List<ExportOrder> brief(String status) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get());
        sql.append(" WHERE tenant_id = ?");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        return jdbc.query(sql.toString(), ExportOrder.ROW_MAPPER, args.toArray());
    }

    /** 按母单号取全部子单（母单详情一次拉齐；idx_batch 服务它）。 */
    public List<ExportOrder> listByBatch(String batchNo) {
        return jdbc.query(
                BASE_SELECT + " WHERE tenant_id = ? AND batch_no = ? ORDER BY order_no",
                ExportOrder.ROW_MAPPER, TenantContext.get(), batchNo);
    }

    public ExportOrderDetail detail(long id) {
        ExportOrder order = get(id);
        ExportBatch batch = null;
        if (order.batchNo() != null && !order.batchNo().isBlank()) {
            List<ExportBatch> rows = jdbc.query("""
                    SELECT id, batch_no, mode, pol_code, pol_name, pod_code, pod_name, route_id,
                           carrier_id, container_type, container_qty, incoterm, currency, total_qty,
                           total_weight, total_volume, volumetric_weight, etd_date, eta_date,
                           cutoff_at, status, remark, created_by, created_at, updated_at,
                           NULL AS shipment_no, NULL AS shipment_status
                    FROM oms_export_batches WHERE tenant_id = ? AND batch_no = ?
                    """, ExportBatch.ROW_MAPPER, TenantContext.get(), order.batchNo());
            batch = rows.isEmpty() ? null : rows.get(0);
        }
        return new ExportOrderDetail(
                linkage.enrichExportOrder(order, batch == null ? Map.of() : Map.of(batch.batchNo(), batch)),
                items(id), batch,
                linkage.findShipmentByBatchNo(order.batchNo()),
                linkage.findPackTaskByOrderNo(order.orderNo()));
    }

    @Transactional
    public ExportOrderDetail create(ExportOrderPayload payload) {
        List<ExportOrderItemPayload> items = normalizeItems(payload.items());
        named.update("""
                INSERT INTO oms_export_orders
                    (tenant_id, order_no, batch_no, is_batch_locked, customer_id, customer_name, consignee_name, consignee_company, consignee_country, consignee_address, consignee_phone, consignee_email, incoterm, currency, freight_amount, total_qty, total_weight, total_volume, volumetric_weight, etd_date, eta_date, ready_date, status, remark, created_by)
                VALUES (:tenant_id, :order_no, '', 0, :customer_id, :customer_name, :consignee_name, :consignee_company, :consignee_country, :consignee_address, :consignee_phone, :consignee_email, :incoterm, :currency, :freight_amount, 0, 0, 0, 0, :etd_date, :eta_date, :ready_date, :status, :remark, :created_by)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("order_no", IntlFields.orDefault(payload.orderNo(), generateOrderNo()))
                        .addValue("customer_id", payload.customerId())
                        .addValue("customer_name", IntlFields.text(payload.customerName()))
                        .addValue("consignee_name", payload.consigneeName().trim())
                        .addValue("consignee_company", IntlFields.text(payload.consigneeCompany()))
                        .addValue("consignee_country", IntlFields.orDefault(payload.consigneeCountry(), ""))
                        .addValue("consignee_address", payload.consigneeAddress().trim())
                        .addValue("consignee_phone", IntlFields.text(payload.consigneePhone()))
                        .addValue("consignee_email", IntlFields.text(payload.consigneeEmail()))
                        .addValue("incoterm", IntlFields.orDefault(payload.incoterm(), "FOB"))
                        .addValue("currency", IntlFields.orDefault(payload.currency(), "USD"))
                        .addValue("freight_amount", payload.freightAmount() == null ? BigDecimal.ZERO : payload.freightAmount())
                        .addValue("etd_date", payload.etdDate())
                        .addValue("eta_date", payload.etaDate())
                        .addValue("ready_date", payload.readyDate())
                        .addValue("status", IntlFields.orDefault(payload.status(), "DRAFT"))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        .addValue("created_by", com.example.oms.intl.IntlCurrentUser.id())
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        insertItems(id, id, items);
        recalcTotals(id);
        return detail(id);
    }

    @Transactional
    public ExportOrderDetail update(ExportOrderPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少出口子单 id");
        }
        ExportOrder existing = get(payload.id());
        boolean replaceItems = payload.items() != null;
        if (replaceItems && existing.isBatchLocked() != null && existing.isBatchLocked() == 1) {
            throw new ConflictException("子单已组入母单 " + existing.batchNo()
                    + "，不允许改明细；请先把它移出母批或拆批");
        }
        // 只改可改字段，不碰 status：状态推进只有 PATCH /status 一个入口（那里过
        // IntlStateMachine 校验），与 ShipmentService.update() 同一口径
        int updated = jdbc.update("""
                UPDATE oms_export_orders
                   SET customer_id = ?, customer_name = ?, consignee_name = ?, consignee_company = ?,
                       consignee_country = ?, consignee_address = ?, consignee_phone = ?, consignee_email = ?,
                       incoterm = ?, currency = ?, freight_amount = ?, etd_date = ?, eta_date = ?,
                       ready_date = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.customerId(),
                IntlFields.text(payload.customerName()),
                payload.consigneeName().trim(),
                IntlFields.text(payload.consigneeCompany()),
                IntlFields.orDefault(payload.consigneeCountry(), existing.consigneeCountry()),
                payload.consigneeAddress().trim(),
                IntlFields.text(payload.consigneePhone()),
                IntlFields.text(payload.consigneeEmail()),
                IntlFields.orDefault(payload.incoterm(), existing.incoterm()),
                IntlFields.orDefault(payload.currency(), existing.currency()),
                payload.freightAmount() == null ? existing.freightAmount() : payload.freightAmount(),
                payload.etdDate() == null ? existing.etdDate() : payload.etdDate(),
                payload.etaDate() == null ? existing.etaDate() : payload.etaDate(),
                payload.readyDate() == null ? existing.readyDate() : payload.readyDate(),
                payload.remark() == null ? existing.remark() : payload.remark(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("出口子单不存在: " + payload.id());
        }
        if (replaceItems) {
            jdbc.update("DELETE FROM oms_export_order_items WHERE order_id = ? AND tenant_id = ?",
                    payload.id(), TenantContext.get());
            insertItems(payload.id(), payload.id(), normalizeItems(payload.items()));
            recalcTotals(payload.id());
        }
        return detail(payload.id());
    }

    public void delete(long id) {
        ExportOrder existing = get(id);
        if (existing.isBatchLocked() != null && existing.isBatchLocked() == 1) {
            throw new ConflictException("子单已组入母单 " + existing.batchNo() + "，不能删除；请先移出母批");
        }
        if (!"DRAFT".equals(existing.status()) && !"CONFIRMED".equals(existing.status())) {
            throw new ConflictException("只有草稿/已确认的子单能删除，当前是 " + existing.status());
        }
        jdbc.update("DELETE FROM oms_export_order_items WHERE order_id = ? AND tenant_id = ?",
                id, TenantContext.get());
        int deleted = jdbc.update("DELETE FROM oms_export_orders WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("出口子单不存在: " + id);
        }
    }

    /** 推进子单状态（唯一推进入口）。M1 不做任何反向联动——由人工在页面上点。 */
    @Transactional
    public ExportOrderDetail changeStatus(long id, ExportOrderStatusPayload payload) {
        ExportOrder existing = get(id);
        IntlStateMachine.assertOrder(IntlStateMachine.EXPORT_ORDER, existing.status(), payload.status());
        int updated = jdbc.update("""
                UPDATE oms_export_orders
                   SET status = ?,
                       ready_date = CASE WHEN ? = 'PACKED' THEN COALESCE(ready_date, UTC_DATE()) ELSE ready_date END,
                       remark = CASE WHEN ? IS NOT NULL AND ? <> '' THEN ? ELSE remark END
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.status(),
                payload.status(),
                payload.remark(), payload.remark(), payload.remark(),
                id, TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("出口子单不存在: " + id);
        }
        return detail(id);
    }

    /** 组批 / 移出母批时由 ExportBatchService 调用（同库，不跨服务）。 */
    public void assignBatch(long orderId, String batchNo) {
        jdbc.update("UPDATE oms_export_orders SET batch_no = ?, is_batch_locked = ? "
                        + "WHERE id = ? AND tenant_id = ?",
                IntlFields.text(batchNo), batchNo == null || batchNo.isBlank() ? 0 : 1,
                orderId, TenantContext.get());
    }

    /** 按子单号取（组批/移出成员用；跨服务只按字符串单号关联，不认 id）。 */
    public ExportOrder findByOrderNo(String orderNo) {
        List<ExportOrder> rows = jdbc.query(
                BASE_SELECT + " WHERE tenant_id = ? AND order_no = ?",
                ExportOrder.ROW_MAPPER, TenantContext.get(), orderNo == null ? "" : orderNo.trim());
        if (rows.isEmpty()) {
            throw new NotFoundException("出口子单不存在: " + orderNo);
        }
        return rows.get(0);
    }

    // ---------------- 明细与汇总 ----------------

    /**
     * 归一化明细：给了 productId 时，缺哪项就回表从 oms_products 补哪项。
     *
     * <p>申报要素（HS 编码/报关品名/危险品）默认从商品档案带出，
     * 但**前端可以覆盖**——报关行的口径有时与商品档案不同（比如把「手机」
     * 报成「便携式移动通信设备」）。所以补齐的逻辑只在字段为空时生效。</p>
     */
    private List<ExportOrderItemPayload> normalizeItems(List<ExportOrderItemPayload> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ExportOrderItemPayload> items = new ArrayList<>(raw.size());
        for (ExportOrderItemPayload item : raw) {
            Product product = item.productId() == null ? null : findProduct(item.productId());
            if (product == null) {
                items.add(item);
                continue;
            }
            items.add(new ExportOrderItemPayload(
                    item.id(), item.productId(),
                    firstNonBlank(item.sku(), product.sku()),
                    firstNonBlank(item.productName(), product.name()),
                    firstNonBlank(item.customsName(), product.customsName()),
                    firstNonBlank(item.customsNameEn(), product.customsNameEn()),
                    firstNonBlank(item.hsCode(), product.hsCode()),
                    firstNonBlank(item.originCountry(), product.originCountry()),
                    item.quantity(),
                    item.unitPrice() == null ? product.price() : item.unitPrice(),
                    item.declaredValue(),
                    item.netWeight() == null ? product.netWeight() : item.netWeight(),
                    item.grossWeight() == null ? product.grossWeight() : item.grossWeight(),
                    item.volume() == null ? product.volumeCbm() : item.volume(),
                    item.isDangerous() == null ? product.isDangerous() : item.isDangerous(),
                    firstNonBlank(item.unNumber(), product.unNumber()),
                    firstNonBlank(item.dgClass(), product.dgClass()),
                    firstNonBlank(item.packingInstruction(), product.packingInstruction()),
                    item.batteryWattHours() == null ? product.batteryWattHours() : item.batteryWattHours(),
                    item.remark()));
        }
        return items;
    }

    private void insertItems(long orderId, long id, List<ExportOrderItemPayload> items) {
        String orderNo = get(orderId).orderNo();
        int lineNo = 1;
        for (ExportOrderItemPayload item : items) {
            BigDecimal declared = item.declaredValue() != null ? item.declaredValue()
                    : (item.unitPrice() == null ? BigDecimal.ZERO
                    : item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
            named.update("""
                    INSERT INTO oms_export_order_items
                        (tenant_id, order_id, order_no, line_no, product_id, sku, product_name, customs_name, customs_name_en, hs_code, origin_country, quantity, unit_price, declared_value, net_weight, gross_weight, volume, is_dangerous, un_number, dg_class, packing_instruction, battery_watt_hours, remark)
                    VALUES (:tenant_id, :order_id, :order_no, :line_no, :product_id, :sku, :product_name, :customs_name, :customs_name_en, :hs_code, :origin_country, :quantity, :unit_price, :declared_value, :net_weight, :gross_weight, :volume, :is_dangerous, :un_number, :dg_class, :packing_instruction, :battery_watt_hours, :remark)
                    """,
                    new MapSqlParameterSource()
                            .addValue("tenant_id", TenantContext.get())
                            .addValue("order_id", orderId)
                            .addValue("order_no", orderNo)
                            .addValue("line_no", lineNo)
                            .addValue("product_id", item.productId() == null ? 0L : item.productId())
                            .addValue("sku", IntlFields.text(item.sku()))
                            .addValue("product_name", IntlFields.text(item.productName()))
                            .addValue("customs_name", IntlFields.text(item.customsName()))
                            .addValue("customs_name_en", IntlFields.text(item.customsNameEn()))
                            .addValue("hs_code", IntlFields.text(item.hsCode()))
                            .addValue("origin_country", IntlFields.orDefault(item.originCountry(), "CN"))
                            .addValue("quantity", item.quantity())
                            .addValue("unit_price", item.unitPrice() == null ? BigDecimal.ZERO : item.unitPrice())
                            .addValue("declared_value", IntlFields.decimal(declared))
                            .addValue("net_weight", IntlFields.decimal(item.netWeight()))
                            .addValue("gross_weight", IntlFields.decimal(item.grossWeight()))
                            .addValue("volume", IntlFields.decimal(item.volume()))
                            .addValue("is_dangerous", IntlFields.zero(item.isDangerous()))
                            .addValue("un_number", IntlFields.text(item.unNumber()))
                            .addValue("dg_class", IntlFields.text(item.dgClass()))
                            .addValue("packing_instruction", IntlFields.text(item.packingInstruction()))
                            .addValue("battery_watt_hours", IntlFields.decimal(item.batteryWattHours()))
                            .addValue("remark", IntlFields.text(item.remark()))
                            );
            lineNo++;
        }
    }

    /**
     * 从明细回算子单的件数/金额/毛重/体积/体积重。
     *
     * <p><b>体积重口径</b> = Σ(单件体积 CBM × 数量) × 1,000,000 ÷ 除数。
     * {@code volume} 列的单位是立方米，公式里必须先换成 cm³ 再除——直接把 CBM
     * 乘除数会差 1e6/除数² 倍（除数 6000 时恰好 36 倍），而这种错在界面上
     * 只是一个「看起来挺大的数」，不核对就发现不了。</p>
     *
     * <p><b>除数取 6000 是空运口径（IATA TACT），不是海运口径</b>：海运 LCL 按
     * revenue ton（1 CBM = 1000 kg）计费，压根没有 6000 除数。航线的实际除数在
     * {@code tms_routes.volumetric_divisor}（空运 6000 / 快递 5000），M1 这里取
     * 常量 6000 只是为了让字段有个自洽的值，M3 接运价试算时必须改成按航线取
     * （跨库读不到 tms_routes，需要由调用方把除数传进来）。</p>
     */
    public void recalcTotals(long orderId) {
        // MySQL 没有 `UPDATE ... FROM`，多表更新要写成 `UPDATE t1 JOIN t2 ... SET`；
        // 参数也必须走 named（jdbc.update(sql, paramSource) 会把 Map 当成 Object[] 变参，
        // 结果是 "Parameter index out of range"）。
        named.update("""
                UPDATE oms_export_orders o
                   JOIN (SELECT order_id,
                                SUM(quantity)                   AS qty,
                                SUM(unit_price * quantity)      AS amount,
                                SUM(gross_weight * quantity)    AS weight,
                                SUM(volume * quantity)          AS volume,
                                -- volume 是 CBM，体积重要 cm³/kg：先 ×1e6 再 ÷ 除数
                                SUM(volume * quantity * 1000000.0 / 6000) AS vol_weight
                           FROM oms_export_order_items
                          WHERE tenant_id = :tenant AND order_id = :orderId
                          GROUP BY order_id) s ON s.order_id = o.id
                   SET o.total_qty = COALESCE(s.qty, 0),
                       o.total_amount = ROUND(COALESCE(s.amount, 0), 2),
                       o.total_weight = ROUND(COALESCE(s.weight, 0), 3),
                       o.total_volume = ROUND(COALESCE(s.volume, 0), 4),
                       o.volumetric_weight = ROUND(COALESCE(s.vol_weight, 0), 3)
                 WHERE o.id = :orderId AND o.tenant_id = :tenant
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", TenantContext.get())
                        .addValue("orderId", orderId));
    }

    private Product findProduct(Long productId) {
        List<Product> rows = jdbc.query("""
                SELECT id, style_no, name, category, season, color, size, sku, price, status, remark,
                       hs_code, customs_name, customs_name_en, origin_country, is_dangerous,
                       un_number, dg_class, packing_instruction, battery_watt_hours,
                       net_weight, gross_weight, volume_cbm, created_at, updated_at
                  FROM oms_products WHERE id = ? AND tenant_id = ?
                """, Product.ROW_MAPPER, productId, TenantContext.get());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, ExportBatch> batchStatusOf(List<ExportOrder> orders) {
        Map<String, ExportBatch> map = new HashMap<>();
        for (ExportOrder order : orders) {
            if (order.batchNo() == null || order.batchNo().isBlank()) {
                continue;
            }
            List<ExportBatch> rows = jdbc.query("""
                    SELECT id, batch_no, mode, pol_code, pol_name, pod_code, pod_name, route_id,
                           carrier_id, container_type, container_qty, incoterm, currency, total_qty,
                           total_weight, total_volume, volumetric_weight, etd_date, eta_date,
                           cutoff_at, status, remark, created_by, created_at, updated_at,
                           NULL AS shipment_no, NULL AS shipment_status
                    FROM oms_export_batches WHERE tenant_id = ? AND batch_no = ?
                    """, ExportBatch.ROW_MAPPER, TenantContext.get(), order.batchNo());
            if (!rows.isEmpty()) {
                map.put(order.batchNo(), rows.get(0));
            }
        }
        return map;
    }

    /** EXP + yyyyMMdd + 3 位序列，走原子自增发号器（见 IntlNumberGenerator）。 */
    private String generateOrderNo() {
        return IntlNumberGenerator.nextNo(named, TenantContext.get(), "ORDER", "EXP", 3);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }
}
