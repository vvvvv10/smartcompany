package com.example.oms.intl.exportorder;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 出口单明细行入参。
 *
 * <p><b>为什么这些申报要素是必填的校验项，而不是「留空以后补」</b>：
 * HS 编码与申报货值是海关受理的最低要求，缺任何一项报关行都会退单。
 * 让它在这里 400 掉，比让它流到报关环节退回来便宜得多。</p>
 */
public record ExportOrderItemPayload(
        Long id,
        Long productId,

        @Size(max = 64, message = "SKU 最长 64 字")
        String sku,

        @Size(max = 128, message = "品名最长 128 字")
        String productName,

        @Size(max = 255, message = "报关品名最长 255 字")
        String customsName,

        @Size(max = 255, message = "英文报关品名最长 255 字")
        String customsNameEn,

        @Size(max = 12, message = "HS 编码最长 12 字")
        String hsCode,

        @Pattern(regexp = "([A-Z]{2})?", message = "原产国只能是两位 ISO 代码，如 CN")
        String originCountry,

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少 1")
        Integer quantity,

        @DecimalMin(value = "0", message = "单价不能为负")
        BigDecimal unitPrice,

        @DecimalMin(value = "0", message = "申报货值不能为负")
        BigDecimal declaredValue,

        @DecimalMin(value = "0", message = "净重不能为负")
        BigDecimal netWeight,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal grossWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal volume,

        @Min(value = 0, message = "危险品标记只能是 0/1")
        @Max(value = 1, message = "危险品标记只能是 0/1")
        Integer isDangerous,

        @Size(max = 16, message = "UN 编号最长 16 字")
        String unNumber,

        @Size(max = 8, message = "危险品类别最长 8 字")
        String dgClass,

        @Pattern(regexp = "(PI967|PI966|PI969)?", message = "包装说明只能是 PI967/PI966/PI969")
        String packingInstruction,

        @DecimalMin(value = "0", message = "电池瓦时不能为负")
        BigDecimal batteryWattHours,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
