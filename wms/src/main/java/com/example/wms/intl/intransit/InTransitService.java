package com.example.wms.intl.intransit;

import com.example.wms.NotFoundException;
import com.example.wms.intl.IntlFields;
import com.example.wms.intl.IntlPage;
import com.example.wms.intl.IntlStateMachine;
import com.example.wms.tenant.TenantContext;
import java.time.LocalDate;
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
 * 在途库存数据访问。
 *
 * <p><b>M2 起入库会真实联动库存</b>：在途推到 {@code RECEIVED} 时，同一事务内
 * 扣减源仓的 {@code wms_inventory_batches} 并把同样的数量记入目的仓。
 * M1 之所以只做只读登记（不扣库存），是因为库存占用与释放要和「出库联动」
 * 一起做才自洽——只扣入库不扣出库，会出现「货还在宁波仓、洛杉矶仓已经多了一批」
 * 这种双份库存。现在出库侧的联动还没做，但入库侧必须先扣：否则两个账永远对不上
 * （评审 P2-4 记录过在途 2100 / 在库 1280 这种数字）。</p>
 *
 * <p><b>核心不变量：入库后「源仓减少量 == 目的仓增加量」。</b>
 * 扣减与入库记的是同一个 {@code deducted} 变量，所以即使源仓批次库存不足
 * （M1 遗留的数据问题，见 {@link #transferStock}），两边也一定守恒——
 * 账不会凭空多出货物。</p>
 */
@Service
@RequiredArgsConstructor
public class InTransitService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT t.id, t.shipment_no, t.order_no, t.sku, t.product_name, t.batch_no, t.quantity,
                   t.from_warehouse_id, wf.name AS from_warehouse_name,
                   t.to_warehouse_id, wt.name AS to_warehouse_name,
                   t.shipped_at, t.arrived_at, t.status, t.remark, t.created_at, t.updated_at
            FROM wms_in_transit t
            LEFT JOIN wms_warehouses wf ON wf.id = t.from_warehouse_id
            LEFT JOIN wms_warehouses wt ON wt.id = t.to_warehouse_id
            """;

    public IntlPage<InTransit> search(String shipmentNos, String orderNos, String status,
                                      int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND t.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        appendNos(where, params, "t.shipment_no", "shipmentNos", shipmentNos);
        appendNos(where, params, "t.order_no", "orderNos", orderNos);
        if (status != null && !status.isBlank()) {
            where.append(" AND t.status = :status ");
            params.addValue("status", status.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM wms_in_transit t" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<InTransit> list = named.query(
                BASE_SELECT + where + " ORDER BY t.shipped_at DESC, t.id DESC LIMIT :limit OFFSET :offset",
                params, InTransit.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public InTransit get(long id) {
        List<InTransit> rows = jdbc.query(
                BASE_SELECT + " WHERE t.id = ? AND t.tenant_id = ?", InTransit.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("在途记录不存在: " + id);
        }
        return rows.get(0);
    }

    public InTransit create(InTransitPayload payload) {
        named.update("""
                INSERT INTO wms_in_transit
                    (tenant_id, shipment_no, order_no, sku, product_name, batch_no, quantity, from_warehouse_id, to_warehouse_id, shipped_at, status, remark)
                VALUES (:tenant_id, :shipment_no, :order_no, :sku, :product_name, :batch_no, :quantity, :from_warehouse_id, :to_warehouse_id, :shipped_at, 'IN_TRANSIT', :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("shipment_no", payload.shipmentNo().trim())
                        .addValue("order_no", IntlFields.text(payload.orderNo()))
                        .addValue("sku", IntlFields.text(payload.sku()))
                        .addValue("product_name", IntlFields.text(payload.productName()))
                        .addValue("batch_no", IntlFields.text(payload.batchNo()))
                        .addValue("quantity", payload.quantity())
                        .addValue("from_warehouse_id", payload.fromWarehouseId() == null ? 0L : payload.fromWarehouseId())
                        .addValue("to_warehouse_id", payload.toWarehouseId() == null ? 0L : payload.toWarehouseId())
                        .addValue("shipped_at", payload.shippedAt())
                        // remark 之前是入参声明了、落库时静默丢弃的死字段——而
                        // 「这行为什么是 HELD、为什么是 LOST」恰恰只能靠它解释。
                        .addValue("remark", IntlFields.text(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    /**
     * 推进在途状态。**这是 WMS 唯一的在途推进入口**，前端下拉、定时任务都走它。
     *
     * <p>迁移合法性交给 {@link IntlStateMachine#assertInTransit}，不再在这里手写
     * if：过去只挡了「已入库不能再改」和「LOST→LOST 幂等」两种，其余任意状态可跳
     * 任意状态——正好是 {@code IntlStateMachine} 类注释里批评国内陆运运单的那个问题，
     * 而在途是 WMS 唯一的状态推进入口，缺状态机的代价是「已入库的记录被改回在途」。
     * RECEIVED / LOST 是终态且不出现在迁移表的任何 value 里，不用额外写终态判断。</p>
     *
     * <p>LOST→LOST 曾经是「静默返回成功」的幂等特例，现在由状态机以
     * 「非法状态迁移（LOST 是终态）」显式拒绝：重复提交一次状态推进，
     * 静默成功会让人以为操作生效了，而丢件是要走人工对账与理赔结论才能撤销的。</p>
     *
     * <p>加 {@code @Transactional}（对比 {@code ShipmentService.changeStatus} 早就有）：
     * 状态与 arrived_at / to_warehouse_id 必须同生共死，否则会出现
     * 「状态已是 ARRIVED 但 arrived_at 为空」——在途天数算不出来，
     * 「这批货到了没有」也就答不出来。入库扣减也必须在这一个事务里：
     * 状态已是 RECEIVED 但库存没扣，等于货凭空消失。</p>
     *
     * <p><b>入库扣减天然幂等</b>：{@code RECEIVED} 在状态机里是终态
     * （不出现在任何 value 里），重复提交会被 {@code assertInTransit} 以
     * 「非法状态迁移」显式拒掉，所以不存在「同一批货被扣两次」的路径。
     * 不需要额外的「已处理过」标记——加那种标记只会引入第二份真相。</p>
     */
    @Transactional
    public InTransit changeStatus(long id, InTransitStatusPayload payload) {
        InTransit existing = get(id);
        IntlStateMachine.assertInTransit(existing.status(), payload.status());

        long toWarehouseId = payload.toWarehouseId() != null && payload.toWarehouseId() > 0
                ? payload.toWarehouseId()
                : existing.toWarehouseId();
        // 入库要把货记到目的仓，缺目的仓就说不清「这批货入到哪里了」，
        // 此时若继续推进，扣了源仓却不知道加到哪，账就少了一边。
        String remark = existing.remark();
        if ("RECEIVED".equals(payload.status())) {
            remark = transferStock(existing, toWarehouseId);
        }

        jdbc.update("""
                UPDATE wms_in_transit
                   SET status = ?,
                       to_warehouse_id = COALESCE(NULLIF(?, 0), to_warehouse_id),
                       arrived_at = CASE WHEN ? = 'ARRIVED' THEN ? ELSE arrived_at END,
                       remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.status(),
                toWarehouseId,
                payload.status(), nowUtc(),
                remark,
                id, TenantContext.get());
        return get(id);
    }

    /**
     * 入库搬货：源仓批次减、目的仓批次加，返回要写回在途行的说明。
     *
     * <p><b>为什么不写 {@code wms_inventory}（计划 §8.4 原本要求「回写 wms_inventory」）：</b>
     * 国际物流的库存视图只读 {@code wms_inventory_batches}（{@code GET /intl/inventory-batches}）。
     * 入库如果记到 {@code wms_inventory}，页面的「库存批次」Tab 里**看不到刚入库的货**——
     * 界面会说这批货没入库，而数据其实已经入了。**那比不做更糟，它造了一个用户可见的谎言。**
     * 两边都记同一张批次表，「在库 = Σ batches」这个唯一能自检的等式才成立。</p>
     *
     * <p><b>源仓不足时怎么办（这是 M1 遗留的真数据问题）：</b>
     * 种子数据里在途量远大于源仓批次库存（例如 TEE-COT-M-WHT 在途 2100、
     * 批次只有 400），另有三批的源仓根本没有对应批次行。所以入库时有三种可能：
     * <ol>
     *   <li>够扣：按在途量足额扣减与入库。</li>
     *   <li>不够扣：扣「可用量」（在库量 − 占用量，占用中的库存本来就不该发货），
     *       目的仓也只记这个数，缺口写进 remark 等人查。<b>不硬失败</b>——硬失败会让
     *       「已入库」这个动作在演示数据上几乎永远推不动，把数据问题变成功能不可用；
     *       <b>也不静默按在途量入库</b>——那会凭空造出货物。</li>
     *   <li>找不到批次行：同 2 处理，缺口说明里点明「源仓无此批次」。</li>
     * </ol>
     * 无论哪种，「源仓减少量 == 目的仓增加量」都成立（记的是同一个 deducted）。
     * 数据本身的对账问题由 {@code scripts/check_intl_data.sh} 的在途-批次
     * 校验持续报出来，不靠这一行代码顺带解决。</p>
     */
    private String transferStock(InTransit row, long toWarehouseId) {
        if (toWarehouseId <= 0) {
            throw new IllegalArgumentException(
                    "入库必须指定目的仓：本次在途行没有目的仓，传 toWarehouseId 补上（或先登记时填）");
        }
        if (toWarehouseId == row.fromWarehouseId()) {
            throw new IllegalArgumentException("目的仓与发运仓相同，扣了源仓又加回同一个仓，账不动但看起来动了");
        }

        SourceBatch src = lockSourceBatch(row);
        int transitQty = row.quantity();
        int deducted;
        String note;
        if (src == null) {
            deducted = 0;
            note = "【入库缺口】源仓无对应批次（仓 " + row.fromWarehouseId() + " / " + row.sku()
                    + " / 批次 " + (row.batchNo().isEmpty() ? "(空)" : row.batchNo())
                    + "），应扣 " + transitQty + " 实扣 0，需人工补建批次后重算";
        } else {
            // 占用中的库存不该发货，所以可扣量要减掉 reserved_qty；
            // 否则「入库后 quantity < reserved_qty」会打破批次表自己的
            // 「占用不能大于在库」不变量（InventoryBatchService.create 有这个校验）。
            int available = Math.max(0, src.quantity() - src.reservedQty());
            deducted = Math.min(available, transitQty);
            if (deducted > 0) {
                jdbc.update("UPDATE wms_inventory_batches SET quantity = quantity - ? WHERE id = ?",
                        deducted, src.id());
            }
            note = deducted < transitQty
                    ? "【入库缺口】应扣 " + transitQty + " 实扣 " + deducted
                    + "（源仓批次在库 " + src.quantity() + "、占用 " + src.reservedQty()
                    + "，可发 " + available + "），缺口 " + (transitQty - deducted) + " 待对账"
                    : "【入库联动】源仓扣 " + deducted + "，目的仓入 " + deducted;
        }

        if (deducted > 0) {
            creditDestination(row, toWarehouseId, deducted, src);
        }
        return row.remark().isEmpty() ? note : row.remark() + " ｜ " + note;
    }

    /** 源仓批次行加锁读。锁是必需的：读-改-写之间若有并发入库，扣减会算错可用量。 */
    private SourceBatch lockSourceBatch(InTransit row) {
        List<SourceBatch> rows = jdbc.query("""
                SELECT id, quantity, reserved_qty, unit_cost, production_date, expiry_date
                  FROM wms_inventory_batches
                 WHERE tenant_id = ? AND warehouse_id = ? AND sku = ? AND batch_no = ?
                 FOR UPDATE
                """,
                (rs, i) -> new SourceBatch(
                        rs.getLong("id"),
                        rs.getInt("quantity"),
                        rs.getInt("reserved_qty"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getDate("production_date") == null ? null : rs.getDate("production_date").toLocalDate(),
                        rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate()),
                TenantContext.get(), row.fromWarehouseId(), row.sku(), row.batchNo());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 目的仓入账：同 (仓, SKU, 批次) 累加，不存在则建行。
     *
     * <p><b>效期与生产日期必须一起搬</b>：目的仓是海外仓，货到那边还是要按 FEFO
     * 先进先出（效期短的先发）。只在目的仓留数量、丢掉效期，等于给海外仓造了一批
     * 没有效期的货，食品/化妆品这类会直接变成合规问题。</p>
     *
     * <p><b>unit_cost 照搬源仓成本</b>，并明确它<em>不是</em>到岸成本：
     * 运费、关税、保险都没算进来（那属于 M3 的成本引擎）。这里宁可让成本口径
     * 偏小并写清楚，也不要静默写 0——0 成本会让毛利率算出一堆假利润。</p>
     */
    private void creditDestination(InTransit row, long toWarehouseId, int qty, SourceBatch src) {
        String productName = row.productName().isEmpty() ? row.sku() : row.productName();
        int updated = jdbc.update("""
                UPDATE wms_inventory_batches
                   SET quantity = quantity + ?
                 WHERE tenant_id = ? AND warehouse_id = ? AND sku = ? AND batch_no = ?
                """,
                qty, TenantContext.get(), toWarehouseId, row.sku(), row.batchNo());
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO wms_inventory_batches
                        (tenant_id, warehouse_id, sku, product_name, batch_no, production_date, expiry_date,
                         quantity, reserved_qty, unit_cost, location_code, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, '', 'NORMAL')
                    """,
                    TenantContext.get(), toWarehouseId, row.sku(), productName, row.batchNo(),
                    src == null ? null : src.productionDate(),
                    src == null ? null : src.expiryDate(),
                    qty,
                    src == null ? java.math.BigDecimal.ZERO : src.unitCost());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // wms_inventory_batches 的唯一键是 (warehouse_id, sku, batch_no)，**不含 tenant_id**。
            // 所以当另一个租户在同一目的仓已有同 SKU 同批次时，上面那步UPDATE 按 tenant_id
            // 找不到行（这是对的，不能动别人的货），而 INSERT 会被这个唯一键挡下来。
            // 不转成可读错误的话这里只会冒一个 500，操作员只知道「入库失败」，
            // 查半天也想不到自己是撞了另一个租户的批次。
            //
            // 治本是把唯一键改成 (tenant_id, warehouse_id, sku, batch_no)，但那会动到
            // wms_inventory_batches 的既有写入路径，超出本条范围——留作 M2 的账本整改项，
            // 这里先把症状说清楚，不把 schema 债藏在一个 500 后面。
            throw new IllegalStateException(
                    "目的仓 " + toWarehouseId + " 已被另一租户占用同 SKU 同批次（"
                    + row.sku() + " / " + (row.batchNo().isEmpty() ? "(空批次)" : row.batchNo())
                    + "），无法入账：库存批次表的唯一键目前不含租户，属主数据整改项");
        }
    }

    /** 源仓批次行快照。unit_cost 与两个日期是搬去目的仓时要跟着走的字段。 */
    private record SourceBatch(long id, int quantity, int reservedQty,
                               java.math.BigDecimal unitCost,
                               LocalDate productionDate, LocalDate expiryDate) {
    }

    public void delete(long id) {
        get(id);
        int deleted = jdbc.update("DELETE FROM wms_in_transit WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("在途记录不存在: " + id);
        }
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
