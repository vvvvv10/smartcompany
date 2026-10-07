package com.example.crm.intl;

import java.util.Map;
import java.util.Set;

/**
 * 异常工单状态机。
 *
 * <p>OPEN → FOLLOWING → RESOLVED → CLOSED，并且允许 OPEN 直接跳到 RESOLVED
 * （现场最常见的情形就是「货已延误，直接记处理结果」，硬要求先点一次「处理中」
 * 只是让操作员多点一下，没有换来任何信息）。</p>
 *
 * <p>为什么要显式声明，而不在 service 里写 if：CRM/OMS/WMS/TMS 四个服务的状态都
 * 放在同名同形状的类里，页面上的「推进状态」下拉因此可以只列合法目标。
 * 之前这里是裸写 if（只挡了 CLOSED 不许再改），结果 RESOLVED 能被改回 OPEN——
 * 已解决的处理结果还挂在工单上，状态却说还没解决，看板上根本看不出矛盾。</p>
 *
 * <p>CLOSED 是终态：要撤单就重新开一张工单，而不是把旧的改回去。历史上留痕比
 * 「一张工单只有一个 CLOSED 时间点」重要。</p>
 */
public final class IntlStateMachine {

    private IntlStateMachine() {
    }

    public static final Map<String, Set<String>> TICKET = Map.ofEntries(
            Map.entry("OPEN", Set.of("FOLLOWING", "RESOLVED", "CANCELLED")),
            Map.entry("FOLLOWING", Set.of("RESOLVED", "CANCELLED")),
            Map.entry("RESOLVED", Set.of("CLOSED", "FOLLOWING")),
            // 解决之后又发现没解决，允许退回处理中；但不允许退回「待处理」——
            // 那等于把已填好的处理结果变成没有出处的孤儿数据。
            Map.entry("CANCELLED", Set.of()));

    public static void assertTicket(String from, String to) {
        assertTransition(TICKET, from, to, "异常工单");
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