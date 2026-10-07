package com.example.oms.intl.exportbatch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PATCH /batches/{id}/status 入参。 */
public record ExportBatchStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(DRAFT|CONFIRMED|WAITING_GOODS|BOOKING|BOOKED|LOADING|DEPARTED|IN_TRANSIT"
                + "|ARRIVED|CLEARING|CLEARED|LAST_MILE|DELIVERED|CLOSED|EXCEPTION|CANCELLED)",
                message = "不是合法的出口母单状态")
        String status,

        String remark) {
}
