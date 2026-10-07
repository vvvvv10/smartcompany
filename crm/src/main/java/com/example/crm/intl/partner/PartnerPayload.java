package com.example.crm.intl.partner;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 渠道商创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>rating（合作评级 1~5）的评分标准【待验证】——计划 §13 第 13 项。
 * 服务端只校验范围，不校验「3 分是什么意思」，那是业务口径不是技术约束。</p>
 */
public record PartnerPayload(
        Long id,

        @NotBlank(message = "渠道商编号不能为空")
        @Size(max = 32, message = "渠道商编号最长 32 字")
        String partnerCode,

        /**
         * 对应 TMS tms_carriers.code（承运商代码）。
         *
         * <p>渠道商与承运商是**两套刻意并行的主数据**（TMS 管运输能力、CRM 管合作关系
         * 与账期），但两套并行不等于两张表可以毫无关联：少了这一列，
         * 「我们和 COSCO 的账期是多少」答不出来，M3 生成对账单时也不知道该发给哪个
         * partner_id。</p>
         *
         * <p>取值域不校验也不跨库查（口径同计划 §3.7 规则 2/P1-5：跨库校验要网络调用，
         * 会把一次普通的渠道商保存变成一次跨服务调用）。空串表示「这家不是承运商」——
         * 报关行、海外仓、卡航本来就没有承运商代码。</p>
         */
        @Size(max = 16, message = "承运商代码最长 16 字")
        String carrierCode,

        @NotBlank(message = "渠道商名称不能为空")
        @Size(max = 64, message = "渠道商名称最长 64 字")
        String partnerName,

        @Pattern(regexp = "(FORWARDER|NVOCC|AGENT|CLEARANCE_BROKER|LAST_MILE|TRUCKING)?",
                message = "类型只能是 FORWARDER/NVOCC/AGENT/CLEARANCE_BROKER/LAST_MILE/TRUCKING")
        String partnerType,

        @Size(max = 64, message = "联系人最长 64 字")
        String contactName,

        @Size(max = 32, message = "联系电话最长 32 字")
        String contactPhone,

        @Size(max = 64, message = "联系邮箱最长 64 字")
        String contactEmail,

        @Pattern(regexp = "([A-Z]{2})?", message = "国家只能是两位 ISO 代码，如 CN")
        String country,

        @Pattern(regexp = "([A-Z]{3})?", message = "结算币种只能是三位 ISO 代码，如 USD")
        String currency,

        @Min(value = 0, message = "账期天数不能为负")
        @Max(value = 180, message = "账期天数最多 180")
        Integer paymentTermDays,

        BigDecimal creditLimit,

        BigDecimal creditUsed,

        @Min(value = 1, message = "合作评级 1~5")
        @Max(value = 5, message = "合作评级 1~5")
        Integer rating,

        @Pattern(regexp = "(ACTIVE|INACTIVE)?", message = "状态只能是 ACTIVE 或 INACTIVE")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {

    public PartnerPayload withId(long newId) {
        return new PartnerPayload(newId, partnerCode, carrierCode, partnerName, partnerType, contactName,
                contactPhone, contactEmail, country, currency, paymentTermDays, creditLimit, creditUsed,
                rating, status, remark);
    }
}
