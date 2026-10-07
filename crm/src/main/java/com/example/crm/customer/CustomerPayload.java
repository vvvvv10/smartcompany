package com.example.crm.customer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 客户创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>末尾 8 个字段是国际属性，全部可选：不传时服务端回落
 * SELLER / CN / 账期 0 / 额度 0 / USD，存量国内客户的编辑行为与加列之前完全一致。</p>
 */
public record CustomerPayload(
        Long id,

        @NotBlank(message = "客户名称不能为空")
        @Size(max = 64, message = "客户名称最长 64 字")
        String name,

        @Size(max = 32, message = "行业最长 32 字")
        String industry,

        @Size(max = 16)
        String level,

        @Size(max = 32, message = "来源最长 32 字")
        String source,

        @Size(max = 16)
        String status,

        @Size(max = 32, message = "电话最长 32 字")
        String phone,

        @Size(max = 64, message = "邮箱最长 64 字")
        String email,

        @Size(max = 128, message = "地址最长 128 字")
        String address,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        @Pattern(regexp = "(SELLER|FORWARDER|FACTORY|DISTRIBUTOR)?",
                message = "客户类型只能是 SELLER/FORWARDER/FACTORY/DISTRIBUTOR")
        String customerType,

        @Pattern(regexp = "([A-Z]{2})?", message = "国家只能是两位 ISO 代码，如 CN")
        String country,

        @Size(max = 32, message = "税号最长 32 字")
        String taxNo,

        @Min(value = 0, message = "账期天数不能为负")
        @Max(value = 180, message = "账期天数最多 180")
        Integer paymentTermDays,

        BigDecimal creditLimit,

        BigDecimal creditUsed,

        @Pattern(regexp = "([A-Z]{3})?", message = "默认币种只能是三位 ISO 代码，如 USD")
        String defaultCurrency,

        @Size(max = 32, message = "IOSS 编号最长 32 字")
        String iossNo) {

    /** 补齐 payload 漏传的 id（PUT 时以路径 id 为准）。 */
    public CustomerPayload withId(long newId) {
        return new CustomerPayload(newId, name, industry, level, source, status, phone, email, address,
                remark, customerType, country, taxNo, paymentTermDays, creditLimit, creditUsed,
                defaultCurrency, iossNo);
    }
}
