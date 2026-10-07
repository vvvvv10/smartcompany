package com.example.tms.intl.shipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH /{id}/status 的入参：只带要推进到的状态与可选说明。
 *
 * <p>与 PUT /{id} 分开的原因见 {@link ShipmentPayload} 的类注释：
 * 「改属性」和「推进流程」是两种完全不同的风险，混在一个端点里迟早会出现
 * 「顺手把状态从 PICKED_UP 改回 BOOKED」这种绕过状态机的操作。</p>
 *
 * <p>进 EXCEPTION 时 exceptionReason 必填（Service 里强校验）：
 * 异常单不写原因，过两周就没人知道当初为什么挂起，只能当垃圾数据处理。</p>
 */
public record ShipmentStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(DRAFT|BOOKING|BOOKED|PICKED_UP|EXPORT_DECLARED|DEPARTED|IN_TRANSIT|ARRIVED"
                + "|CLEARING|CLEARED|LAST_MILE|DELIVERED|POD_CONFIRMED|EXCEPTION|RETURNED|CANCELLED)",
                message = "不是合法的运单状态")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        @Size(max = 255, message = "异常原因最长 255 字")
        String exceptionReason) {
}
