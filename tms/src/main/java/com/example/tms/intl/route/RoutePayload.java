package com.example.tms.intl.route;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 航线创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>volumetricDivisor 是体积重除数（cm³/kg）：空运 6000、快递 5000。
 * 它挂在航线上而不是挂承运商上，因为**同一家船司不同航线的计费口径可能不同**
 * （W/M 比例、按吨/按件都随航线走）。</p>
 */
public record RoutePayload(
        Long id,

        @NotBlank(message = "航线代码不能为空")
        @Size(max = 32, message = "航线代码最长 32 字")
        String routeCode,

        @NotNull(message = "承运商不能为空")
        Long carrierId,

        @Pattern(regexp = "(SEA|AIR|RAIL|TRUCK|EXPRESS|LCL)?",
                message = "运输方式只能是 SEA/AIR/RAIL/TRUCK/EXPRESS/LCL")
        String mode,

        @NotBlank(message = "起运港/机场不能为空")
        @Size(max = 8, message = "起运港码最长 8 字")
        String polCode,

        @NotBlank(message = "目的港/目的地不能为空")
        @Size(max = 8, message = "目的港码最长 8 字")
        String podCode,

        @Min(value = 0, message = "时效天数不能为负")
        @Max(value = 365, message = "时效天数最多 365")
        Integer transitDays,

        @Min(value = 1, message = "体积重除数至少为 1")
        @Max(value = 20000, message = "体积重除数最多 20000")
        Integer volumetricDivisor,

        @Size(max = 128, message = "中转港最长 128 字")
        String viaPorts,

        @Pattern(regexp = "(ACTIVE|INACTIVE)?", message = "状态只能是 ACTIVE 或 INACTIVE")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
