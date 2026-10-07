package com.example.wms.intl;

import java.util.Map;
import java.util.Set;

/**
 * 备货任务与在途库存的状态机。
 *
 * <p>四个阶段（PENDING → PICKING → PACKED → LABELLED → HANDED_OVER）刻意分成
 * 「拣货 / 装箱 / 贴标 / 交接」而不是一个「已处理」：
 * 出口货的贴标（箱唛、FNSKU、目的国标签）是独立的一道工序，
 * 贴错了到港才发现，整柜退运的成本比拆成四个状态大得多。
 * 状态粒度决定了看板能不能回答「现在卡在哪一步」。</p>
 *
 * <p>SHORTAGE（缺料）可以从三个阶段进入，也可以回到 PICKING——
 * 补到货了就接着拣，不是重新建一条任务。</p>
 *
 * <p><b>为什么不抽象成通用状态机框架</b>：本项目只有几张状态表，一张 Map 加一个
 * assert 就够；引入框架要多一层反射与配置，收益抵不上「多一处可运行代码」的成本。</p>
 */
public final class IntlStateMachine {

    private IntlStateMachine() {
    }

    public static final Map<String, Set<String>> PACK_TASK = Map.ofEntries(
            Map.entry("PENDING", Set.of("PICKING", "CANCELLED")),
            Map.entry("PICKING", Set.of("PACKED", "SHORTAGE", "CANCELLED")),
            Map.entry("PACKED", Set.of("LABELLED", "SHORTAGE")),
            Map.entry("LABELLED", Set.of("HANDED_OVER", "SHORTAGE")),
            Map.entry("HANDED_OVER", Set.of()),
            Map.entry("SHORTAGE", Set.of("PICKING", "CANCELLED")),
            Map.entry("CANCELLED", Set.of()));

    /**
     * 在途库存状态迁移表：当前态 → 允许去的状态集合。
     *
     * <p>主链是 {@code IN_TRANSIT → ARRIVED → RECEIVED}：在途是「已出库未入库」，
     * 到了目的仓是 ARRIVED，入库完成才是 RECEIVED。RECEIVED 是终态且不出现在
     * 任何 value 里，所以「已入库的记录被改回在途」这条最荒唐的路径不需要
     * 额外写判断——从 RECEIVED 出发任何迁移都会被拒。</p>
     *
     * <p><b>HELD 与 LOST 必须分开</b>（P2-5）：HELD 是海关查验扣留，货还在、
     * 责任未定；LOST 是丢件，有理赔/索赔后果。种子曾把「整批查验」映射成 LOST，
     * 等于凭空造出一批理赔事件，将来「丢件率」看板算出来的是查验率。
     * HELD 可以放行回 ARRIVED（查验结束正常放行），也可以判 LOST（查验后认定短少），
     * 所以它必须能从 IN_TRANSIT 和 ARRIVED 两个态进入，也必须能离开。</p>
     *
     * <p>EXCEPTION（异常挂起，如外箱破损）与 HELD 同理可进可出：人处理完要把货
     * 放回流水线上，否则只能作废重建一行在途，等于凭空多出一批在途量。</p>
     *
     * <p>LOST 也是终态且不出现在任何 value 里。丢件要撤销必须走人工对账与理赔
     * 结论，不是把状态点回去就能抹掉的——这条数据会进索赔金额与丢件率。</p>
     */
    public static final Map<String, Set<String>> IN_TRANSIT = Map.ofEntries(
            Map.entry("IN_TRANSIT", Set.of("ARRIVED", "HELD", "EXCEPTION", "LOST")),
            Map.entry("ARRIVED", Set.of("RECEIVED", "HELD", "EXCEPTION", "LOST")),
            Map.entry("HELD", Set.of("IN_TRANSIT", "ARRIVED", "LOST")),
            Map.entry("EXCEPTION", Set.of("IN_TRANSIT", "ARRIVED", "LOST")),
            Map.entry("RECEIVED", Set.of()),
            Map.entry("LOST", Set.of()));

    public static void assertPackTask(String from, String to) {
        assertTransition(PACK_TASK, from, to, "备货任务");
    }

    /** 在途是 WMS 唯一的状态推进入口，迁移必须在这里判，不能让前端下拉说了算。 */
    public static void assertInTransit(String from, String to) {
        assertTransition(IN_TRANSIT, from, to, "在途记录");
    }

    private static void assertTransition(Map<String, Set<String>> table, String from, String to, String 主体) {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("缺少目标状态");
        }
        Set<String> allowed = table.get(from);
        if (allowed == null) {
            throw new IllegalArgumentException("未知状态: " + from);
        }
        if (!allowed.contains(to)) {
            throw new IllegalArgumentException(
                    "非法状态迁移: " + from + " → " + to
                            + (allowed.isEmpty() ? "（" + from + " 是终态）" : "，当前只允许 → " + String.join("/", allowed)));
        }
    }
}
