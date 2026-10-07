package com.example.oms.intl;

import java.util.Map;
import java.util.Set;

/**
 * 出口单据域的状态机（母单 + 子单）。
 *
 * <p>与 TMS 运单状态机分开定义而不是共用一张表，因为**两者的推进节奏不同**：
 * 运单是「运输事实」（承运商推着走，一步都不能跳），
 * 出口子单是「面向客户的展示层」（允许滞后，计划 §11 R1）。
 * 共用一张表会诱使人写出「运单自动把子单一起推了」的逻辑，
 * 而那正是计划明确推迟到 M2 的反向回调——M1 里子单状态由人工点。</p>
 */
public final class IntlStateMachine {

    private IntlStateMachine() {
    }

    /**
     * 出口子单状态迁移表。
     *
     * <p>EXCEPTION 允许回到挂起前的状态：异常挂起不是终态，人处理完要把货放回流水线。
     * CANCELLED / CLOSED 是终态（不出现在任何 value 里）。</p>
     */
    public static final Map<String, Set<String>> EXPORT_ORDER = Map.ofEntries(
            Map.entry("DRAFT", Set.of("CONFIRMED", "CANCELLED")),
            Map.entry("CONFIRMED", Set.of("PICKING", "CANCELLED")),
            Map.entry("PICKING", Set.of("PACKED", "EXCEPTION", "CANCELLED")),
            Map.entry("PACKED", Set.of("EXPORT_DECLARED", "EXCEPTION", "CANCELLED")),
            Map.entry("EXPORT_DECLARED", Set.of("SHIPPED", "EXCEPTION")),
            Map.entry("SHIPPED", Set.of("IN_TRANSIT", "EXCEPTION")),
            Map.entry("IN_TRANSIT", Set.of("ARRIVED", "EXCEPTION")),
            Map.entry("ARRIVED", Set.of("CUSTOMS_CLEARING", "EXCEPTION")),
            Map.entry("CUSTOMS_CLEARING", Set.of("CUSTOMS_CLEARED", "EXCEPTION")),
            Map.entry("CUSTOMS_CLEARED", Set.of("LAST_MILE", "EXCEPTION")),
            Map.entry("LAST_MILE", Set.of("DELIVERED", "EXCEPTION")),
            Map.entry("DELIVERED", Set.of("CLOSED", "EXCEPTION")),
            Map.entry("CLOSED", Set.of()),
            Map.entry("EXCEPTION", Set.of("DRAFT", "CONFIRMED", "PICKING", "PACKED", "EXPORT_DECLARED",
                    "SHIPPED", "IN_TRANSIT", "ARRIVED", "CUSTOMS_CLEARING", "CUSTOMS_CLEARED",
                    "LAST_MILE", "DELIVERED", "CLOSED", "CANCELLED")),
            Map.entry("CANCELLED", Set.of()));

    /**
     * 出口母单状态迁移表。
     * LOADING（装柜中）夹在 BOOKED 与 DEPARTED 之间，是真实流程里独立的一步：
     * 货已装进柜但船还没开，这时客户问「到了吗」不该答「已开航」。
     *
     * <p>WAITING_GOODS（等货备货）解决的是 LCL 拼箱的真实常态：母单已组批，
     * 但子单还在仓库拣货打包，凑不齐货就不能订舱。没有这个状态时，LCL 母单只能
     * 停在 CONFIRMED 等着，和「已确认但还没开始备货」混在一起说不清；而把子单的
     * PICKING 写进母单状态列则更糟——那是个不在本表里的值，这张母单从此谁也推不动。</p>
     */
    public static final Map<String, Set<String>> EXPORT_BATCH = Map.ofEntries(
            Map.entry("DRAFT", Set.of("CONFIRMED", "CANCELLED")),
            Map.entry("CONFIRMED", Set.of("WAITING_GOODS", "BOOKING", "CANCELLED")),
            Map.entry("WAITING_GOODS", Set.of("BOOKING", "EXCEPTION", "CANCELLED")),
            Map.entry("BOOKING", Set.of("BOOKED", "EXCEPTION", "CANCELLED")),
            Map.entry("BOOKED", Set.of("LOADING", "EXCEPTION", "CANCELLED")),
            Map.entry("LOADING", Set.of("DEPARTED", "EXCEPTION")),
            Map.entry("DEPARTED", Set.of("IN_TRANSIT", "EXCEPTION")),
            Map.entry("IN_TRANSIT", Set.of("ARRIVED", "EXCEPTION")),
            Map.entry("ARRIVED", Set.of("CLEARING", "EXCEPTION")),
            Map.entry("CLEARING", Set.of("CLEARED", "EXCEPTION")),
            Map.entry("CLEARED", Set.of("LAST_MILE", "EXCEPTION")),
            Map.entry("LAST_MILE", Set.of("DELIVERED", "EXCEPTION")),
            Map.entry("DELIVERED", Set.of("CLOSED", "EXCEPTION")),
            Map.entry("CLOSED", Set.of()),
            Map.entry("EXCEPTION", Set.of("DRAFT", "CONFIRMED", "WAITING_GOODS", "BOOKING", "BOOKED",
                    "LOADING", "DEPARTED", "IN_TRANSIT", "ARRIVED", "CLEARING", "CLEARED", "LAST_MILE",
                    "DELIVERED", "CLOSED", "CANCELLED")),
            Map.entry("CANCELLED", Set.of()));

    public static void assertOrder(Map<String, Set<String>> table, String from, String to) {
        assertTransition(table, from, to, "出口子单");
    }

    public static void assertBatch(Map<String, Set<String>> table, String from, String to) {
        assertTransition(table, from, to, "出口母单");
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
