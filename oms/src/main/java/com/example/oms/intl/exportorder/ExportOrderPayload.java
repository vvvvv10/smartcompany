package com.example.oms.intl.exportorder;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 出口子单创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>orderNo 留空由服务端生成 EXP+yyyyMMdd+3位；传了就用传入的（允许客户自带单号）。
 * customerId 是 CRM customers.id 的**跨库弱引用**：本服务不校验它是否存在
 * （校验要一次跨库 HTTP，会把一次本地事务变成三次远程调用）；
 * 同时冗余 customerName 快照，对方删了客户历史单据仍可读。</p>
 *
 * <p>items 为 null 表示不动明细（更新时保留）；空列表表示清空明细。
 * 但**已组批（isBatchLocked=1）的单不允许改明细**——件数已计入母单汇总。</p>
 */
public record ExportOrderPayload(
        Long id,

        @Size(max = 32, message = "出口子单号最长 32 字")
        String orderNo,

        @NotNull(message = "客户不能为空")
        Long customerId,

        @Size(max = 128, message = "客户名称最长 128 字")
        String customerName,

        @NotBlank(message = "境外收货人不能为空")
        @Size(max = 64, message = "收货人最长 64 字")
        String consigneeName,

        @Size(max = 128, message = "收货公司最长 128 字")
        String consigneeCompany,

        @Pattern(regexp = "([A-Z]{2})?", message = "收货国只能是两位 ISO 代码，如 US")
        String consigneeCountry,

        @NotBlank(message = "境外收货地址不能为空")
        @Size(max = 255, message = "收货地址最长 255 字")
        String consigneeAddress,

        @Size(max = 32, message = "收货电话最长 32 字")
        String consigneePhone,

        @Size(max = 64, message = "收货邮箱最长 64 字")
        String consigneeEmail,

        @Pattern(regexp = "(EXW|FCA|FOB|CFR|CIF|CPT|CIP|DAP|DPU|DDP)?",
                message = "贸易条款只能是 EXW/FCA/FOB/CFR/CIF/CPT/CIP/DAP/DPU/DDP")
        String incoterm,

        @Pattern(regexp = "([A-Z]{3})?", message = "币种只能是三位 ISO 代码，如 USD")
        String currency,

        @DecimalMin(value = "0", message = "订单金额不能为负")
        BigDecimal totalAmount,

        @DecimalMin(value = "0", message = "运费不能为负")
        BigDecimal freightAmount,

        @Min(value = 0, message = "件数不能为负")
        Integer totalQty,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal totalWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal totalVolume,

        @DecimalMin(value = "0", message = "体积重不能为负")
        BigDecimal volumetricWeight,

        /** ETD/ETA/备货完成日是当地日期，不做时区换算。 */
        LocalDate etdDate,

        LocalDate etaDate,

        LocalDate readyDate,

        @Pattern(regexp = "(DRAFT|CONFIRMED|PICKING|PACKED|EXPORT_DECLARED|SHIPPED|IN_TRANSIT|ARRIVED"
                + "|CUSTOMS_CLEARING|CUSTOMS_CLEARED|LAST_MILE|DELIVERED|CLOSED|EXCEPTION|CANCELLED)?",
                message = "不是合法的出口子单状态")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        List<ExportOrderItemPayload> items) {

    public ExportOrderPayload withId(long newId) {
        return new ExportOrderPayload(newId, orderNo, customerId, customerName, consigneeName,
                consigneeCompany, consigneeCountry, consigneeAddress, consigneePhone, consigneeEmail,
                incoterm, currency, totalAmount, freightAmount, totalQty, totalWeight, totalVolume,
                volumetricWeight, etdDate, etaDate, readyDate, status, remark, items);
    }
}
