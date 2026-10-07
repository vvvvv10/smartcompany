package com.example.tms.intl.callback;

import com.example.tms.intl.IntlFields;
import com.example.tms.intl.shipment.Shipment;
import com.example.tms.intl.shipment.ShipmentService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 承运商回调的批处理（M2-4）。
 *
 * <p><b>逐条独立，绝不整批回滚</b>：承运商一次会推来几十上百条事件，
 * 里面混一条我们不认识的单号是常态（他们的系统里可能有我们从没建过的票）。
 * 整批回滚的结果是「好的那 99 条也丢了」，承运商重推一次，我们再回滚一次——
 * 死循环。所以本类<b>刻意不加 {@code @Transactional}</code></b>：
 * 每条事件自己算 accepted / duplicated / rejected，写失败只影响它自己。</p>
 *
 * <p><b>三个计数的定义（互斥且完备，accepted + duplicated + rejected == events.size()）</b>：
 * <ul>
 *   <li>{@code accepted} —— 落库成功（含「落轨迹但不动主状态」的兜底事件）</li>
 *   <li>{@code duplicated} —— 这条事件已经落过了（幂等键命中唯一索引）</li>
 *   <li>{@code rejected} —— <b>什么都没写</b>：反查不到运单 / 缺事件码 / 缺事件时间 /
 *       被状态机或业务门禁拒绝</li>
 * </ul>
 * 「事件码映射不上」<b>计入 accepted 而不是 rejected</b>：它确实写进库了，
 * 说它 rejected 是不诚实的，会让运维去查一个根本没出错的条目。
 * 想区分的看 {@code detail=TRACKING_ONLY} 与顶层 {@code trackingOnly} 计数。
 * 这一点与评审验收标准 ③ 的关系：③ 要的是「只落轨迹、主状态不变」，
 * 这两件事都在 {@code results} 里可核对，计数口径则是本实现自己定的。</p>
 *
 * <p><b>为什么状态推进必须走 {@link ShipmentService}</b>：本类一行 SQL 都不写。
 * 门禁（制裁 HIT / 海运 VGM=0 / EXCEPTION 必须填原因）的强度等于所有写入路径里
 * 最弱的那一条，这里若另写一段 UPDATE，那些门禁就在承运商回调这条路径上形同虚设。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierCallbackService {

    private final ShipmentService shipments;

    /**
     * 事件级结果。
     *
     * @param index          事件在本批中的下标（从 0 起），运维按它对行定位
     * @param outcome        ACCEPTED / DUPLICATED / REJECTED
     * @param detail         ACCEPTED 且未映射 → {@code TRACKING_ONLY}；
     *                      REJECTED → 原因；DUPLICATED → {@code ALREADY_RECORDED}
     * @param messageReference 该事件反查用的单号（便于一眼看出是哪条没匹配上）
     * @param eventCode      承运商原始事件码
     * @param mapped         是否命中事件码映射表
     * @param nodeCode       实际写入的节点码
     * @param statusFrom     推进前的运单状态
     * @param statusTo       推进后的运单状态（未推进则与 statusFrom 相同）
     * @param reason         人话原因（拒绝原因 / 门禁原文 / 反查失败说明）
     */
    public record EventResult(
            int index,
            String outcome,
            String detail,
            String messageReference,
            String eventCode,
            boolean mapped,
            String nodeCode,
            String statusFrom,
            String statusTo,
            String reason) {
    }

    /**
     * 整批响应。
     *
     * @param messageType       回显的报文类型（让承运商确认我们认得他的格式）
     * @param messageReferenceNumber 回显的报文参考号
     * @param accepted          落库成功条数
     * @param duplicated        幂等命中条数
     * @param rejected          未写入条数
     * @param trackingOnly      其中「只落轨迹未推状态」的条数（accepted 的子集，便于一眼看兜底触发量）
     * @param results           逐条明细，长度 == events.size()
     */
    public record CallbackResponse(
            String messageType,
            String messageReferenceNumber,
            int accepted,
            int duplicated,
            int rejected,
            int trackingOnly,
            List<EventResult> results) {
    }

    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_DUPLICATED = "DUPLICATED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    /** 落轨迹但不推主状态的明细标记。 */
    public static final String DETAIL_TRACKING_ONLY = "TRACKING_ONLY";
    public static final String DETAIL_ALREADY_RECORDED = "ALREADY_RECORDED";

    /**
     * 单批事件数上限：防呆。
     *
     * <p>为什么整批超限要 400 而不是「截断后继续处理」：静默截断会让承运商以为
     * 全部接收成功，而我们悄悄扔掉了后面几千条——下次它不会重推这批，
     * 于是那批货永远不会有状态。与其这样，不如明确失败让它重推或分批。</p>
     */
    private static final int MAX_EVENTS = 2000;

    public CallbackResponse handle(IftstaMessage message) {
        List<IftstaMessage.Event> events = message.events() == null ? List.of() : message.events();
        if (events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("单批事件数超过上限 " + MAX_EVENTS + "，请分批推送");
        }

        List<EventResult> results = new ArrayList<>(events.size());
        int accepted = 0;
        int duplicated = 0;
        int rejected = 0;
        int trackingOnly = 0;

        for (int i = 0; i < events.size(); i++) {
            IftstaMessage.Event event = events.get(i);
            EventResult result = handleOne(i, event, message.rawPayload());
            results.add(result);
            switch (result.outcome()) {
                case OUTCOME_ACCEPTED -> {
                    accepted++;
                    if (DETAIL_TRACKING_ONLY.equals(result.detail())) {
                        trackingOnly++;
                    }
                }
                case OUTCOME_DUPLICATED -> duplicated++;
                default -> rejected++;
            }
        }

        log.info("承运商回调处理完成 报文={} 类型={} 事件={} 落库={} 重复={} 拒绝={} 仅轨迹={}",
                message.messageReferenceNumber(), message.messageType(), events.size(),
                accepted, duplicated, rejected, trackingOnly);

        return new CallbackResponse(message.messageType(), message.messageReferenceNumber(),
                accepted, duplicated, rejected, trackingOnly, results);
    }

    private EventResult handleOne(int index, IftstaMessage.Event event, String rawPayload) {
        String eventCode = IntlFields.text(event.eventCode());
        String reference = IntlFields.text(event.messageReference());
        String documentNo = IntlFields.text(event.transportDocumentNumber());

        if (eventCode.isEmpty()) {
            return reject(index, reference, eventCode, "缺 eventCode（IFTSTA STS C555/4405），无法落轨迹");
        }
        LocalDateTime nodeTime = event.eventDateTime();
        if (nodeTime == null) {
            return reject(index, reference, eventCode,
                    "缺 eventDateTime（IFTSTA DTM C507），轨迹的幂等键与时间线都依赖它");
        }

        Optional<Shipment> found = shipments.findByDocumentNos(List.of(reference, documentNo));
        if (found.isEmpty()) {
            // 反查不到的单号**不能整批失败**：计入 rejected 并说明是哪一条、查的是什么。
            // 这里刻意不抛异常——承运商的系统里必然有我们从没建过的票。
            String tried = (reference.isEmpty() ? "" : "运单号/" + reference)
                    + (documentNo.isEmpty() ? "" : (reference.isEmpty() ? "" : "、") + "提单号/" + documentNo);
            return reject(index, reference, eventCode,
                    "按 " + (tried.isEmpty() ? "(空单号)" : tried) + " 反查不到运单");
        }
        Shipment shipment = found.get();
        String statusFrom = shipment.status();

        Optional<String> target = IftstaEventCodes.toShipmentStatus(eventCode);
        String nodeCode = target.orElseGet(() -> IftstaEventCodes.trackingOnlyNodeCode(eventCode));
        String nodeName = target.map(code -> code)
                .orElseGet(() -> IftstaEventCodes.trackingOnlyNodeName(eventCode, event.eventDescription()));
        String remark = buildRemark(event, target);

        if (target.isPresent()) {
            return applyMapped(index, shipment, statusFrom, target.get(), nodeCode, eventCode,
                    nodeTime, event, rawPayload, remark);
        }
        return applyTrackingOnly(index, shipment, statusFrom, nodeCode, eventCode, nodeTime,
                event, rawPayload, remark);
    }

    /** 事件码命中映射表：走与人工完全相同的状态推进（含状态机与三道门禁）。 */
    private EventResult applyMapped(int index, Shipment shipment, String statusFrom, String targetStatus,
                                    String nodeCode, String eventCode, LocalDateTime nodeTime,
                                    IftstaMessage.Event event, String rawPayload, String remark) {
        // 先判幂等再推状态：重复投递若直接进状态机会撞上「EXPORT_DECLARED→EXPORT_DECLARED
        // 非法迁移」，被误判成「被拒绝」。两者在承运商侧动作完全不同（前者不用管、后者要人介入）。
        if (shipments.carrierEventAlreadyRecorded(shipment, targetStatus, nodeTime, eventCode)) {
            return duplicated(index, shipment.shipmentNo(), eventCode, nodeCode, statusFrom, true);
        }
        String carrierCode = IntlFields.text(event.carrierCode());
        try {
            shipments.changeStatusByCarrier(shipment.id(), targetStatus, carrierCode, nodeTime,
                    eventCode, rawPayload, remark);
            return new EventResult(index, OUTCOME_ACCEPTED, null, shipment.shipmentNo(), eventCode,
                    true, nodeCode, statusFrom, targetStatus, null);
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_node_event 命中：并发推同一批时，两个请求都可能过了上面的预判，
            // 这里由数据库分出胜负。注意捕获是安全的：本类不在事务里，
            // changeStatusByCarrier 的事务已独立回滚（所以不会出现状态被推进两次）。
            return duplicated(index, shipment.shipmentNo(), eventCode, nodeCode, statusFrom, true);
        } catch (RuntimeException e) {
            // 状态机非法迁移 / 制裁 HIT / 海运 VGM 门禁 —— 承运商报了，我们也不推。
            return reject(index, shipment.shipmentNo(), eventCode,
                    "推进到 " + targetStatus + " 被拒（当前 " + statusFrom + "）：" + e.getMessage());
        }
    }

    private EventResult duplicated(int index, String shipmentNo, String eventCode, String nodeCode,
                                   String status, boolean mapped) {
        return new EventResult(index, OUTCOME_DUPLICATED, DETAIL_ALREADY_RECORDED,
                shipmentNo, eventCode, mapped, nodeCode, status, status,
                "该事件已落库（幂等键 shipment_no+node_code+node_time+external_code）");
    }

    /**
     * 事件码映射不上：<b>只落轨迹、不动主状态</b>（评审 M2-4 验收标准 ③）。
     *
     * <p>理由见 {@link IftstaEventCodes} 的类注释：主状态是对外承担法律责任的断言，
     * 不能由「我们不认识的码」驱动。轨迹是只追加的事实记录，落它零成本，
     * 还留住了承运商原文，等将来弄清这个码的含义可以直接补映射重放。</p>
     */
    private EventResult applyTrackingOnly(int index, Shipment shipment, String statusFrom,
                                          String nodeCode, String eventCode, LocalDateTime nodeTime,
                                          IftstaMessage.Event event, String rawPayload, String remark) {
        // 与 applyMapped 同理：先判幂等，避免把重复投递当成「新写了一条」
        if (shipments.carrierEventAlreadyRecorded(shipment, nodeCode, nodeTime, eventCode)) {
            return duplicated(index, shipment.shipmentNo(), eventCode, nodeCode, statusFrom, false);
        }
        boolean inserted = shipments.appendCarrierNode(shipment, nodeCode, nodeName(event, eventCode),
                nodeTime, placeOf(event), remark, eventCode,
                IntlFields.text(event.carrierCode()), rawPayload);
        if (!inserted) {
            return duplicated(index, shipment.shipmentNo(), eventCode, nodeCode, statusFrom, false);
        }
        String reason = IftstaEventCodes.knownButUnmappedReason(eventCode)
                .orElse("事件码 " + eventCode + " 不在映射表内，按 TRACKING_ONLY 兜底只落轨迹");
        return new EventResult(index, OUTCOME_ACCEPTED, DETAIL_TRACKING_ONLY, shipment.shipmentNo(),
                eventCode, false, nodeCode, statusFrom, statusFrom, reason);
    }

    private EventResult reject(int index, String reference, String eventCode, String reason) {
        // 只 warn 不 error：单条失败是承运商数据的常态（他们会报我们没建的票），
        // 打成 error 会让真正的异常（连不上库、报文解析失败）淹没在噪声里。
        log.warn("承运商回调单条拒绝 下标={} 单号={} 事件码={} 原因={}", index, reference, eventCode, reason);
        return new EventResult(index, OUTCOME_REJECTED, null, reference, eventCode, false,
                null, null, null, reason);
    }

    private String nodeName(IftstaMessage.Event event, String eventCode) {
        return IftstaEventCodes.trackingOnlyNodeName(eventCode, event.eventDescription());
    }

    /** 地点拼装：LOC.C517 通常只给码或只给名，两个都有的用「码+空格+名」。 */
    private String placeOf(IftstaMessage.Event event) {
        String code = IntlFields.text(event.placeOfEvent());
        String name = IntlFields.text(event.placeOfEventName());
        if (code.isEmpty()) {
            return name;
        }
        return name.isEmpty() ? code : code + " " + name;
    }

    /**
     * 备注：把承运商自己说的话留着。
     *
     * <p>为什么留 remark 而不是只留 raw_payload：raw_payload 是整条报文，
     * 一票货几十条节点都指向同一份，查单条节点时得自己从 JSON 里挖；
     * remark 是「这一条」的摘要（运输工具、阶段、原因码、承运商描述）。</p>
     */
    private String buildRemark(IftstaMessage.Event event, Optional<String> target) {
        StringBuilder sb = new StringBuilder();
        sb.append("IFTSTA 事件 ").append(IntlFields.text(event.eventCode()));
        String description = IntlFields.text(event.eventDescription());
        if (!description.isEmpty()) {
            sb.append("（").append(description).append("）");
        }
        if (target.isEmpty()) {
            sb.append("；未映射到内部状态，仅留轨迹");
        }
        String means = IntlFields.text(event.transportMeansCode());
        if (!means.isEmpty()) {
            sb.append("；运输工具=").append(means);
        }
        String stage = IntlFields.text(event.transportStage());
        if (!stage.isEmpty()) {
            sb.append("；运输阶段=").append(stage);
        }
        String reasonCode = IntlFields.text(event.statusReasonCode());
        if (!reasonCode.isEmpty()) {
            String reasonText = IntlFields.text(event.statusReason());
            sb.append("；原因=").append(reasonCode)
                    .append(reasonText.isEmpty() ? "" : "/" + reasonText);
        }
        String freeText = IntlFields.text(event.freeText());
        if (!freeText.isEmpty()) {
            sb.append("；承运商备注=").append(freeText);
        }
        // remark 列是 VARCHAR(255)：外部来的长文本截断即可，截断比让 MySQL 报错好
        String remark = sb.toString();
        return remark.length() <= 255 ? remark : remark.substring(0, 255);
    }
}