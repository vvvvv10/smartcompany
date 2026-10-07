package com.example.wms.warehouse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 仓库创建/更新入参。id 为空是创建，非空是更新。 */
public record WarehousePayload(
        Long id,

        @NotBlank(message = "仓库名称不能为空")
        @Size(max = 64, message = "仓库名称最长 64 字")
        String name,

        @Size(max = 128, message = "位置最长 128 字")
        String location,

        Integer capacity,

        @Size(max = 16)
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        // ---- 国际仓属性（45 号迁移新增，可选；存量仓缺省 DOMESTIC/CN/Asia/Shanghai）----

        @Pattern(regexp = "(DOMESTIC|OVERSEAS|BONDED|TRANSIT)?",
                message = "仓库类型只能是 DOMESTIC/OVERSEAS/BONDED/TRANSIT")
        String warehouseType,

        @Pattern(regexp = "([A-Z]{2})?", message = "所在国只能是两位 ISO 代码，如 CN")
        String country,

        @Size(max = 32, message = "时区最长 32 字，如 Asia/Shanghai")
        String timezone,

        @Size(max = 64, message = "海外仓服务商最长 64 字")
        String overseaOperator,

        @Min(value = 0, message = "保税标记只能是 0/1")
        @Max(value = 1, message = "保税标记只能是 0/1")
        Integer isBonded) {

    /** 补齐 payload 漏传的 id（PUT 时以路径 id 为准）。 */
    public WarehousePayload withId(long newId) {
        return new WarehousePayload(newId, name, location, capacity, status, remark,
                warehouseType, country, timezone, overseaOperator, isBonded);
    }
}
