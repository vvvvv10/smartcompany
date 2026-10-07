package com.example.tms.intl.shipment;

import java.util.List;

/**
 * 运单详情 = 主单 + 箱明细 + 轨迹节点 + 报关单。
 *
 * <p>为什么一次返回而不是四个接口：详情页一打开就要这四块，分成四个请求意味着
 * 前端要处理「轨迹回来了但箱还没回来」的中间态；数据量小（一条运单几行到几十行），
 * 一次返回的成本可以忽略。</p>
 *
 * <p>declaration 允许为 null——还没报关的运单就是没有报关单，前端显示「未报关」。</p>
 */
public record ShipmentDetail(
        Shipment shipment,
        List<ShipmentItem> items,
        List<TrackingNode> trackingNodes,
        com.example.tms.intl.customs.CustomsDeclaration declaration) {
}
