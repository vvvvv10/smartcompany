package com.example.tms.intl.callback;

import java.util.Map;
import java.util.Optional;

/**
 * IFTSTA 事件码 → 内部 node_code / 运单目标状态的映射表（M2-4）。
 *
 * <p><b>为什么这张表必须小，而且必须允许落空</b>：UNCL 4405 是几百项的码表，
 * 但没几家承运商会用全；行业实践里真正流通的就是下面这十来个
 * （{@code ORI/PRE/DEP/ARR/RCA/TRN/DLV/CUS/COL/EXC}，赫伯罗特、马士基、中远、
 * 丹格斯的 IFTSTA 实现基本一致）。反过来，**承运商的私有码比我们认识的多**：
 * 每家都有一堆自定义码（延误原因、内部站点码），它们在报文里的地位与上面那些完全相同。</p>
 *
 * <p><b>为什么映射不上时只落轨迹、不动主状态</b>（评审 M2-4 验收标准 ③，
 * 兜底名 {@code TRACKING_ONLY}）：</p>
 * <p>如果这里做「未知码 → 猜一个最接近的状态」，那等于<strong>让外部数据驱动我们的
 * 状态机</strong>。运单状态不是描述性的标签，它是<b>对外承担法律责任的断言</b>：
 * 推到 {@code EXPORT_DECLARED} 等于向海关申报，推到 {@code CLEARED} 等于向客户声明
 * 货已放行，推到 {@code DEPARTED} 意味着我们对船司主张了开航事实。这些断言必须来自
 * 「我们审查过的事实」，而不是「某个我们不认识的码猜起来最像什么」。</p>
 * <p>更现实的问题是：猜错的方向不可控。把一个未知的 {@code X39} 猜成 {@code ARRIVED}，
 * 会让一票还在船上的货凭空变成已到港，然后下游的在途库存（计划 §8.4）被扣掉、
 * 目的仓提前入库——账就错了，而且错得没有任何人会发现。</p>
 * <p>所以：<b>映射表不认识的一律只落轨迹</b>。轨迹是只追加的事实记录，落下它零成本
 * （还顺带留住了承运商的原文，等将来发现这码其实是「到港」时可以直接补映射重放）；
 * 动主状态是有代价的断言，宁可不动。</p>
 *
 * <p><b>映射到的码也不保证推得动</b>：运单状态机 + 三道业务门禁（异常原因 /
 * 制裁 HIT / 海运 VGM）一律照走。承运商报了 {@code DEP} 但柜子还没称重，
 * 我们照样不推——SOLAS 的核实总重不能由船公司的通知代替。失败转 rejected
 * 并把原因写进 {@code results}，运维看得到。</p>
 */
public final class IftstaEventCodes {

    private IftstaEventCodes() {
    }

    /**
     * 事件码 → 运单目标状态。
     *
     * <p>值用「运单状态」而不是「节点码」是因为推主状态时两者必须一致；
     * 落轨迹时 {@code node_code} 直接用这个状态名（与 {@code IntlStateMachine.SHIPMENT}
     * 的键同名），所以详情页的 Timeline 与运单状态永远讲同一个故事。</p>
     */
    private static final Map<String, String> EVENT_TO_STATUS = Map.ofEntries(
            // ---- 订舱与提货 ----
            // ORI = 已受理/已订舱。货代确认拿到舱位后推这个。
            Map.entry("ORI", "BOOKED"),
            // PRE = 货已交承运商等待发出（预处理/已揽收）。
            Map.entry("PRE", "PICKED_UP"),
            Map.entry("PU", "PICKED_UP"),

            // ---- 开航与在途 ----
            // DEP = 已开航/已发出。主运输段离开起运地。
            Map.entry("DEP", "DEPARTED"),
            // TRN = 在途（含转运中）。
            Map.entry("TRN", "IN_TRANSIT"),
            Map.entry("ITR", "IN_TRANSIT"),

            // ---- 到港与清关 ----
            // ARR = 到达目的港。RCA = 到达收货人处（俗称「已签收前到门」），
            // 两者都进 ARRIVED —— 到港与到门在海关口径上是同一件事的后续，
            // 而我们在它后面还有 CLEARING → CLEARED 两态，不会在 ARRIVED 上分叉。
            Map.entry("ARR", "ARRIVED"),
            Map.entry("RCA", "ARRIVED"),
            // CUS = 已报关（清关开始）。注意它落在 EXPORT_DECLARED 而不是 CLEARING：
            // 出口报关在开航之前就完成了，而承运商的 CUS 通常报的是「已向海关申报运输单据」，
            // 那个动作发生在装船前。若门禁未过（制裁 HIT），这一条会被拒——这是对的。
            Map.entry("CUS", "EXPORT_DECLARED"),
            // CLR = 清关中；COL = 清关完成放行。
            Map.entry("CLR", "CLEARING"),
            Map.entry("COL", "CLEARED"),
            Map.entry("CLD", "CLEARED"),

            // ---- 末端派送 ----
            // DLV = 已送达。POD = 回单已确认。
            Map.entry("DLV", "DELIVERED"),
            Map.entry("DEL", "DELIVERED"),
            Map.entry("POD", "POD_CONFIRMED"));

    /**
     * <b>刻意不映射的码</b>（即使它们看起来「很明确」），以及理由：
     * <ul>
     *   <li>{@code EXC} —— 运单进 EXCEPTION 强制要求 exceptionReason（状态机门禁），
     *       而承运商报文里只有 {@code C556/9011} 的原因码，那是承运商的码表口径
     *       （「ROLL 滚装」「STR 滞留」），不是我们的业务原因（「甩柜」「客户改单」）。
     *       把两者硬对齐，等于把承运商的枚举当成我们的异常原因——
     *       异常单两周后没人能看懂就会按垃圾数据处理。所以 EXC 只落轨迹，由人判断。</li>
     *   <li>{@code CAN} / {@code CLC} —— 承运商的「取消」与清关完成语义与我们的
     *       CANCELLED / CLEARED 不完全等价（承运商取消的是舱位，我们取消的是整票货）。
     *       本期不接，M4 逐个承运商对齐后再加。</li>
     * </ul>
     * 这些码落到兜底分支时会正常入库，{@code results} 里带
     * {@code detail=TRACKING_ONLY}，运维能看到「这家的码我们还不认」。</p>
     */
    private static final Map<String, String> KNOWN_BUT_UNMAPPED =
            Map.of("EXC", "承运商报异常：原因码属承运商口径，需人工判定后再挂起/推 EXCEPTION");

    /** 映射到目标状态；映射不上返回空。 */
    public static Optional<String> toShipmentStatus(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(EVENT_TO_STATUS.get(eventCode.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    /** 该事件码是否「我们认识但刻意不推状态」，用于给 results 一个更具体的说明。 */
    public static Optional<String> knownButUnmappedReason(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(KNOWN_BUT_UNMAPPED.get(eventCode.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    /** 兜底时的 node_code：外部码进不去内部枚举，但不能丢原文。 */
    public static String trackingOnlyNodeCode(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return com.example.tms.intl.shipment.NewTrackingNode.NODE_CODE_CARRIER_EVENT;
        }
        String cleaned = eventCode.trim().toUpperCase(java.util.Locale.ROOT);
        // 截到 24 - 前缀长度以内：node_code 是 VARCHAR(24)，而 external_code 存完整原码
        return "CARRIER_" + cleaned.substring(0, Math.min(cleaned.length(), 17));
    }

    /** 兜底时的节点中文名：承运商给了描述就用他们的，没给就标出原码（比空着强）。 */
    public static String trackingOnlyNodeName(String eventCode, String eventDescription) {
        if (eventDescription != null && !eventDescription.isBlank()) {
            return eventDescription.trim();
        }
        return "承运商事件 " + (eventCode == null || eventCode.isBlank() ? "(无事件码)" : eventCode.trim());
    }
}