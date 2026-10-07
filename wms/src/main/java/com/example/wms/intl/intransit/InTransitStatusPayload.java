package com.example.wms.intl.intransit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH /in-transit/{id}/status 入参。
 *
 * <p>进 RECEIVED（已入库）时带 toWarehouseId：在途行要指明「入到哪个仓」，
 * 而这正是 M2 扣减 wms_inventory_batches 的时点。M1 只落状态，
 * 不动库存批次——理由见 PackTaskService 的类注释（M1 的库存占用/释放与出库联动一起做）。</p>
 *
 * <p>取值域与 {@code IntlStateMachine.IN_TRANSIT} 的 key 保持一致：
 * IN_TRANSIT / ARRIVED / RECEIVED / HELD / EXCEPTION / LOST。
 * HELD（海关查验扣留）与 LOST（丢件）分开是 P2-5 的核心——查验 ≠ 丢失，
 * 把查验记成丢件会凭空造出理赔事件。这里的正则只管「拼写对不对」，
 * 「这个迁移允不允许」由状态机判（见 InTransitService.changeStatus）。</p>
 */
public record InTransitStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(IN_TRANSIT|ARRIVED|RECEIVED|HELD|EXCEPTION|LOST)",
                message = "在途状态只能是 IN_TRANSIT/ARRIVED/RECEIVED/HELD/EXCEPTION/LOST")
        String status,

        Long toWarehouseId) {
}
