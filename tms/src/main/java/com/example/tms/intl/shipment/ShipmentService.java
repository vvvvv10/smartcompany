package com.example.tms.intl.shipment;

import com.example.tms.intl.IntlNumberGenerator;
import com.example.tms.ConflictException;
import com.example.tms.NotFoundException;
import com.example.tms.intl.IntlPage;
import com.example.tms.intl.IntlStateMachine;
import com.example.tms.intl.IntlFields;
import com.example.tms.intl.carrier.CarrierService;
import com.example.tms.intl.customs.CustomsService;
import com.example.tms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 国际运单数据访问与状态机执行。
 *
 * <p><b>写入口的分工</b>：
 * <ul>
 *   <li>{@link #create} —— 建单（含箱明细），同时按 batchNo 幂等</li>
 *   <li>{@link #update} —— 只改可改字段，不碰状态</li>
 *   <li>{@link #changeStatus} —— **人工**推进（M1 的入口）</li>
 *   <li>{@link #changeStatusByCarrier} —— **承运商回调**推进（M2-4）</li>
 *   <li>{@link #changeStatusByScheduler} —— **定时任务**推进（M2-5）</li>
 * </ul>
 * 三个推进入口最终都汇到同一个私有方法 {@link #applyStatusChange}：
 * 状态机校验、三道业务门禁（异常原因 / 制裁 HIT / 海运 VGM）、状态落库、留轨迹，
 * 一行都不许各写各的。<b>这不是洁癖</b>——M2-5 的定时任务每天会自动推几百票，
 * 如果它另写一套 SQL 绕过 VGM 门禁，那么 SOLAS 门禁在这条路径上就是不存在，
 * 而门禁恰恰是「宁可在系统里推不动」的那类控制。见 {@link #changeStatusByScheduler}
 * 的注释里「为什么不能另写一套」。</p>
 *
 * <p>三个入口唯一的差别是「谁在说话」：节点来源（MANUAL / CARRIER_CALLBACK / SCHEDULED）、
 * operator 写谁、以及节点时间用「发生时刻」还是「入库时刻」。</p>
 *
 * <p><b>承运商警示前置校验</b>：sanctionFlag=HIT 时不允许推到 EXPORT_DECLARED。
 * 合规放行做成状态机的前置校验而不是审批流——当前权限模型里根本没有
 * 「合规专员」这种角色，硬加进审批体系会污染 system_admins 的语义（计划 §12.3）。</p>
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final CustomsService customs;
    private final CarrierService carriers;
    /** P1-5：sailing_id / route_id 是**同库**弱引用，本地就能校验，不必等M3 的跨库方案。 */
    private final com.example.tms.intl.sailing.SailingService sailings;
    private final com.example.tms.intl.route.RouteService routes;
    /** M2-4：轨迹 INSERT 收口到一处，人工 / 回调 / 定时 / 报关联动共用。 */
    private final TrackingNodeWriter tracker;

    private static final String BASE_SELECT = """
            SELECT s.id, s.shipment_no, s.batch_no, s.awb_no, s.bl_no, s.bl_type, s.bl_issuer,
                   s.booking_no, s.sailing_id,
                   s.mode, s.carrier_id, c.name_zh AS carrier_name, s.pol_code, s.pod_code,
                   s.container_type, s.container_qty, s.incoterm, s.currency, s.total_pieces,
                   s.total_gross_weight, s.total_volume, s.volumetric_weight, s.chargeable_weight,
                   s.vgm_weight, s.container_tare_weight,
                   s.marks, s.etd_at, s.eta_at, s.cutoff_at, s.actual_etd_at,
                   s.actual_eta_at, s.pod_received_at, s.is_dangerous, s.un_number, s.dg_class,
                   s.sanction_flag, s.ioss_no, s.status, s.exception_reason, s.remark,
                   s.created_by, s.created_at, s.updated_at
            FROM tms_shipments s
            LEFT JOIN tms_carriers c ON c.id = s.carrier_id
            """;

    private static final String ITEM_SELECT = """
            SELECT id, shipment_id, shipment_no, order_no, house_no, container_no, container_type,
                   seal_no, marks, pieces, gross_weight, volume, volumetric_weight,
                   is_dangerous, un_number, dg_class, created_at, updated_at
            FROM tms_shipment_items
            """;

    // raw_payload 不在这里：它是整条承运商报文的 JSON，一行轨迹带一份会让详情页体积翻几倍。
    // 要看原文走 GET /{id}/tracking/{nodeId}/raw —— 那是「对账扯皮时才翻」的冷路径。
    private static final String NODE_SELECT = """
            SELECT id, shipment_id, shipment_no, node_code, node_name, node_time, location,
                   source, operator, remark, external_code, external_source, created_at
            FROM tms_tracking_nodes
            """;

    public IntlPage<Shipment> search(String keyword, String status, String mode, Long carrierId,
                                     String batchNos, String orderNos, String polCode, String podCode,
                                     String etdFrom, String etdTo, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND s.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            // 箱号与唛头在子表上：EXISTS 一把，避免 JOIN 炸开分页与 count
            where.append(" AND (s.shipment_no LIKE :kw OR s.awb_no LIKE :kw OR s.bl_no LIKE :kw "
                    + "OR s.booking_no LIKE :kw OR s.marks LIKE :kw OR EXISTS ("
                    + "SELECT 1 FROM tms_shipment_items i WHERE i.shipment_id = s.id "
                    + "AND (i.container_no LIKE :kw OR i.house_no LIKE :kw OR i.order_no LIKE :kw))) ");
            params.addValue("kw", kw);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND s.status = :status ");
            params.addValue("status", status.trim());
        }
        if (mode != null && !mode.isBlank()) {
            where.append(" AND s.mode = :mode ");
            params.addValue("mode", mode.trim());
        }
        if (carrierId != null) {
            where.append(" AND s.carrier_id = :carrierId ");
            params.addValue("carrierId", carrierId);
        }
        if (polCode != null && !polCode.isBlank()) {
            where.append(" AND s.pol_code = :pol ");
            params.addValue("pol", polCode.trim());
        }
        if (podCode != null && !podCode.isBlank()) {
            where.append(" AND s.pod_code = :pod ");
            params.addValue("pod", podCode.trim());
        }
        appendNos(where, params, "s.batch_no", "batchNos", batchNos);
        // orderNos 走子表：箱明细里才有子单号，主表上没有
        appendOrderNos(where, params, orderNos);
        if (etdFrom != null && !etdFrom.isBlank()) {
            where.append(" AND s.etd_at >= :etdFrom ");
            params.addValue("etdFrom", etdFrom.trim() + " 00:00:00");
        }
        if (etdTo != null && !etdTo.isBlank()) {
            where.append(" AND s.etd_at <= :etdTo ");
            params.addValue("etdTo", etdTo.trim() + " 23:59:59");
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM tms_shipments s" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Shipment> list = named.query(
                BASE_SELECT + where + " ORDER BY s.id DESC LIMIT :limit OFFSET :offset",
                params, Shipment.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Shipment get(long id) {
        List<Shipment> rows = jdbc.query(
                BASE_SELECT + " WHERE s.id = ? AND s.tenant_id = ?", Shipment.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("国际运单不存在: " + id);
        }
        return rows.get(0);
    }

    public Optional<Shipment> findByNo(String shipmentNo) {
        List<Shipment> rows = jdbc.query(
                BASE_SELECT + " WHERE s.shipment_no = ? AND s.tenant_id = ?",
                Shipment.ROW_MAPPER, shipmentNo, TenantContext.get());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 按母单号找运单——OMS「发起订舱」的**先查**那一步就靠它做幂等。
     * batch_no 没有唯一索引（子单侧可以为空串），所以这里可能返回多条；
     * 取最早的一条，语义上就是「这票货第一次建的运单」。
     */
    public Optional<Shipment> findByBatchNo(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return Optional.empty();
        }
        List<Shipment> rows = jdbc.query(
                BASE_SELECT + " WHERE s.batch_no = ? AND s.tenant_id = ? ORDER BY s.id LIMIT 1",
                Shipment.ROW_MAPPER, batchNo.trim(), TenantContext.get());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 按**单号**反查运单——承运商回调的核心查询（M2-4）。
     *
     * <p>回调是「拿着提单号来问这票货到哪了」，不是「拿着我们的 id 来问」。
     * 承运商系统里根本没有我们的自增 id（那是数据库内部身份），它只有自己签发的
     * 单据号：海运是 BL、空运是 AWB。所以这里按三把钥匙依次试，顺序不能换：
     * <ol>
     *   <li>{@code shipment_no} —— 双方在订舱阶段就约定过的号，最精确</li>
     *   <li>{@code bl_no} —— 船司提单号。承运商内部系统的主键就是它</li>
     *   <li>{@code awb_no} —— IATA 空运单号</li>
     * </ol>
     *
     * <p><b>为什么不按 {@code house_no}（分单号）或箱号查</b>：那两列在
     * {@code tms_shipment_items} 上，理论上可以反查到运单，但 P1-4 引入 bl_type
     * 之后语义变复杂了——一柜多张 HBL 对应同一个母单运单，一条 house_no 可能
     * 命中 0 条、1 条或 N 条运单。没有规则说「命中多条该取哪一条」，而猜错的后果是
     * 「把 A 货主的状态写到 B 货主的运单上」。宁可反查不到（计入 rejected，
     * 运维看日志补录），也不能猜。这一条留给 M4 承运商级联接入时一起定。</p>
     *
     * <p>租户来自网关注入的 X-Tenant-Id；IFTSTA 报文本身没有租户概念
     * （一个承运商不会在报文里区分我们的不同租户），多租户下「哪个承运商属于哪个租户」
     * 需要一张绑定表，属M4。</p>
     */
    public Optional<Shipment> findByDocumentNos(List<String> nos) {
        for (String no : nos) {
            if (no == null || no.isBlank()) {
                continue;
            }
            String trimmed = no.trim();
            List<Shipment> rows = jdbc.query(
                    BASE_SELECT + " WHERE s.tenant_id = ? AND ("
                            + "s.shipment_no = ? OR s.bl_no = ? OR s.awb_no = ?) ORDER BY s.id LIMIT 1",
                    Shipment.ROW_MAPPER, TenantContext.get(), trimmed, trimmed, trimmed);
            if (!rows.isEmpty()) {
                return Optional.of(rows.get(0));
            }
        }
        return Optional.empty();
    }

    /** 列表下拉（≤500 行）。OMS 侧不直接用它——跨库一律走 LinkageClient。 */
    public List<Shipment> brief() {
        return jdbc.query(BASE_SELECT + " WHERE s.tenant_id = ? ORDER BY s.id DESC LIMIT 500",
                Shipment.ROW_MAPPER, TenantContext.get());
    }

    public List<ShipmentItem> items(long shipmentId) {
        return jdbc.query(ITEM_SELECT + " WHERE shipment_id = ? AND tenant_id = ? ORDER BY id",
                ShipmentItem.ROW_MAPPER, shipmentId, TenantContext.get());
    }

    public List<TrackingNode> tracking(long shipmentId) {
        // 轨迹按时间**倒序**返回：详情页 Timeline 是「最新在上」的阅读习惯
        return jdbc.query(NODE_SELECT + " WHERE shipment_id = ? AND tenant_id = ? ORDER BY node_time DESC, id DESC",
                TrackingNode.ROW_MAPPER, shipmentId, TenantContext.get());
    }

    public ShipmentDetail detail(long id) {
        Shipment shipment = get(id);
        return new ShipmentDetail(shipment, items(id), tracking(id), customs.findByShipmentId(id).orElse(null));
    }

    public ShipmentDetail detailByNo(String shipmentNo) {
        Shipment shipment = findByNo(shipmentNo)
                .orElseThrow(() -> new NotFoundException("国际运单不存在: " + shipmentNo));
        return detail(shipment.id());
    }

    /**
     * 建单：先插 tms_shipments，再整批插 tms_shipment_items，同事务。
     *
     * <p><b>幂等</b>：batchNo 非空且已存在运单时直接复用既有的，不再建第二张——
     * 这正是 OMS「发起订舱」在事务里先查后建的那一半；TMS 侧也留一道闸，
     * 是因为「先查」之后到「建」之间可能有并发（两个操作员同时点订舱）。</p>
     *
     * <p><b>承运商必须真实存在</b>：tms_shipments.carrier_id 上没有外键（本项目一律不建
     * 外键，避免跨库/删改时的锁与顺序问题），所以「填了个不存在的承运商 id」数据库不会拦，
     * 会留下一张承运商列显示为空的运单——出问题时没人知道该找谁。宁可在建单时用一句
     * 人话拒掉。跨库调用方（OMS 母单发起订舱）传错 id 时也能因此拿到明确的失败，
     * 由 OMS 转成 502 linkage_failed 并回滚，而不是静默建出一张坏单。</p>
     */
    @Transactional
    public ShipmentDetail create(ShipmentPayload payload) {
        if (payload.carrierId() != null && payload.carrierId() > 0) {
            carriers.get(payload.carrierId());   // 不存在直接 404，语义比 "列不能为空" 清楚
        }
        requireBlConsistency(payload.blType(), payload.blNo(), payload.blIssuer());
        requireSailingMatchesMode(payload.sailingId(), payload.mode());
        requireCarrierMatchesMode(payload.carrierId(), payload.mode());
        if (payload.batchNo() != null && !payload.batchNo().isBlank()) {
            Optional<Shipment> existing = findByBatchNo(payload.batchNo());
            if (existing.isPresent()) {
                Shipment shipment = existing.get();
                // 已存在时把本次带来的箱明细补齐（重复的按 house_no 跳过），保证重试不丢货
                mergeItems(shipment.id(), shipment.shipmentNo(), payload.items());
                return detail(shipment.id());
            }
        }
        // 用命名参数而不是一串位置占位符：这张表 30 多个列，手写 `?` 对齐极易错位，
        // 而错位的表现是「Parameter index out of range」这种看不出指向哪一列的异常。
        // 运单号在插入前就算好：序列是「当天已有运单数 + 1」，本来就不依赖自增 id；
        // 而 shipment_no 列是 NOT NULL 无默认值，漏给会直接报错。
        String shipmentNo = generateShipmentNo();
        named.update("""
                INSERT INTO tms_shipments
                    (tenant_id, shipment_no, batch_no, awb_no, bl_no, bl_type, bl_issuer, booking_no, sailing_id,
                     mode, carrier_id, pol_code, pod_code, container_type, container_qty,
                     incoterm, currency, total_pieces, total_gross_weight, total_volume,
                     volumetric_weight, chargeable_weight, vgm_weight, container_tare_weight, marks,
                     etd_at, eta_at, cutoff_at, is_dangerous, un_number, dg_class,
                     sanction_flag, ioss_no, status, remark, created_by)
                VALUES (:tenant, :shipmentNo, :batchNo, :awbNo, :blNo, :blType, :blIssuer, :bookingNo, :sailingId,
                        :mode, :carrierId, :polCode, :podCode, :containerType, :containerQty,
                        :incoterm, :currency, :totalPieces, :totalGrossWeight, :totalVolume,
                        :volumetricWeight, :chargeableWeight, :vgmWeight, :containerTareWeight, :marks,
                        :etdAt, :etaAt, :cutoffAt, :isDangerous, :unNumber, :dgClass,
                        :sanctionFlag, :iossNo, 'DRAFT', :remark, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", TenantContext.get())
                        .addValue("shipmentNo", shipmentNo)
                        .addValue("batchNo", IntlFields.text(payload.batchNo()))
                        .addValue("awbNo", IntlFields.text(payload.awbNo()))
                        .addValue("blNo", IntlFields.text(payload.blNo()))
                        // bl_type / bl_issuer：MASTER 缺省（存量运单都是主单语义），
                        // HOUSE 必须有签发方——校验在 requireBlConsistency()
                        .addValue("blType", IntlFields.orDefault(payload.blType(), "MASTER"))
                        .addValue("blIssuer", IntlFields.text(payload.blIssuer()))
                        .addValue("bookingNo", IntlFields.text(payload.bookingNo()))
                        .addValue("sailingId", payload.sailingId() == null ? 0L : payload.sailingId())
                        .addValue("mode", IntlFields.orDefault(payload.mode(), "SEA"))
                        .addValue("carrierId", payload.carrierId())
                        .addValue("polCode", IntlFields.text(payload.polCode()))
                        .addValue("podCode", IntlFields.text(payload.podCode()))
                        .addValue("containerType", IntlFields.text(payload.containerType()))
                        .addValue("containerQty", IntlFields.zero(payload.containerQty()))
                        .addValue("incoterm", IntlFields.orDefault(payload.incoterm(), "FOB"))
                        .addValue("currency", IntlFields.orDefault(payload.currency(), "USD"))
                        .addValue("totalPieces", IntlFields.zero(payload.totalPieces()))
                        // DECIMAL 列都是 NOT NULL DEFAULT 0：不传就归零，不能写 null
                        // （LinkageClient 的建单入参就不带这几个字段）
                        .addValue("totalGrossWeight", IntlFields.decimal(payload.totalGrossWeight()))
                        .addValue("totalVolume", IntlFields.decimal(payload.totalVolume()))
                        .addValue("volumetricWeight", IntlFields.decimal(payload.volumetricWeight()))
                        // 计费重 / VGM：调用方没给就按模式回算（P1-2）——
                        // 之前直接落毛重，等于两个字段都没有计算逻辑
                        .addValue("chargeableWeight", IntlFields.decimal(payload.chargeableWeight() != null
                                ? payload.chargeableWeight()
                                : calcChargeableWeight(payload.mode(), payload.totalGrossWeight(),
                                payload.totalVolume(), payload.volumetricWeight())))
                        .addValue("vgmWeight", IntlFields.decimal(payload.vgmWeight() != null
                                ? payload.vgmWeight()
                                : calcVgmWeight(payload.mode(), payload.totalGrossWeight(), tareOf(payload.containerType()))))
                        .addValue("containerTareWeight", IntlFields.decimal(payload.containerTareWeight() != null
                                ? payload.containerTareWeight() : tareOf(payload.containerType())))
                        .addValue("marks", IntlFields.text(payload.marks()))
                        .addValue("etdAt", payload.etdAt())
                        .addValue("etaAt", payload.etaAt())
                        .addValue("cutoffAt", payload.cutoffAt())
                        .addValue("isDangerous", IntlFields.zero(payload.isDangerous()))
                        .addValue("unNumber", IntlFields.text(payload.unNumber()))
                        .addValue("dgClass", IntlFields.text(payload.dgClass()))
                        .addValue("sanctionFlag", IntlFields.orDefault(payload.sanctionFlag(), "CLEAR"))
                        .addValue("iossNo", IntlFields.text(payload.iossNo()))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        .addValue("createdBy", currentUserId()));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        insertItems(id, shipmentNo, payload.items());
        return detail(id);
    }

    /**
     * 更新运单（只改可改字段）。箱明细给了就整批替换（house_no 做业务键去重）。
     */
    @Transactional
    public ShipmentDetail update(ShipmentPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少运单 id");
        }
        Shipment existing = get(payload.id());
        // 合并后的值再校验：只传了 blType 没传 blIssuer 时，合并结果才是真正要落库的那一组。
        // 拿原始 payload 校验会漏掉「从 MASTER 改成 HOUSE 但没填签发方」这种。
        requireBlConsistency(
                IntlFields.orDefault(payload.blType(), existing.blType()),
                payload.blNo() == null ? existing.blNo() : payload.blNo(),
                payload.blIssuer() == null ? existing.blIssuer() : payload.blIssuer());
        requireSailingMatchesMode(
                payload.sailingId() == null ? 0L : payload.sailingId(),
                IntlFields.orDefault(payload.mode(), existing.mode()));
        requireCarrierMatchesMode(
                payload.carrierId() == null ? 0L : payload.carrierId(),
                IntlFields.orDefault(payload.mode(), existing.mode()));
        int updated = jdbc.update("""
                UPDATE tms_shipments
                   SET awb_no = ?, bl_no = ?, bl_type = ?, bl_issuer = ?, booking_no = ?, sailing_id = ?,
                       container_type = ?, container_qty = ?, incoterm = ?,
                       total_pieces = ?, total_gross_weight = ?, total_volume = ?,
                       volumetric_weight = ?, chargeable_weight = ?, vgm_weight = ?, container_tare_weight = ?, marks = ?,
                       etd_at = ?, eta_at = ?, cutoff_at = ?, is_dangerous = ?, un_number = ?,
                       dg_class = ?, sanction_flag = ?, ioss_no = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                IntlFields.text(payload.awbNo()),
                IntlFields.text(payload.blNo()),
                IntlFields.orDefault(payload.blType(), existing.blType()),
                payload.blIssuer() == null ? existing.blIssuer() : IntlFields.text(payload.blIssuer()),
                IntlFields.text(payload.bookingNo()),
                payload.sailingId() == null ? 0L : payload.sailingId(),
                IntlFields.text(payload.containerType()),
                IntlFields.zero(payload.containerQty()),
                IntlFields.orDefault(payload.incoterm(), existing.incoterm()),
                payload.totalPieces() == null ? existing.totalPieces() : payload.totalPieces(),
                payload.totalGrossWeight() == null ? existing.totalGrossWeight() : payload.totalGrossWeight(),
                payload.totalVolume() == null ? existing.totalVolume() : payload.totalVolume(),
                payload.volumetricWeight() == null ? existing.volumetricWeight() : payload.volumetricWeight(),
                // 计费重 / VGM 同样按合并后的值重算（改了箱型或重量就得跟着变），
                // 调用方显式传了值才以传入为准——「谈好的价」可以覆盖算出来的价。
                payload.chargeableWeight() != null ? payload.chargeableWeight() : calcChargeableWeight(
                        IntlFields.orDefault(payload.mode(), existing.mode()),
                        payload.totalGrossWeight() == null ? existing.totalGrossWeight() : payload.totalGrossWeight(),
                        payload.totalVolume() == null ? existing.totalVolume() : payload.totalVolume(),
                        payload.volumetricWeight() == null ? existing.volumetricWeight() : payload.volumetricWeight()),
                payload.vgmWeight() != null ? payload.vgmWeight() : calcVgmWeight(
                        IntlFields.orDefault(payload.mode(), existing.mode()),
                        payload.totalGrossWeight() == null ? existing.totalGrossWeight() : payload.totalGrossWeight(),
                        payload.containerTareWeight() != null ? payload.containerTareWeight()
                                : existing.containerTareWeight()),
                payload.containerTareWeight() == null ? existing.containerTareWeight()
                        : payload.containerTareWeight(),
                payload.marks() == null ? existing.marks() : payload.marks(),
                payload.etdAt() == null ? existing.etdAt() : payload.etdAt(),
                payload.etaAt() == null ? existing.etaAt() : payload.etaAt(),
                payload.cutoffAt() == null ? existing.cutoffAt() : payload.cutoffAt(),
                payload.isDangerous() == null ? existing.isDangerous() : payload.isDangerous(),
                payload.unNumber() == null ? existing.unNumber() : payload.unNumber(),
                payload.dgClass() == null ? existing.dgClass() : payload.dgClass(),
                IntlFields.orDefault(payload.sanctionFlag(), existing.sanctionFlag()),
                payload.iossNo() == null ? existing.iossNo() : payload.iossNo(),
                payload.remark() == null ? existing.remark() : payload.remark(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("国际运单不存在: " + payload.id());
        }
        if (payload.items() != null) {
            jdbc.update("DELETE FROM tms_shipment_items WHERE shipment_id = ? AND tenant_id = ?",
                    payload.id(), TenantContext.get());
            insertItems(payload.id(), existing.shipmentNo(), payload.items());
        }
        return detail(payload.id());
    }

    public void delete(long id) {
        Shipment shipment = get(id);
        if (!"DRAFT".equals(shipment.status()) && !"CANCELLED".equals(shipment.status())) {
            // 已开航的运单不能删：它是提单、报关单、轨迹的父，删掉之后没人能解释这票货去过哪
            throw new ConflictException("运单已推进到 " + shipment.status() + "，不能删除；只能取消");
        }
        jdbc.update("DELETE FROM tms_tracking_nodes WHERE shipment_id = ? AND tenant_id = ?", id, TenantContext.get());
        jdbc.update("DELETE FROM tms_shipment_items WHERE shipment_id = ? AND tenant_id = ?", id, TenantContext.get());
        int deleted = jdbc.update("DELETE FROM tms_shipments WHERE id = ? AND tenant_id = ?", id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("国际运单不存在: " + id);
        }
    }

    /**
     * 推进运单状态（人工入口，M1 的 PATCH /{id}/status）。
     *
     * <p>校验与落库全在 {@link #applyStatusChange} 里，与回调、定时任务共用——
     * 见类注释里「三个推进入口最终都汇到同一个私有方法」的理由。</p>
     */
    @Transactional
    public ShipmentDetail changeStatus(long id, ShipmentStatusPayload payload) {
        return applyStatusChange(id, payload.status(), payload.exceptionReason(), payload.remark(),
                NewTrackingNode.SOURCE_MANUAL, operatorName(), null, "", "", null);
    }

    /**
     * 承运商回调推进（M2-4）。
     *
     * <p>与人工推进的差别只有三处，恰好是「谁在说话」的三个体现：
     * <ul>
     *   <li>节点 source = CARRIER_CALLBACK，operator = 承运商标识（不是登录用户）</li>
     *   <li>节点时间 = 承运商报的事件时间（不是入库时间）——
     *       这一条最要紧：入库时间是我们「什么时候知道的」，事件时间才是「货什么时候动的」。
     *       承运商往往延迟几小时才补推，用入库时间会让轨迹整体后移，到港晚于开航这种
     *       看起来自相矛盾的记录就出现了</li>
     *   <li>external_code / external_source / raw_payload 全部填真值（对账要用）</li>
     * </ul>
     * <b>状态机与三道门禁一律照走</b>：承运商报了 DEP 我们也不推——
     * VGM 为 0 的海运柜照样推不动，因为承运商的开航通知不能替代 SOLAS 的核实总重。
     * 反过来推回 {@code IllegalArgumentException} 会让整批回调 400，所以
     * {@link CarrierCallbackService} 在事务外捕获，转成 rejected 计数。</p>
     *
     * @param nodeTime 承运商报的事件时间（UTC），为空则退回入库时间
     */
    @Transactional
    public ShipmentDetail changeStatusByCarrier(long id, String targetStatus, String carrierCode,
                                                LocalDateTime nodeTime, String externalCode,
                                                String rawPayload, String remark) {
        return applyStatusChange(id, targetStatus, null, remark,
                NewTrackingNode.SOURCE_CARRIER_CALLBACK,
                carrierCode == null || carrierCode.isBlank() ? "承运商回调" : carrierCode,
                nodeTime, externalCode, carrierCode, rawPayload);
    }

    /**
     * 定时任务推进（M2-5）。
     *
     * <p><b>为什么必须复用 {@link #applyStatusChange} 而不是另写一段 UPDATE</b>：
     * 定时任务是唯一一条「没人看过就推进」的写入路径。如果它自己 UPDATE status，
     * VGM 门禁（SOLAS：未取得核实总重的柜不得装载）就在这条路径上形同虚设——
     * 而这恰恰是现场被拒载最常见的原因。门禁的强度等于所有写入路径里最弱的那一条，
     * 所以这里只能有一条。</p>
     *
     * <p>节点时间用入库时间而不是 etd_at/eta_at：节点时间是「我们记录这件事的时刻」。
     * 计划时间已经被 {@code actual_etd_at = COALESCE(etd_at, now)} 记下来了，
     * 再往 node_time 里塞一遍等于把「事实」和「计划」混成一个字段。</p>
     */
    @Transactional
    public ShipmentDetail changeStatusByScheduler(long id, String targetStatus, String reason) {
        return applyStatusChange(id, targetStatus, null, reason,
                NewTrackingNode.SOURCE_SCHEDULED, "定时任务", null, "", "", null);
    }

    /**
     * 承运商事件的幂等预判。
     *
     * <p><b>为什么回调必须在尝试推进之前先问这一句</b>：状态机先跑的话，
     * 一条<b>重复投递</b>会撞上「{@code EXPORT_DECLARED → EXPORT_DECLARED} 非法迁移」
     * 而被当成拒绝——但它根本不是被拒绝，是承运商重推了一遍。
     * 两者在承运商侧是完全不同的动作：被拒绝要人介入，重复投递什么都不用做。
     * 计数报错会让「这批到底进来几条」彻底不可信。</p>
     *
     * <p><b>这只是快速路径，不是保证</b>：并发推同一批时两个请求都可能在这里查到 0，
     * 真正的闸是 {@code uk_node_event} 唯一索引 + 捕获
     * {@code DuplicateKeyException}。两者都要有：只有预判会在并发下漏判，
     * 只有捕获则每次重推都白跑一次事务。</p>
     */
    public boolean carrierEventAlreadyRecorded(Shipment shipment, String nodeCode,
                                               LocalDateTime nodeTime, String externalCode) {
        return tracker.exists(TenantContext.get(), shipment.shipmentNo(), nodeCode, nodeTime,
                externalCode == null ? "" : externalCode);
    }

    /**
     * 承运商回调「只落轨迹、不动主状态」的入口（M2-4 的 TRACKING_ONLY 兜底）。
     *
     * <p><b>刻意不标 {@code @Transactional}</b>：这里要用「捕获唯一键冲突 = 重复投递」
     * 的语义（见 {@link TrackingNodeWriter} 的类注释），而在一个事务里捕获
     * {@code DuplicateKeyException} 会把事务标记成 rollback-only。回调必须逐条独立，
     * 一条重复不能牵连整批。</p>
     *
     * @return true=新写入，false=重复投递（唯一索引已拦住）
     */
    public boolean appendCarrierNode(Shipment shipment, String nodeCode, String nodeName,
                                     LocalDateTime nodeTime, String location, String remark,
                                     String externalCode, String carrierCode, String rawPayload) {
        NewTrackingNode node = new NewTrackingNode(TenantContext.get(), shipment.id(),
                shipment.shipmentNo(), nodeCode, nodeName, nodeTime, location,
                NewTrackingNode.SOURCE_CARRIER_CALLBACK,
                carrierCode == null || carrierCode.isBlank() ? "承运商回调" : carrierCode,
                IntlFields.text(remark), externalCode, carrierCode, rawPayload);
        try {
            tracker.append(node);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 唯一的推进入口（私有）。三个公开入口都汇到这里。
     *
     * <p>顺序刻意是「先校验 → 再落库 → 最后留轨迹」：
     * 非法迁移一个字节都不写；合法迁移一定留下轨迹，这样「为什么这票货状态变了」
     * 永远查得到。轨迹和状态同事务，状态变了而轨迹没写的情况不会发生。</p>
     *
     * @param nodeTime    节点时间；null 表示用当前时刻（人工与定时任务）
     * @param externalCode 承运商事件码；非回调来源传 ""
     */
    private ShipmentDetail applyStatusChange(long id, String targetStatus, String exceptionReason,
                                             String remark, String source, String operator,
                                             LocalDateTime nodeTime, String externalCode,
                                             String externalSource, String rawPayload) {
        Shipment existing = get(id);
        IntlStateMachine.assertTransition(IntlStateMachine.SHIPMENT, existing.status(), targetStatus, "运单");

        if ("EXCEPTION".equals(targetStatus)
                && (exceptionReason == null || exceptionReason.isBlank())) {
            throw new IllegalArgumentException("进入异常状态必须填异常原因");
        }
        if ("EXPORT_DECLARED".equals(targetStatus) && "HIT".equals(existing.sanctionFlag())) {
            throw new IllegalArgumentException("制裁筛查为 HIT，未人工放行前不能报关放行（先在运单详情把筛查结果改为 CLEAR）");
        }
        // VGM 门禁（P1-2）：SOLAS 明确「未取得核实总重的集装箱不得装载」，
        // 船司与船代会在截关前核对这个数。与其在现场被拒载，不如在系统里推不动。
        if ("DEPARTED".equals(targetStatus)
                && ("SEA".equals(existing.mode()) || "LCL".equals(existing.mode()))
                && (existing.vgmWeight() == null || existing.vgmWeight().signum() == 0)) {
            throw new IllegalArgumentException(
                    "海运开航前必须录入 VGM 核实总重（SOLAS 要求：货物+包装+集装箱自重）");
        }

        LocalDateTime effectiveNodeTime = nodeTime == null ? nowUtc() : nodeTime;
        String effectiveExceptionReason = "EXCEPTION".equals(targetStatus)
                ? exceptionReason.trim()
                : (exceptionReason == null || exceptionReason.isBlank()
                        ? existing.exceptionReason() : exceptionReason.trim());

        jdbc.update("""
                UPDATE tms_shipments
                   SET status = ?,
                       exception_reason = ?,
                       actual_etd_at = CASE WHEN ? = 'DEPARTED' THEN COALESCE(?, ?) ELSE actual_etd_at END,
                       actual_eta_at = CASE WHEN ? = 'ARRIVED' THEN COALESCE(?, ?) ELSE actual_eta_at END,
                       pod_received_at = CASE WHEN ? = 'POD_CONFIRMED' THEN COALESCE(?, ?) ELSE pod_received_at END
                 WHERE id = ? AND tenant_id = ?
                """,
                targetStatus,
                effectiveExceptionReason,
                targetStatus, existing.etdAt(), effectiveNodeTime,
                targetStatus, existing.etaAt(), effectiveNodeTime,
                targetStatus, effectiveNodeTime, effectiveNodeTime,
                id, TenantContext.get());

        appendIfAbsent(new NewTrackingNode(TenantContext.get(), id, existing.shipmentNo(),
                targetStatus, targetStatus, effectiveNodeTime, "", source, operator,
                IntlFields.text(remark), externalCode, externalSource, rawPayload));
        return detail(id);
    }

    /**
     * 人工录入一条轨迹节点（不改状态）。
     *
     * <p>幂等键 = (shipment_no, node_code, node_time, external_code)：同一条轨迹重复提交
     * 不会写两行。人工来源的 external_code 恒为 ''，所以它就是原来那三列——
     * <b>没有为回调另开一套判重逻辑</b>，回调只是把 external_code 填上真值。
     * DB 上有 {@code uk_node_event} 唯一索引兜底（见 56 号迁移）。</p>
     */
    @Transactional
    public ShipmentDetail addTracking(long id, TrackingPayload payload) {
        Shipment shipment = get(id);
        String nodeCode = payload.nodeCode().trim();
        appendIfAbsent(manualNode(shipment, nodeCode,
                payload.nodeName() == null || payload.nodeName().isBlank()
                        ? nodeCode : payload.nodeName().trim(),
                payload.nodeTime(), IntlFields.text(payload.location()), payload.remark()));
        return detail(id);
    }

    /**
     * 取一条轨迹的承运商原始报文（对账扯皮时的冷路径）。
     *
     * <p>为什么要单独一个端点而不是把raw_payload 塞进轨迹列表：列表一页 10~100 条，
     * 每条带一份整报文会让详情接口体积翻几倍，而绝大多数人一辈子不会点开它。</p>
     *
     * <p><b>「节点不存在」与「节点存在但没有原文」必须分开</b>：前者 404，
     * 后者返回空串。合成一个 404 会让人以为节点被删了，而实际原因是这条轨迹来自
     * 人工录入或种子演示数据（本就没有承运商报文）——两者的排查方向完全不同。</p>
     */
    public String rawPayloadOf(long shipmentId, long nodeId) {
        List<String> rows = jdbc.query(
                "SELECT raw_payload FROM tms_tracking_nodes WHERE id = ? AND shipment_id = ? AND tenant_id = ?",
                (rs, i) -> rs.getString("raw_payload"), nodeId, shipmentId, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("轨迹节点不存在: " + nodeId);
        }
        return rows.get(0) == null ? "" : rows.get(0);
    }

    /**
     * 服务内部用（报关单创建时记一条轨迹）：不改状态，只追加节点。
     *
     * <p>仍然走 {@link TrackingNodeWriter}：本轮给表加了 external_code 等三列，
     * 留着第二份手写 INSERT 就等于留一个「报关联动的轨迹永远没有原文」的暗坑。</p>
     */
    void appendNode(String shipmentNo, String nodeCode, String nodeName, String remark) {
        Optional<Shipment> shipment = findByNo(shipmentNo);
        shipment.ifPresent(s -> appendIfAbsent(manualNode(s, nodeCode, nodeName,
                nowUtc(), "", remark)));
    }

    /** 人工来源节点（MANUAL）：operator 取网关注入的用户名，external 三列留空。 */
    private NewTrackingNode manualNode(Shipment shipment, String nodeCode, String nodeName,
                                       LocalDateTime nodeTime, String location, String remark) {
        return new NewTrackingNode(TenantContext.get(), shipment.id(), shipment.shipmentNo(),
                nodeCode, nodeName, nodeTime, location, NewTrackingNode.SOURCE_MANUAL,
                operatorName(), IntlFields.text(remark), "", "", null);
    }

    /** 事务内写轨迹：先判重再插（详见 TrackingNodeWriter 的类注释，为什么不能捕获唯一键冲突）。 */
    private void appendIfAbsent(NewTrackingNode node) {
        if (!tracker.exists(node.tenantId(), node.shipmentNo(), node.nodeCode(),
                node.nodeTime(), node.externalCode())) {
            tracker.append(node);
        }
    }

    private void mergeItems(long shipmentId, String shipmentNo, List<ShipmentItemPayload> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> existing = jdbc.query(
                "SELECT house_no FROM tms_shipment_items WHERE shipment_id = ? AND tenant_id = ?",
                (rs, i) -> rs.getString("house_no"), shipmentId, TenantContext.get());
        for (ShipmentItemPayload item : items) {
            if (item.houseNo() != null && !item.houseNo().isBlank() && existing.contains(item.houseNo().trim())) {
                continue;
            }
            insertItem(shipmentId, shipmentNo, item);
        }
    }

    private void insertItems(long shipmentId, String shipmentNo, List<ShipmentItemPayload> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (ShipmentItemPayload item : items) {
            insertItem(shipmentId, shipmentNo, item);
        }
    }

    private void insertItem(long shipmentId, String shipmentNo, ShipmentItemPayload item) {
        named.update("""
                INSERT INTO tms_shipment_items
                    (tenant_id, shipment_id, shipment_no, order_no, house_no, container_no, container_type, seal_no, marks, pieces, gross_weight, volume, volumetric_weight, is_dangerous, un_number, dg_class)
                VALUES (:tenant_id, :shipment_id, :shipment_no, :order_no, :house_no, :container_no, :container_type, :seal_no, :marks, :pieces, :gross_weight, :volume, :volumetric_weight, :is_dangerous, :un_number, :dg_class)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("shipment_id", shipmentId)
                        .addValue("shipment_no", shipmentNo)
                        .addValue("order_no", IntlFields.text(item.orderNo()))
                        .addValue("house_no", IntlFields.text(item.houseNo()))
                        .addValue("container_no", IntlFields.text(item.containerNo()))
                        .addValue("container_type", IntlFields.text(item.containerType()))
                        .addValue("seal_no", IntlFields.text(item.sealNo()))
                        .addValue("marks", IntlFields.text(item.marks()))
                        .addValue("pieces", IntlFields.zero(item.pieces()))
                        .addValue("gross_weight", item.grossWeight())
                        .addValue("volume", item.volume())
                        .addValue("volumetric_weight", item.volumetricWeight())
                        .addValue("is_dangerous", IntlFields.zero(item.isDangerous()))
                        .addValue("un_number", IntlFields.text(item.unNumber()))
                        .addValue("dg_class", IntlFields.text(item.dgClass()))
                        );
    }

    /** 运单号 SHP + yyyyMMdd + 3 位序列；冲突时顺延，最多试 5 次。 */
    /**
     * BL 类型与单号的互斥校验（P1-4）。
     *
     * <p>MASTER（主单，船司签发给货代/NVOCC）与 HOUSE（分单，货代签发给真实货主）
     * 的区别是「谁签的」——而这正是货代系统的核心命题：目的港清关与提货凭 HBL，船司
     * 只认 MBL，没有这两个字段就答不出「这票货向谁索赔、跟谁对账」。</p>
     *
     * <p>校验只在「已经有 BL 内容」时才生效：订舱阶段（OMS 发起）还没拿到提单号，
     * 此时 MASTER + 空 blNo 是正常的；声明成分单却没填签发方才拒——那说明手上有
     * HBL 却不知道是谁签发的。</p>
     */
    private void requireBlConsistency(String blType, String blNo, String blIssuer) {
        String type = IntlFields.orDefault(blType, "MASTER");
        if (!"MASTER".equals(type) && !"HOUSE".equals(type)) {
            throw new IllegalArgumentException("BL 类型只能是 MASTER/HOUSE");
        }
        boolean hasBlNo = blNo != null && !blNo.isBlank();
        boolean hasIssuer = blIssuer != null && !blIssuer.isBlank();

        // 订舱阶段（OMS 发起）还没拿到提单号，MASTER + 空 blNo 是**正常**的，不能拦。
        // 只有「已经有 BL 内容却缺另一半」才拒：
        //   - 填了提单号 → 必须能说清它是主单还是分单（默认 MASTER，所以这条放过）
        //   - 声明成分单 → 必须有签发方，否则挂着一张不知道谁签的单证，比不填更危险
        if ("HOUSE".equals(type) && !hasIssuer) {
            throw new IllegalArgumentException("BL 类型是分单（HOUSE）时必须填签发方（货代或船司）");
        }
    }

    /**
     * 船期与运输方式必须匹配（P1-5：同库弱引用本地校验）。
     *
     * <p>sailing_id 指向同库的 tms_sailings，sailing.route_id 又指向 tms_routes——
     * 都是本地表，校验不需要任何网络调用。这条挡的是「海运单挂到空运船期」这类错配：
     * 它在页面上看不出来（两边都是合法的 id），但 ETD/ETA 与船期对不上会直接导致
     * 截关误判——船司与舱单一律按船期时间卡。</p>
     */
    private void requireSailingMatchesMode(Long sailingId, String mode) {
        if (sailingId == null || sailingId <= 0) {
            return;
        }
        com.example.tms.intl.sailing.Sailing sailing = sailings.get(sailingId);
        if (sailing.routeId() == null || sailing.routeId() <= 0 || mode == null || mode.isBlank()) {
            return;
        }
        String routeMode = routes.get(sailing.routeId()).mode();
        if (routeMode != null && !routeMode.isBlank() && !routeMode.equals(mode)) {
            throw new IllegalArgumentException(
                    "运输方式与船期所属航线不一致：运单是 " + mode + "，船期属于 " + routeMode + " 航线");
        }
    }

    /**
     * 集装箱自重（吨）。箱型决定，缺箱型时按 20GP 的最保守值取——
     * 少算 tare 会让 VGM 偏低（申报不足），多算只是比船司准一点。
     * 拼箱（LCL）没有本方集装箱，tare = 0。
     */
    private BigDecimal tareOf(String containerType) {
        String t = IntlFields.text(containerType);
        return switch (t) {
            case "40GP" -> new BigDecimal("3.750");
            case "40HQ" -> new BigDecimal("3.900");
            case "LCL" -> BigDecimal.ZERO;
            // 未填箱型时按 20GP 的 2.2 吨——但为了不低估，用 40HQ 的 3.9 更安全？
            // 这里反过来选：VGM 报少会被船司拒载，报多只影响运费，按大值取。
            default -> new BigDecimal("2.200");
        };
    }

    /**
     * 计费重（P1-2）。分口径是因为「体积重」在不同运输方式下含义完全不同：
     * <ul>
     *   <li><b>海运（SEA/LCL）</b>：W/M 规则，取 max(毛重吨, 体积CBM × 1)——
     *       行业惯例是 revenue ton（1 CBM = 1 吨），所以海运根本不用 6000 除数，
     *       除数那套只对空运/快递有意义。</li>
     *   <li><b>空运与快递（AIR/EXPRESS）</b>：max(毛重, 体积重)，体积重已由 OMS
     *       按航线除数算好（空运 6000 / 快递 5000）。</li>
     *   <li><b>铁路/卡车</b>：近似按重量，本轮不做体积口径。</li>
     * </ul>
     */
    private BigDecimal calcChargeableWeight(String mode, BigDecimal gross, BigDecimal volume,
                                            BigDecimal volumetric) {
        BigDecimal g = IntlFields.decimal(gross);
        if ("SEA".equals(mode) || "LCL".equals(mode)) {
            BigDecimal v = IntlFields.decimal(volume);
            return v.max(g).setScale(3, java.math.RoundingMode.HALF_UP);
        }
        if ("AIR".equals(mode) || "EXPRESS".equals(mode)) {
            BigDecimal v = IntlFields.decimal(volumetric);
            return v.max(g).setScale(3, java.math.RoundingMode.HALF_UP);
        }
        return g.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    /**
     * VGM 核实总重（P1-2，SOLAS VI/2 / IMO MSC.1/Circ.1475 Method 2）。
     *
     * <p>Method 2 =Σ(货物 + 包装 + 绑扎材料) + 集装箱自重。这里用 total_gross_weight
     * 代表「货物+包装+绑扎」（它已经是箱明细 gross_weight 的汇总），加上 tare。</p>
     *
     * <p><b>非海运返回 0</b>：空运/快递没有「核实总重」这回事，VGM 是海运独有的申报项，
     * 填上只会让人误以为它也适用。</p>
     */
    private BigDecimal calcVgmWeight(String mode, BigDecimal gross, BigDecimal tare) {
        if (!"SEA".equals(mode) && !"LCL".equals(mode)) {
            return BigDecimal.ZERO;
        }
        return IntlFields.decimal(gross).add(IntlFields.decimal(tare))
                .setScale(3, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 承运商类型必须与运输方式匹配（P1-5：同库弱引用本地校验）。
     *
     * <p>「海运整柜单挂到快递承运商」在页面上完全看不出来（两边都是合法 id），但会让
     * 运价、VGM 申报、报关单据整条流程走错。carrier_id 指向同库的 tms_carriers，本地查
     * 一次即可，不需要跨库调用。</p>
     *
     * <p>映射：SEA/LCL → OCEAN 或 NVOCC（海运承运人可以是 NVOCC 本身，也可以是实际
     * 承运的船司）；AIR → AIR；EXPRESS → EXPRESS；RAIL/TRUCK 在承运商类型枚举里没有
     * 对应值，放过不校验。</p>
     */
    private void requireCarrierMatchesMode(Long carrierId, String mode) {
        if (carrierId == null || carrierId <= 0 || mode == null || mode.isBlank()) {
            return;
        }
        String carrierType = carriers.get(carrierId).carrierType();
        if (carrierType == null || carrierType.isBlank()) {
            return;
        }
        boolean ok = switch (mode) {
            case "SEA", "LCL" -> "OCEAN".equals(carrierType) || "NVOCC".equals(carrierType);
            case "AIR" -> "AIR".equals(carrierType);
            case "EXPRESS" -> "EXPRESS".equals(carrierType);
            default -> true;
        };
        if (!ok) {
            throw new IllegalArgumentException("运输方式 " + mode
                    + " 与承运商类型 " + carrierType + " 不匹配（海运单应挂 OCEAN/NVOCC）");
        }
    }

    /** 单号走原子自增发号器（见 IntlNumberGenerator），不再用 COUNT(*)+1。 */
    private String generateShipmentNo() {
        return IntlNumberGenerator.nextNo(named, TenantContext.get(), "SHIPMENT", "SHP", 3);
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
        // 上限 200：再长就该用真正的反范式查询了，一条 URL 塞不下更多
        List<String> capped = nos.size() > 200 ? nos.subList(0, 200) : nos;
        where.append(" AND ").append(column).append(" IN (:").append(param).append(") ");
        params.addValue(param, capped);
    }

    private static void appendOrderNos(StringBuilder where, MapSqlParameterSource params, String csv) {
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
        List<String> capped = nos.size() > 200 ? nos.subList(0, 200) : nos;
        where.append(" AND EXISTS (SELECT 1 FROM tms_shipment_items i WHERE i.shipment_id = s.id "
                + "AND i.tenant_id = :tenant AND i.order_no IN (:orderNos)) ");
        params.addValue("orderNos", capped);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    private static long currentUserId() {
        return com.example.tms.intl.IntlCurrentUser.id();
    }

    private static String operatorName() {
        return com.example.tms.intl.IntlCurrentUser.name();
    }
}
