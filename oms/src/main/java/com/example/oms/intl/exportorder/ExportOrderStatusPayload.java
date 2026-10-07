package com.example.oms.intl.exportorder;

import com.example.oms.intl.exportbatch.ExportBatch;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PATCH /export-orders/{id}/status 入参。 */
public record ExportOrderStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(DRAFT|CONFIRMED|PICKING|PACKED|EXPORT_DECLARED|SHIPPED|IN_TRANSIT|ARRIVED"
                + "|CUSTOMS_CLEARING|CUSTOMS_CLEARED|LAST_MILE|DELIVERED|CLOSED|EXCEPTION|CANCELLED)",
                message = "不是合法的出口子单状态")
        String status,

        String remark) {
}
