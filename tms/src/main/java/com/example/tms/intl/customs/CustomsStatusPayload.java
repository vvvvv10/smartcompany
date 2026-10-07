package com.example.tms.intl.customs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PATCH /customs/{id}/status 入参：只带目标状态。 */
public record CustomsStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(DRAFT|FILED|RELEASED|INSPECTING|HELD|REJECTED)",
                message = "不是合法的报关单状态")
        String status) {
}
