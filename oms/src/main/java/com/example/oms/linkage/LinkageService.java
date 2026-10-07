package com.example.oms.linkage;

import com.example.oms.linkage.LinkageClient.PackTaskInfo;
import com.example.oms.linkage.LinkageClient.ShipmentInfo;
import com.example.oms.linkage.LinkageClient.TmsOrderCreate;
import com.example.oms.linkage.LinkageClient.TmsOrderInfo;
import com.example.oms.linkage.LinkageClient.WarehouseInfo;
import com.example.oms.linkage.LinkageClient.WmsRecordCreate;
import com.example.oms.linkage.LinkageClient.WmsRecordInfo;
import com.example.oms.intl.exportbatch.ExportBatch;
import com.example.oms.intl.exportorder.ExportOrder;
import com.example.oms.order.Order;
import com.example.oms.order.OrderDetail;
import com.example.oms.order.OrderItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * OMS/WMS/TMS 三系统按 OMS 订单号关联（39 号方案）。
 *
 * <p><b>发货联动</b>（{@link #ensureLinkage}）：订单状态到 SHIPPED/DONE 时，
 * 自动在 TMS 建一条运单（order_no = OMS 订单号）、在 WMS 按商品聚合建 OUT 出库记录。
 * 全程「先查后建」幂等：半途失败回滚 OMS 状态后重试，不会重复建档。</p>
 *
 * <p><b>列表聚合</b>（{@link #enrich}）：OMS 订单列表/详情的「运单/出库」列由这里
 * 批量查 TMS+WMS 回填（每页 2 次 HTTP）；下游不可用时按来源各自降级为 null，
 * 不挡列表渲染。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkageService {

    private final LinkageClient client;

    /** 订单终态里会触发联动的两个状态。 */
    private static final Set<String> LINKAGE_STATUSES = Set.of("SHIPPED", "DONE");

    /** OMS 状态 → TMS 运单状态。 */
    private static final Map<String, String> TMS_STATUS = Map.of(
            "SHIPPED", "IN_TRANSIT",
            "DONE", "DELIVERED");

    /** TMS OrderPayload 的校验上限，超长会被 400 拒掉，入口先截断。 */
    private static final int TMS_NAME_MAX = 64;
    private static final int TMS_ADDR_MAX = 128;
    private static final int WMS_PRODUCT_MAX = 128;

    /**
     * 幂等补齐 TMS 运单 + WMS 出库记录。非联动状态直接返回。
     *
     * <p>失败一律抛 {@link LinkageException}（调用方在 OMS 事务内，回滚状态变更，
     * 用户重试时靠「先查后建」续上——TMS 先建成功的运单不会被重复建）。</p>
     */
    public void ensureLinkage(OrderDetail order) {
        if (!LINKAGE_STATUSES.contains(order.status())) {
            return;
        }

        Optional<WarehouseInfo> warehouse = client.firstActiveWarehouse();

        // ---- TMS：order_no 即关联键，先查后建 ----
        if (client.findTmsOrder(order.orderNo()).isEmpty()) {
            if (warehouse.isEmpty()) {
                // 没有可用仓库：运单还是要建（发货事实存在），起点用订单地址兜底
                log.warn("OMS 订单 {} 发货联动：WMS 无 ACTIVE 仓库，运单起点用收货地址兜底", order.orderNo());
            }
            String origin = clip(warehouse
                    .map(WarehouseInfo::location)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(order.address()), TMS_ADDR_MAX, "发货地待定");
            String destination = clip(order.address(), TMS_ADDR_MAX,
                    clip(order.customerName(), TMS_ADDR_MAX, "收货地址待补"));
            client.createTmsOrder(new TmsOrderCreate(
                    order.orderNo(),
                    clip(order.customerName(), TMS_NAME_MAX, "散客"),
                    origin,
                    destination,
                    TMS_STATUS.getOrDefault(order.status(), "IN_TRANSIT"),
                    "OMS订单 " + order.orderNo() + " 发货自动创建"));
        }

        // ---- WMS：按商品聚合建 OUT 记录，按 (orderNo, productName) 补缺 ----
        if (warehouse.isEmpty()) {
            log.warn("OMS 订单 {} 发货联动：WMS 无 ACTIVE 仓库，跳过出库建档", order.orderNo());
            return;
        }
        Set<String> existing = new HashSet<>();
        for (WmsRecordInfo record : client.findOutRecords(order.orderNo())) {
            existing.add(record.productName());
        }
        // 同名商品（不同色码/尺码）合并成一条记录、数量求和，保证出库件数=订单件数
        Map<String, Integer> perProduct = new LinkedHashMap<>();
        for (OrderItem item : order.items()) {
            String name = item.productName();
            if (name == null || name.isBlank() || item.quantity() == null || item.quantity() <= 0) {
                continue;
            }
            perProduct.merge(name.trim(), item.quantity(), Integer::sum);
        }
        long whId = warehouse.get().id();
        for (Map.Entry<String, Integer> entry : perProduct.entrySet()) {
            if (existing.contains(entry.getKey())) {
                continue;
            }
            client.createWmsRecord(new WmsRecordCreate(
                    whId,
                    clip(entry.getKey(), WMS_PRODUCT_MAX, entry.getKey()),
                    "OUT",
                    entry.getValue(),
                    "OMS订单 " + order.orderNo() + " 发货出库",
                    order.orderNo()));
        }
    }

    /**
     * 给订单列表回填运单/出库聚合字段（每页 2 次批量 HTTP）。
     * TMS、WMS 各自故障各自降级——一边挂了另一边的列照常显示。
     */
    public List<Order> enrich(List<Order> orders) {
        if (orders.isEmpty()) {
            return orders;
        }
        List<String> orderNos = orders.stream().map(Order::orderNo).toList();

        Map<String, TmsOrderInfo> tmsByNo = new HashMap<>();
        try {
            for (TmsOrderInfo tms : client.findTmsOrders(orderNos)) {
                if (tms.orderNo() != null) {
                    tmsByNo.put(tms.orderNo(), tms);
                }
            }
        } catch (RuntimeException e) {
            log.warn("订单列表聚合 TMS 运单失败，运单列降级为 —: {}", e.getMessage());
        }

        Map<String, Integer> outQtyByNo = new HashMap<>();
        try {
            for (WmsRecordInfo record : client.findOutRecords(orderNos)) {
                if (record.quantity() == null || record.orderNo() == null) {
                    continue;
                }
                outQtyByNo.merge(record.orderNo(), record.quantity(), Integer::sum);
            }
        } catch (RuntimeException e) {
            log.warn("订单列表聚合 WMS 出库失败，出库列降级为 —: {}", e.getMessage());
        }

        if (tmsByNo.isEmpty() && outQtyByNo.isEmpty()) {
            return orders;
        }
        return orders.stream()
                .map(o -> {
                    TmsOrderInfo tms = tmsByNo.get(o.orderNo());
                    Integer outQty = outQtyByNo.get(o.orderNo());
                    if (tms == null && outQty == null) {
                        return o;
                    }
                    return o.withLinkage(
                            tms == null ? null : tms.status(),
                            tms == null ? null : tms.origin(),
                            tms == null ? null : tms.destination(),
                            outQty);
                })
                .toList();
    }

    /** 单条订单的回填（详情接口用）。 */
    public Order enrichOne(Order order) {
        return enrich(List.of(order)).get(0);
    }

    /** 截断到 max 字符；blank 时兜底 fallback（TMS/WMS 侧 @NotBlank/@Size 的入口防线）。 */
    private static String clip(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    // ====================================================================================
    // 国际跨境物流（M1）
    //
    //   ① ensureShipment：母单「发起订舱」在 **OMS 事务内** 先查后建 TMS 运单；
    //   ② enrichExportOrders：出口订单列表跨库回填运单/备货两列（失败各自降级）。
    //
    // 为什么只有这一条同步写链路：四个库各自独立、没有分布式事务，
    // 多一条双向写就多一处可能打架的地方。TMS→OMS / WMS→OMS 的反向回调放 M2，
    // 且只做非阻塞（失败 log.warn + 列表列降级为 null，见计划 §11 R1）。
    // ====================================================================================

    /**
     * 母单发起订舱：先按 batch_no 查 TMS 是否已有运单，命中就复用，没有才建。
     *
     * <p><b>幂等由两层保证</b>：这里的「先查」，以及 TMS 侧 create() 里再查一次同样的 batch_no。
     * 第二层不是冗余——两个操作员同时点「发起订舱」时两次「先查」都会落空，
     * 靠 TMS 侧那道业务键闸拦下第二张单，才不会出现同一票货两张主单。</p>
     *
     * <p>失败抛 {@link LinkageException}：调用方（ExportBatchService.ship）在
     * OMS 事务内，抛出去即回滚——宁可让母单停在「已确认」让人重试，
     * 也不要留下「母单已订舱但 TMS 没有运单」的半截数据。</p>
     *
     * @return TMS 运单 id 与运单号（已存在时是既有的那张）
     */
    public LinkageClient.ShipmentDetailResult ensureShipment(ShipmentCreateCommand command) {
        List<LinkageClient.ShipmentInfo> existing =
                client.findShipmentsByBatchNos(List.of(command.batchNo()));
        LinkageClient.ShipmentInfo found = existing.stream()
                .filter(s -> command.batchNo().equals(s.batchNo()))
                .findFirst()
                .orElse(null);
        if (found != null) {
            log.info("母单 {} 发起订舱：TMS 已有运单 {}，直接复用", command.batchNo(), found.shipmentNo());
            return new LinkageClient.ShipmentDetailResult(found.id(), found.shipmentNo());
        }

        List<LinkageClient.ShipmentItemCreate> items = new ArrayList<>();
        for (ShipmentItemLine line : command.items()) {
            items.add(new LinkageClient.ShipmentItemCreate(
                    line.orderNo(), line.houseNo(), "", command.containerType(), "",
                    line.marks(), line.pieces(), line.grossWeight(), line.volume(), line.volumetricWeight()));
        }
        LinkageClient.ShipmentDetailResult created = client.createIntlShipment(new LinkageClient.ShipmentCreate(
                command.batchNo(), "", "", "", command.sailingId(), command.mode(), command.carrierId(),
                command.polCode(), command.podCode(), command.containerType(), command.containerQty(),
                command.incoterm(), command.totalPieces(), command.totalGrossWeight(), command.totalVolume(),
                command.etdAt(), command.etaAt(), command.cutoffAt(), command.remark(),
                // 危险品三件套必须透传给 TMS：TMS 库没有子单明细，不传就等于「无危险品」
                command.isDangerous(), command.unNumber(), command.dgClass(), items));
        log.info("母单 {} 发起订舱：已建 TMS 运单 {}", command.batchNo(), created.shipmentNo());
        return created;
    }

    /** 母单发起订舱的命令（Service 之间不互相依赖具体单据类，只传这个 record）。 */
    public record ShipmentCreateCommand(
            String batchNo, String mode, Long carrierId, String polCode, String podCode,
            String containerType, Integer containerQty, String incoterm, Long sailingId,
            Integer totalPieces, java.math.BigDecimal totalGrossWeight, java.math.BigDecimal totalVolume,
            java.time.LocalDateTime etdAt, java.time.LocalDateTime etaAt, java.time.LocalDateTime cutoffAt,
            String remark,
            // 危险品三件套：必须由 OMS 从子单明细汇总后传下来，不能让 TMS 自己猜——
            // TMS 库没有子单明细（跨库），不传就等于「这票没危险品」，报关单与舱单都会漏报
            // （锂电池是海运/空运强制申报项，IATA DGR class 9）。
            // unNumber/dgClass 可能是多个（母单下多票带电），用逗号分隔去重。
            //
            // 类型用 Integer 而非 boolean：TMS 的 ShipmentPayload.isDangerous 是 Integer
            // （与 tms_shipments.is_dangerous 的 TINYINT 对齐），用 boolean 会序列化成
            // JSON true，TMS 反序列化直接报 HttpMessageNotReadableException（502）。
            Integer isDangerous, String unNumber, String dgClass,
            List<ShipmentItemLine> items) {
    }

    /** 一条箱明细（每个出口子单一条：house_no = 母单号-两位序号）。 */
    public record ShipmentItemLine(
            String orderNo, String houseNo, String marks, Integer pieces,
            java.math.BigDecimal grossWeight, java.math.BigDecimal volume,
            java.math.BigDecimal volumetricWeight) {
    }

    /**
     * 给出口订单列表回填运单与备货两列（每页 2 次批量 HTTP）。
     *
     * <p>与 {@link #enrich} 同一个哲学：TMS、WMS 各自故障各自降级——
     * TMS 挂了「运单」列显示 —，WMS 挂了「备货」列显示 —，列表本身照常渲染。
     * 绝不让「下游故障」变成「整个出口订单页打不开」。</p>
     */
    public List<ExportOrder> enrichExportOrders(List<ExportOrder> orders,
                                                Map<String, ExportBatch> batchesByNo) {
        if (orders.isEmpty()) {
            return orders;
        }
        List<String> batchNos = orders.stream()
                .map(ExportOrder::batchNo)
                .filter(nos -> nos != null && !nos.isBlank())
                .distinct()
                .toList();
        List<String> orderNos = orders.stream().map(ExportOrder::orderNo).toList();

        Map<String, ShipmentInfo> shipmentByBatch = new HashMap<>();
        try {
            for (ShipmentInfo shipment : client.findShipmentsByBatchNos(batchNos)) {
                if (shipment.batchNo() != null) {
                    shipmentByBatch.put(shipment.batchNo(), shipment);
                }
            }
        } catch (RuntimeException e) {
            log.warn("出口订单列表聚合 TMS 运单失败，运单列降级为 —: {}", e.getMessage());
        }

        Map<String, PackTaskInfo> packByOrder = new HashMap<>();
        try {
            for (PackTaskInfo task : client.findPackTasksByOrderNos(orderNos)) {
                if (task.orderNo() != null) {
                    packByOrder.put(task.orderNo(), task);
                }
            }
        } catch (RuntimeException e) {
            log.warn("出口订单列表聚合 WMS 备货失败，备货列降级为 —: {}", e.getMessage());
        }

        if (shipmentByBatch.isEmpty() && packByOrder.isEmpty()) {
            return orders;
        }
        return orders.stream()
                .map(order -> {
                    ShipmentInfo shipment = order.batchNo() == null ? null : shipmentByBatch.get(order.batchNo());
                    PackTaskInfo pack = order.orderNo() == null ? null : packByOrder.get(order.orderNo());
                    ExportBatch batch = order.batchNo() == null || batchesByNo == null
                            ? null : batchesByNo.get(order.batchNo());
                    if (shipment == null && pack == null && batch == null) {
                        return order;
                    }
                    return order.withLinkage(
                            batch == null ? null : batch.status(),
                            shipment == null ? null : shipment.shipmentNo(),
                            shipment == null ? null : shipment.status(),
                            shipment == null ? null : firstNonBlank(shipment.awbNo(), shipment.bookingNo()),
                            shipment == null ? null : shipment.bookingNo(),
                            shipment == null ? null : shipment.etdAt(),
                            shipment == null ? null : shipment.etaAt(),
                            pack == null ? null : pack.status(),
                            pack == null ? null : pack.boxNo());
                })
                .toList();
    }

    /** 单条出口订单的回填（详情接口用）。 */
    public ExportOrder enrichExportOrder(ExportOrder order, Map<String, ExportBatch> batchesByNo) {
        return enrichExportOrders(List.of(order), batchesByNo).get(0);
    }

    /** 单条出口订单的运单快照（详情接口的「母单与运单」块）；下游不可用时为 null。 */
    public ShipmentInfo findShipmentByBatchNo(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return null;
        }
        try {
            return client.findShipmentsByBatchNos(List.of(batchNo)).stream()
                    .filter(s -> batchNo.equals(s.batchNo()))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("出口订单详情聚合 TMS 运单失败，运单块降级为 —: {}", e.getMessage());
            return null;
        }
    }

    /** 母单列表的运单列：一次批量 HTTP，失败降级为 null（母单列表本身照常渲染）。 */
    public List<ShipmentInfo> findShipmentsByBatchNos(List<String> batchNos) {
        if (batchNos == null || batchNos.isEmpty()) {
            return List.of();
        }
        return client.findShipmentsByBatchNos(batchNos);
    }

    /** 单条出口订单的备货任务快照；下游不可用时为 null。 */
    public PackTaskInfo findPackTaskByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        try {
            return client.findPackTasksByOrderNos(List.of(orderNo)).stream()
                    .filter(t -> orderNo.equals(t.orderNo()))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("出口订单详情聚合 WMS 备货失败，备货块降级为 —: {}", e.getMessage());
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }
}
