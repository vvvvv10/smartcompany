package com.example.tms.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 车辆创建/更新入参。id 为空是创建，非空是更新。 */
public record VehiclePayload(
        Long id,

        @NotBlank(message = "车牌号不能为空")
        @Size(max = 16, message = "车牌号最长 16 字")
        String plateNo,

        @Size(max = 32, message = "车型最长 32 字")
        String type,

        @Size(max = 16)
        String status,

        @Size(max = 64, message = "司机姓名最长 64 字")
        String driverName,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        java.math.BigDecimal costPerKm,

        java.math.BigDecimal dailyFixedCost,

        Boolean isExternal) {
}
