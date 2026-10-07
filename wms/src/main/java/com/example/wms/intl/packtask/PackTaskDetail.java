package com.example.wms.intl.packtask;

import java.util.List;

/**
 * 备货任务详情 = 主表 + 明细 + FEFO 批次候选。
 *
 * <p>batchCandidates 是「这个 SKU 在本仓里按效期升序的库存批次」，
 * 供页面上的「按 FEFO 推荐批次」一键回填。<b>只推荐不强制</b>——
 * 仓库现场可能有「这批虽然效期长但客户指定要这个批次」的现实。</p>
 */
public record PackTaskDetail(
        PackTask task,
        List<PackTaskItem> items,
        List<com.example.wms.intl.batch.InventoryBatch> batchCandidates) {
}
