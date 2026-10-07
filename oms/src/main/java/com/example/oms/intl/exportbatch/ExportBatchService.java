package com.example.oms.intl.exportbatch;

import com.example.oms.intl.IntlNumberGenerator;
import com.example.oms.ConflictException;
import com.example.oms.NotFoundException;
import com.example.oms.intl.IntlFields;
import com.example.oms.intl.IntlPage;
import com.example.oms.intl.IntlStateMachine;
import com.example.oms.intl.exportorder.ExportOrder;
import com.example.oms.intl.exportorder.ExportOrderService;
import com.example.oms.linkage.LinkageClient;
import com.example.oms.linkage.LinkageService;
import com.example.oms.tenant.TenantContext;
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
 * 出口母单（拼批 + 发起订舱）数据访问。
 *
 * <p><b>组批的两条硬规则</b>：
 * <ol>
 *   <li>子单必须**未组批**（或已属于本批）才允许加入——一张子单只能在一票货里；</li>
 *   <li>加入后 is_batch_locked=1，此后不允许改子单明细（否则母单汇总与子单对不上）。</li>
 * </ol>
 *
 * <p><b>发起订舱是 M1 唯一的跨库同步写</b>：在 OMS 事务内经 LinkageService.ensureShipment
 * 先查后建 TMS 运单，任何一步失败抛 LinkageException → 事务回滚 → 502 linkage_failed。
 * 母单状态推进到 BOOKING 与建单同事务，所以不会出现「母单已订舱、TMS 没运单」。</p>
 */
@Service
@RequiredArgsConstructor
public class ExportBatchService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ExportOrderService orderService;
    private final LinkageService linkage;

    private static final String BASE_SELECT = """
            SELECT id, batch_no, mode, pol_code, pol_name, pod_code, pod_name, route_id, carrier_id,
                   container_type, container_qty, incoterm, currency, total_qty, total_weight,
                   total_volume, volumetric_weight, etd_date, eta_date, cutoff_at, status,
                   remark, created_by, created_at, updated_at,
                   NULL AS shipment_no, NULL AS shipment_status
            FROM oms_export_batches
            """;

    public IntlPage<ExportBatch> search(String keyword, String status, String mode, String polCode,
                                        String podCode, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND batch_no LIKE :kw ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }
        if (mode != null && !mode.isBlank()) {
            where.append(" AND mode = :mode ");
            params.addValue("mode", mode.trim());
        }
        if (polCode != null && !polCode.isBlank()) {
            where.append(" AND pol_code = :pol ");
            params.addValue("pol", polCode.trim());
        }
        if (podCode != null && !podCode.isBlank()) {
            where.append(" AND pod_code = :pod ");
            params.addValue("pod", podCode.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM oms_export_batches" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<ExportBatch> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, ExportBatch.ROW_MAPPER);
        return IntlPage.of(enrich(list), total == null ? 0 : total, safePage, safeSize);
    }

    public ExportBatch get(long id) {
        List<ExportBatch> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", ExportBatch.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("出口母单不存在: " + id);
        }
        return rows.get(0);
    }

    public List<ExportBatch> brief() {
        return jdbc.query(BASE_SELECT + " WHERE tenant_id = ? ORDER BY id DESC LIMIT 500",
                ExportBatch.ROW_MAPPER, TenantContext.get());
    }

    public ExportBatchDetail detail(long id) {
        ExportBatch batch = get(id);
        return new ExportBatchDetail(enrichOne(batch), orderService.listByBatch(batch.batchNo()),
                linkage.findShipmentByBatchNo(batch.batchNo()));
    }

    /**
     * 组批：建母单并把 orderNos 里的子单挂上去（同事务）。
     *
     * <p>顺序是「先建母单 → 再挂子单」：挂子单要写 batch_no 与 is_batch_locked，
     * 母单号必须已经存在，否则子单会指向一个空气单号。</p>
     */
    @Transactional
    public ExportBatchDetail create(ExportBatchPayload payload) {
        named.update("""
                INSERT INTO oms_export_batches
                    (tenant_id, batch_no, mode, pol_code, pol_name, pod_code, pod_name, route_id, carrier_id, container_type, container_qty, incoterm, currency, etd_date, eta_date, cutoff_at, status, remark, created_by)
                VALUES (:tenant_id, :batch_no, :mode, :pol_code, :pol_name, :pod_code, :pod_name, :route_id, :carrier_id, :container_type, :container_qty, :incoterm, :currency, :etd_date, :eta_date, :cutoff_at, :status, :remark, :created_by)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("batch_no", IntlFields.orDefault(payload.batchNo(), generateBatchNo()))
                        .addValue("mode", IntlFields.orDefault(payload.mode(), "SEA"))
                        .addValue("pol_code", IntlFields.text(payload.polCode()))
                        .addValue("pol_name", IntlFields.text(payload.polName()))
                        .addValue("pod_code", IntlFields.text(payload.podCode()))
                        .addValue("pod_name", IntlFields.text(payload.podName()))
                        .addValue("route_id", payload.routeId() == null ? 0L : payload.routeId())
                        .addValue("carrier_id", payload.carrierId() == null ? 0L : payload.carrierId())
                        .addValue("container_type", IntlFields.orDefault(payload.containerType(), "20GP"))
                        .addValue("container_qty", payload.containerQty() == null ? 1 : payload.containerQty())
                        .addValue("incoterm", IntlFields.orDefault(payload.incoterm(), "FOB"))
                        .addValue("currency", IntlFields.orDefault(payload.currency(), "USD"))
                        .addValue("etd_date", payload.etdDate())
                        .addValue("eta_date", payload.etaDate())
                        .addValue("cutoff_at", payload.cutoffAt())
                        .addValue("status", IntlFields.orDefault(payload.status(), "DRAFT"))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        .addValue("created_by", com.example.oms.intl.IntlCurrentUser.id())
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        if (payload.orderNos() != null && !payload.orderNos().isEmpty()) {
            addOrders(id, payload.orderNos());
        }
        recalcTotals(id);
        return detail(id);
    }

    @Transactional
    public ExportBatchDetail update(ExportBatchPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少母单 id");
        }
        ExportBatch existing = get(payload.id());
        // 只改可改字段，不碰 status：状态推进只有 PATCH /status 一个入口，
        // 那条路上过 IntlStateMachine 校验。这里若也接受 status，就等于给了
        // 一个绕过迁移表的暗门（能把已开航的母单改回 DRAFT，而 TMS 运单还在 DEPARTED）。
        // 与 ShipmentService.update() 同一口径。
        int updated = jdbc.update("""
                UPDATE oms_export_batches
                   SET mode = ?, pol_code = ?, pol_name = ?, pod_code = ?, pod_name = ?, route_id = ?,
                       carrier_id = ?, container_type = ?, container_qty = ?, incoterm = ?, currency = ?,
                       etd_date = ?, eta_date = ?, cutoff_at = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                IntlFields.orDefault(payload.mode(), existing.mode()),
                IntlFields.text(payload.polCode()),
                IntlFields.text(payload.polName()),
                IntlFields.text(payload.podCode()),
                IntlFields.text(payload.podName()),
                payload.routeId() == null ? existing.routeId() : payload.routeId(),
                payload.carrierId() == null ? existing.carrierId() : payload.carrierId(),
                IntlFields.orDefault(payload.containerType(), existing.containerType()),
                payload.containerQty() == null ? existing.containerQty() : payload.containerQty(),
                IntlFields.orDefault(payload.incoterm(), existing.incoterm()),
                IntlFields.orDefault(payload.currency(), existing.currency()),
                payload.etdDate() == null ? existing.etdDate() : payload.etdDate(),
                payload.etaDate() == null ? existing.etaDate() : payload.etaDate(),
                payload.cutoffAt() == null ? existing.cutoffAt() : payload.cutoffAt(),
                payload.remark() == null ? existing.remark() : payload.remark(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("出口母单不存在: " + payload.id());
        }
        if (payload.orderNos() != null && !payload.orderNos().isEmpty()) {
            addOrders(payload.id(), payload.orderNos());
        }
        recalcTotals(payload.id());
        return detail(payload.id());
    }

    public void delete(long id) {
        get(id);
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_export_orders WHERE batch_no = (SELECT batch_no FROM oms_export_batches WHERE id = ? AND tenant_id = ?) AND tenant_id = ?",
                Integer.class, id, TenantContext.get(), TenantContext.get());
        if (orders != null && orders > 0) {
            throw new ConflictException("母单下还有 " + orders + " 张子单，请先移出再删");
        }
        Integer deleted = jdbc.update("DELETE FROM oms_export_batches WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("出口母单不存在: " + id);
        }
    }

    /** 把子单加进母批（未组批或已属于本批才允许）。 */
    @Transactional
    public ExportBatchDetail addOrders(long id, List<String> orderNos) {
        ExportBatch batch = get(id);
        for (String orderNo : orderNos) {
            if (orderNo == null || orderNo.isBlank()) {
                continue;
            }
            ExportOrder order = orderService.findByOrderNo(orderNo);
            if (order.batchNo() != null && !order.batchNo().isBlank() && !order.batchNo().equals(batch.batchNo())) {
                throw new ConflictException("子单 " + orderNo + " 已在母单 " + order.batchNo() + " 里，一票货只能有一个母单");
            }
            orderService.assignBatch(order.id(), batch.batchNo());
        }
        recalcTotals(id);
        return detail(id);
    }

    /** 把子单移出母批（顺带解锁明细、汇总重算）。 */
    @Transactional
    public ExportBatchDetail removeOrder(long id, String orderNo) {
        get(id);
        orderService.assignBatch(orderService.findByOrderNo(orderNo).id(), "");
        recalcTotals(id);
        return detail(id);
    }

    @Transactional
    public ExportBatchDetail changeStatus(long id, ExportBatchStatusPayload payload) {
        ExportBatch existing = get(id);
        IntlStateMachine.assertBatch(IntlStateMachine.EXPORT_BATCH, existing.status(), payload.status());
        int updated = jdbc.update("""
                UPDATE oms_export_batches
                   SET status = ?,
                       remark = CASE WHEN ? IS NOT NULL AND ? <> '' THEN ? ELSE remark END
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.status(), payload.remark(), payload.remark(), payload.remark(),
                id, TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("出口母单不存在: " + id);
        }
        return detail(id);
    }

    /**
     * 发起订舱：**OMS 事务内**先查后建 TMS 运单，成功后母单 → BOOKING。
     *
     * <p>前置校验：承运商必填。母单没选承运商就订舱是不成立的——
     * TMS 的 tms_shipments.carrier_id 是 NOT NULL，让下游 400 会得到一句
     * 看不懂的「列不能为空」，不如在这里说人话。</p>
     *
     * <p>幂等：TMS 已有同 batch_no 的运单就复用，母单也照常推到 BOOKING。
     * 重复点「发起订舱」不会产生第二张主单。</p>
     */
    @Transactional
    public ExportBatchDetail ship(long id, ShipBatchRequest request) {
        ExportBatch batch = get(id);
        if (request.carrierId() == null || request.carrierId() <= 0) {
            throw new IllegalArgumentException("发起订舱必须先选承运商");
        }
        if ("DRAFT".equals(batch.status())) {
            throw new IllegalArgumentException("母单还是草稿，请先推进到「已确认」再发起订舱");
        }
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_export_orders WHERE tenant_id = ? AND batch_no = ?",
                Integer.class, TenantContext.get(), batch.batchNo());
        if (orders == null || orders == 0) {
            throw new IllegalArgumentException("母单下没有子单，不能发起订舱");
        }

        String containerType = IntlFields.orDefault(request.containerType(), batch.containerType());
        List<LinkageService.ShipmentItemLine> lines = new ArrayList<>();
        int seq = 0;
        for (ExportOrder order : orderService.listByBatch(batch.batchNo())) {
            seq++;
            lines.add(new LinkageService.ShipmentItemLine(
                    order.orderNo(),
                    batch.batchNo() + "-" + String.format("%02d", seq),
                    buildMarks(order, batch, String.format("%02d", seq)),
                    order.totalQty(), order.totalWeight(), order.totalVolume(), order.volumetricWeight()));
        }

        // 危险品三件套必须在这里汇总后传给 TMS：TMS 库没有子单明细，不传就等于「无危险品」，
        // 而锂电池是海运/空运强制申报项（IATA DGR class 9），漏报会让舱单与报关单不合规。
        DangerousGoods dg = sumDangerousGoods(batch.batchNo());

        LinkageClient.ShipmentDetailResult created = linkage.ensureShipment(new LinkageService.ShipmentCreateCommand(
                batch.batchNo(),
                IntlFields.orDefault(request.mode(), batch.mode()),
                request.carrierId(),
                IntlFields.orDefault(request.polCode(), batch.polCode()),
                IntlFields.orDefault(request.podCode(), batch.podCode()),
                containerType,
                request.containerQty() == null ? batch.containerQty() : request.containerQty(),
                IntlFields.orDefault(batch.incoterm(), "FOB"),
                request.sailingId() == null ? 0L : request.sailingId(),
                batch.totalQty(), batch.totalWeight(), batch.totalVolume(),
                dateToUtcStart(batch.etdDate()), dateToUtcStart(batch.etaDate()), batch.cutoffAt(),
                IntlFields.text(request.remark()) + "（母单 " + batch.batchNo() + " 发起订舱）",
                dg.flag(), dg.unNumbers(), dg.dgClasses(),
                lines));

        // 运单已建（或复用），母单推进到「订舱中」
        if (IntlStateMachine.EXPORT_BATCH.getOrDefault(batch.status(), java.util.Set.of()).contains("BOOKING")) {
            jdbc.update("UPDATE oms_export_batches SET status = 'BOOKING' WHERE id = ? AND tenant_id = ?",
                    id, TenantContext.get());
        }
        jdbc.update("UPDATE oms_export_batches SET remark = ? WHERE id = ? AND tenant_id = ?",
                IntlFields.orDefault(batch.remark(), "") + " [运单 " + created.shipmentNo() + "]",
                id, TenantContext.get());
        return detail(id);
    }

    /**
     * 母单下危险品汇总（一票多品时 UN 号与 class 都可能多个）。
     *
     * <p>取自子单<b>明细行</b>（{@code oms_export_order_items}）而不是子单主表：
     * 「含危险品」是 SKU 级属性，同一个子单里可能只有一两个 SKU 带电。</p>
     *
     * <p>UN 号与 class 各自去重后用逗号拼接（运单是报文的归集对象，多值要能表达）；
     * UN3480 与 UN3481 可以同时出现——前者是锂电池单独装运（PI965），后者是装在
     * 设备内/随设备出货（PI967/PI966），两者包装说明不同，不能只留一个。</p>
     */
    private DangerousGoods sumDangerousGoods(String batchNo) {
        List<String> uns = jdbc.query("""
                SELECT DISTINCT i.un_number
                  FROM oms_export_order_items i
                  JOIN oms_export_orders o ON o.id = i.order_id
                 WHERE o.tenant_id = ? AND o.batch_no = ?
                   AND i.is_dangerous = 1 AND i.un_number <> ''
                 ORDER BY i.un_number
                """,
                (rs, i) -> rs.getString(1), TenantContext.get(), batchNo);
        List<String> classes = jdbc.query("""
                SELECT DISTINCT i.dg_class
                  FROM oms_export_order_items i
                  JOIN oms_export_orders o ON o.id = i.order_id
                 WHERE o.tenant_id = ? AND o.batch_no = ?
                   AND i.is_dangerous = 1 AND i.dg_class <> ''
                 ORDER BY i.dg_class
                """,
                (rs, i) -> rs.getString(1), TenantContext.get(), batchNo);
        return new DangerousGoods(!uns.isEmpty() && !classes.isEmpty() ? 1 : 0,
                String.join(",", uns), String.join(",", classes));
    }

    /** 危险品汇总结果（flag + 去重后的 UN 号/class 列表）。flag 用 Integer：下游 TMS 侧是 TINYINT。 */
    private record DangerousGoods(Integer flag, String unNumbers, String dgClasses) {
    }

    /** 母单汇总列从子单回算（组批/移出后立即重算，保证母单与子单永远一致）。 */
    public void recalcTotals(long batchId) {
        List<String> nos = jdbc.query("SELECT batch_no FROM oms_export_batches WHERE id = ? AND tenant_id = ?",
                (rs, i) -> rs.getString(1), batchId, TenantContext.get());
        if (nos.isEmpty()) {
            return;
        }
        named.update("""
                UPDATE oms_export_batches b
                   JOIN (SELECT batch_no,
                                SUM(total_qty)         AS qty,
                                SUM(total_weight)      AS weight,
                                SUM(total_volume)      AS volume,
                                SUM(volumetric_weight) AS vol_weight
                           FROM oms_export_orders
                          WHERE tenant_id = :tenant AND batch_no = :batchNo
                          GROUP BY batch_no) s ON s.batch_no = b.batch_no
                   SET b.total_qty = COALESCE(s.qty, 0),
                       b.total_weight = ROUND(COALESCE(s.weight, 0), 3),
                       b.total_volume = ROUND(COALESCE(s.volume, 0), 3),
                       b.volumetric_weight = ROUND(COALESCE(s.vol_weight, 0), 3)
                 WHERE b.id = :batchId AND b.tenant_id = :tenant
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", TenantContext.get())
                        .addValue("batchId", batchId)
                        .addValue("batchNo", nos.get(0)));
    }

    /** 母单列表的运单列（一次批量 HTTP，失败降级为 null）。 */
    private List<ExportBatch> enrich(List<ExportBatch> batches) {
        if (batches.isEmpty()) {
            return batches;
        }
        java.util.Map<String, LinkageClient.ShipmentInfo> byBatch = new java.util.HashMap<>();
        try {
            for (LinkageClient.ShipmentInfo s : linkage.findShipmentsByBatchNos(
                    batches.stream().map(ExportBatch::batchNo).toList())) {
                if (s.batchNo() != null) {
                    byBatch.put(s.batchNo(), s);
                }
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ExportBatchService.class)
                    .warn("出口母单列表聚合 TMS 运单失败，运单列降级为 —: {}", e.getMessage());
        }
        return batches.stream()
                .map(b -> {
                    LinkageClient.ShipmentInfo s = byBatch.get(b.batchNo());
                    return s == null ? b : b.withShipment(s.shipmentNo(), s.status());
                })
                .toList();
    }

    private ExportBatch enrichOne(ExportBatch batch) {
        return enrich(List.of(batch)).get(0);
    }

    /**
     * 唛头（shipping mark）标准排版：收货人抬头 / 单号 / 目的港 / 箱号 / 产地。
     * 一票多单时抬头用收货公司，没有就退回收货人。
     */
    private static String buildMarks(ExportOrder order, ExportBatch batch, String seq) {
        String header = order.consigneeCompany() == null || order.consigneeCompany().isBlank()
                ? order.consigneeName() : order.consigneeCompany();
        return String.join("\n",
                "N/M: " + header,
                "P/C: " + order.orderNo(),
                "R/D: " + batch.podCode(),
                "BOX NO: " + seq,
                "MADE IN CHINA");
    }

    /** 母单上的 DATE（当地）→ 运单的 UTC 时刻：按当天 01:00 UTC 落（= 北京 09:00）。 */
    private static LocalDateTime dateToUtcStart(java.time.LocalDate date) {
        return date == null ? null : date.atStartOfDay().plusHours(1);
    }

    /** MEXP + yyyyMMdd + 3 位序列，走原子自增发号器（见 IntlNumberGenerator）。 */
    private String generateBatchNo() {
        return IntlNumberGenerator.nextNo(named, TenantContext.get(), "BATCH", "MEXP", 3);
    }
}
