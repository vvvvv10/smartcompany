package com.example.tms.intl.carrier;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 承运商创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>code 必填且唯一：它是承运商对外的身份（船司代码 / 航司二字码 / 快递品牌名），
 * 运单与航线都按它关联，改一次等于换一家承运商——所以它比 name 更容易被下游引用。</p>
 */
public record CarrierPayload(
        Long id,

        @NotBlank(message = "承运商代码不能为空")
        @Size(max = 16, message = "承运商代码最长 16 字")
        String code,

        @NotBlank(message = "承运商中文名不能为空")
        @Size(max = 64, message = "承运商中文名最长 64 字")
        String nameZh,

        @Size(max = 64, message = "承运商英文名最长 64 字")
        String nameEn,

        @Pattern(regexp = "(OCEAN|AIR|EXPRESS|NVOCC|FORWARDER|AGENT|CLEARANCE|LAST_MILE)?",
                message = "类型只能是 OCEAN/AIR/EXPRESS/NVOCC/FORWARDER/AGENT/CLEARANCE/LAST_MILE")
        String carrierType,

        @Size(max = 2, message = "注册国是两位 ISO 代码，如 CN")
        String country,

        @Size(max = 64, message = "联系人最长 64 字")
        String contactName,

        @Size(max = 32, message = "联系电话最长 32 字")
        String contactPhone,

        @Size(max = 64, message = "联系邮箱最长 64 字")
        String contactEmail,

        @Pattern(regexp = "(STANDARD|EXPRESS|ECONOMY)?",
                message = "服务等级只能是 STANDARD/EXPRESS/ECONOMY")
        String serviceLevel,

        @Min(value = 0, message = "时效天数不能为负")
        @Max(value = 365, message = "时效天数最多 365")
        Integer transitDays,

        @Min(value = 0, message = "截关提前小时数不能为负")
        @Max(value = 168, message = "截关提前小时数最多 168")
        Integer cutOffHours,

        @Min(value = 0, message = "免堆存天数不能为负")
        @Max(value = 90, message = "免堆存天数最多 90")
        Integer freeTimeDays,

        @Pattern(regexp = "(ACTIVE|INACTIVE)?", message = "状态只能是 ACTIVE 或 INACTIVE")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
