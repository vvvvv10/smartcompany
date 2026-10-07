package com.example.oms.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 商品创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>sku 为空时服务端按 "款号-颜色-尺码" 自动生成。</p>
 *
 * <p>末尾 12 个字段是 44 号迁移加的国际申报属性，**全部可选**：
 * 国内零售的 9 条存量款号没有这些字段，留空即不参与国际报关。
 * 有 dangerous=1 的商品建议填 unNumber / dgClass / packingInstruction——
 * 报关行对危险品是硬性要求，但服务端不强制（档案可以先建、属性后补）。</p>
 */
public record ProductPayload(
        Long id,

        @NotBlank(message = "款号不能为空")
        @Size(max = 32, message = "款号最长 32 字")
        String styleNo,

        @NotBlank(message = "品名不能为空")
        @Size(max = 128, message = "品名最长 128 字")
        String name,

        @Size(max = 16, message = "品类最长 16 字")
        String category,

        @Size(max = 16, message = "季节最长 16 字")
        String season,

        @Size(max = 32, message = "颜色最长 32 字")
        String color,

        @Size(max = 16, message = "尺码最长 16 字")
        String size,

        @Size(max = 64, message = "SKU 最长 64 字")
        String sku,

        // 对应库表 DECIMAL(10,2)
        @Digits(integer = 8, fraction = 2, message = "价格最多 8 位整数 + 2 位小数")
        BigDecimal price,

        // 空串合法，create/update 时回落到默认值 ON
        @Pattern(regexp = "(ON|OFF)?", message = "状态只能是 ON 或 OFF")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        // ---- 国际申报属性（44 号迁移新增，可选）----

        @Size(max = 12, message = "HS 编码最长 12 字")
        String hsCode,

        @Size(max = 255, message = "报关品名最长 255 字")
        String customsName,

        @Size(max = 255, message = "英文报关品名最长 255 字")
        String customsNameEn,

        @Pattern(regexp = "([A-Z]{2})?", message = "原产国只能是两位 ISO 代码，如 CN")
        String originCountry,

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

        @DecimalMin(value = "0", message = "净重不能为负")
        BigDecimal netWeight,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal grossWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal volumeCbm) {

    /** 补齐 payload 漏传的 id（PUT 时以路径 id 为准）。 */
    public ProductPayload withId(long newId) {
        return new ProductPayload(newId, styleNo, name, category, season, color, size, sku, price,
                status, remark, hsCode, customsName, customsNameEn, originCountry, isDangerous,
                unNumber, dgClass, packingInstruction, batteryWattHours, netWeight, grossWeight, volumeCbm);
    }
}
