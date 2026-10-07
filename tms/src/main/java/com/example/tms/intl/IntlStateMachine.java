package com.example.tms.intl;

import java.util.Map;
import java.util.Set;

/**
 * 国际运单域的状态机（一处定义，运单与报关单共用校验入口）。
 *
 * <p><b>为什么状态迁移必须在服务端校验</b>：现在的国内陆运运单是 4 态无校验的
 * （前端下拉直接 PUT），前端能点什么就点什么。这在「待发车→运输中→已送达」这种
 * 线性流程里勉强能用，但国际运单有 16 个态，且横跨三个库——如果服务端不拦，
 * 就会出现「运单已取消但报关单已放行」「母单已开航而子单还在 PACKED」这类
 * 没人能解释的数据。把合法迁移写成一张常量表，是让服务端成为唯一裁判。</p>
 *
 * <p><b>为什么不抽象成通用状态机框架</b>：本项目只有三张状态表（运单/报关单/母单），
 * 一张 Map 加一个 assert 就够；引入框架要多一层反射与配置，
 * 收益抵不上「多一处可运行代码」的成本。</p>
 */
public final class IntlStateMachine {

    private IntlStateMachine() {
    }

    /**
     * 运单状态迁移表：当前态 → 允许去的状态集合。
     *
     * <p>终态（CANCELLED / POD_CONFIRMED）不出现在任何 value 里，所以从终态出发
     * 任何迁移都会被拒——不需要额外写「终态判断」，少一处分支就少一处漏。</p>
     */
    public static final Map<String, Set<String>> SHIPMENT = Map.ofEntries(
            Map.entry("DRAFT", Set.of("BOOKING", "CANCELLED")),
            Map.entry("BOOKING", Set.of("BOOKED", "EXCEPTION", "CANCELLED")),
            Map.entry("BOOKED", Set.of("PICKED_UP", "EXCEPTION", "CANCELLED")),
            Map.entry("PICKED_UP", Set.of("EXPORT_DECLARED", "EXCEPTION")),
            Map.entry("EXPORT_DECLARED", Set.of("DEPARTED", "EXCEPTION")),
            Map.entry("DEPARTED", Set.of("IN_TRANSIT", "EXCEPTION")),
            Map.entry("IN_TRANSIT", Set.of("ARRIVED", "EXCEPTION")),
            Map.entry("ARRIVED", Set.of("CLEARING", "EXCEPTION")),
            Map.entry("CLEARING", Set.of("CLEARED", "EXCEPTION")),
            Map.entry("CLEARED", Set.of("LAST_MILE", "EXCEPTION")),
            Map.entry("LAST_MILE", Set.of("DELIVERED", "EXCEPTION")),
            Map.entry("DELIVERED", Set.of("POD_CONFIRMED", "EXCEPTION")),
            // EXCEPTION 允许回到「挂起前的状态」或退运/取消：异常挂起不是死局，
            // 人处理完要能把单子放回流水线上，否则只能作废重建。
            Map.entry("EXCEPTION", Set.of("DRAFT", "BOOKING", "BOOKED", "PICKED_UP", "EXPORT_DECLARED",
                    "DEPARTED", "IN_TRANSIT", "ARRIVED", "CLEARING", "CLEARED", "LAST_MILE",
                    "DELIVERED", "RETURNED", "CANCELLED")),
            Map.entry("RETURNED", Set.of("CANCELLED")));

    /**
     * 报关单状态迁移表。
     * REJECTED（退单）是终态：报关单被海关退单后必须重开一张，不是「改回去」。
     */
    public static final Map<String, Set<String>> DECLARATION = Map.ofEntries(
            Map.entry("DRAFT", Set.of("FILED")),
            Map.entry("FILED", Set.of("RELEASED", "INSPECTING", "REJECTED")),
            Map.entry("INSPECTING", Set.of("RELEASED", "HELD", "REJECTED")),
            Map.entry("HELD", Set.of("RELEASED", "REJECTED")),
            Map.entry("RELEASED", Set.of()),
            Map.entry("REJECTED", Set.of()));

    /**
     * 校验一次迁移，非法就抛 {@link IllegalArgumentException}（全局处理器转 400 invalid_argument）。
     *
     * @param table 迁移表（运单或报关单）
     * @param from   当前状态
     * @param to     目标状态
     * @param 主体   错误文案里的对象名，如「运单」「报关单」
     */
    public static void assertTransition(Map<String, Set<String>> table, String from, String to, String 主体) {
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
