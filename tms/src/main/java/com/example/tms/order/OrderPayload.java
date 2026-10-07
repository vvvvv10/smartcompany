package com.example.tms.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 运输订单创建/更新入参。id 为空是创建，非空是更新。 */
public record OrderPayload(
        Long id,

        @NotBlank(message = "订单编号不能为空")
        @Size(max = 32, message = "订单编号最长 32 字")
        String orderNo,

        @NotBlank(message = "客户名称不能为空")
        @Size(max = 64, message = "客户名称最长 64 字")
        String customerName,

        @NotBlank(message = "起点不能为空")
        @Size(max = 128, message = "起点最长 128 字")
        String origin,

        @NotBlank(message = "终点不能为空")
        @Size(max = 128, message = "终点最长 128 字")
        String destination,

        @Size(max = 16)
        String status,

        Long driverId,

        Long vehicleId,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        java.math.BigDecimal distanceKm,

        java.math.BigDecimal cost) {
}
