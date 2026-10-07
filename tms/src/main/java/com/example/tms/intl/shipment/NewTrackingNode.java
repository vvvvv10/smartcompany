package com.example.tms.intl.shipment;

import java.time.LocalDateTime;

/**
 * 待写入的轨迹节点（**所有**写轨迹的入口都必须走这里，不允许再手写 INSERT）。
 *
 * <p>为什么单独一个类型：M2 之前「插一条轨迹」的 SQL 已经被手写复制了两份
 * （ShipmentService 人工录入、CusotmsService 报关联动），再加一份就是第三份。
 * 而 {@code tms_tracking_nodes} 这次刚加了三个列——第三份复制品会立刻变成
 * 「回调写出来的行 external_code/external_source/raw_payload 恒为空」，
 * 而且这个 bug 不会报错，只会安静地丢证据，等到对账时才发现。所以本轮把它收成
 * 一个组件，三处调用方共用同一个 INSERT。</p>
 *
 * <p>{@code tenantId} 显式传而不是内部取 {@code TenantContext}：定时任务与承运商回调
 * 都不是「某个登录用户的请求」，租户来源不同（前者是配置的默认租户，后者是网关注入的
 * X-Tenant-Id）。让调用方明确写出它，比在一个组件里猜上下文更不容易串租户。</p>
 *
 * @param tenantId       租户 ID
 * @param shipmentId     {@code tms_shipments.id}
 * @param shipmentNo     冗余运单号（幂等键的一部分）
 * @param nodeCode       内部节点码，与运单状态机同名（DEPARTED/ARRIVED/…）或 CARRIER_EVENT
 * @param nodeName       节点中文名（承运商原文优先）
 * @param nodeTime       节点发生时间（UTC）。回调用承运商报的事件时间，不可用入库时间
 * @param location       节点地点（IFTSTA LOC/C517 拼装值）
 * @param source         MANUAL / CARRIER_CALLBACK / SCHEDULED
 * @param operator       操作人：人工=网关注入的用户名；回调=承运商标识；定时=固定文案
 * @param remark         备注
 * @param externalCode   承运商原始事件码；非回调来源恒为 ""
 * @param externalSource 承运商标识；非回调来源恒为 ""
 * @param rawPayload     承运商原始报文（JSON 字符串）；非回调来源为 null
 */
public record NewTrackingNode(
        String tenantId,
        long shipmentId,
        String shipmentNo,
        String nodeCode,
        String nodeName,
        LocalDateTime nodeTime,
        String location,
        String source,
        String operator,
        String remark,
        String externalCode,
        String externalSource,
        String rawPayload) {

    /** 三个来源常量：source 列上没有 CHECK 约束，写错只有人能看出来，所以在这里定死。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_CARRIER_CALLBACK = "CARRIER_CALLBACK";
    public static final String SOURCE_SCHEDULED = "SCHEDULED";

    /** 承运商事件码映射不上时用的兜底节点码。 */
    public static final String NODE_CODE_CARRIER_EVENT = "CARRIER_EVENT";

    /**
     * node_code 列是 VARCHAR(24)：兜底码 + 承运商原始码要能塞得下，
     * 截断在这里做而不是让 MySQL 在严格模式下报错（回调是外部数据，不能因长度炸整批）。
     */
    public static NewTrackingNode withTruncatedCode(NewTrackingNode node) {
        String code = node.nodeCode();
        if (code != null && code.length() <= 24) {
            return node;
        }
        String clipped = code == null ? "" : code.substring(0, 24);
        return new NewTrackingNode(node.tenantId(), node.shipmentId(), node.shipmentNo(), clipped,
                node.nodeName(), node.nodeTime(), node.location(), node.source(), node.operator(),
                node.remark(), node.externalCode(), node.externalSource(), node.rawPayload());
    }
}