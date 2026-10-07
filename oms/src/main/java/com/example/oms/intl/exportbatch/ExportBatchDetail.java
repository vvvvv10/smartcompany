package com.example.oms.intl.exportbatch;

import com.example.oms.intl.exportorder.ExportOrder;
import java.util.List;

/**
 * 出口母单详情 = 母单 + 全部子单 + 运单快照。
 *
 * <p>子单一次拉齐（idx_batch 服务它），运单快照走 LinkageClient 单条查询。
 * shipment 为 null 表示「还没发起订舱」或「TMS 不可用」——这两种情况在页面上
 * 都表现为「运单块显示未建 / —」，但文案上要能区分，所以详情页会同时看
 * shipmentNo 是否为空与接口是否降级（日志里有 warn）。</p>
 */
public record ExportBatchDetail(
        ExportBatch batch,
        List<ExportOrder> orders,
        com.example.oms.linkage.LinkageClient.ShipmentInfo shipment) {
}
