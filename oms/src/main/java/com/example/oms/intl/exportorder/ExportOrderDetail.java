package com.example.oms.intl.exportorder;

import com.example.oms.intl.exportbatch.ExportBatch;
import java.util.List;

/**
 * 出口子单详情 = 主表 + 明细 + 母单 + 跨库聚合（运单/备货任务）。
 *
 * <p>一次返回而不是四个接口，理由同 TMS 的 ShipmentDetail：详情页一打开就要这几块，
 * 分成多次请求前端要处理「有的到了有的没到」的中间态。</p>
 *
 * <p>batch 允许为 null（还没组批）；shipment/packTask 是跨库聚合结果，
 * 下游不可用时为 null——「—」比「报 502」有用。</p>
 */
public record ExportOrderDetail(
        ExportOrder order,
        List<ExportOrderItem> items,
        ExportBatch batch,
        com.example.oms.linkage.LinkageClient.ShipmentInfo shipment,
        com.example.oms.linkage.LinkageClient.PackTaskInfo packTask) {
}
