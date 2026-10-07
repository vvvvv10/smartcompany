package com.example.tms.intl.shipment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 运单箱明细 / 分单入参。
 *
 * <p>orderNo 是 OMS 出口子单号（跨库弱引用字符串），houseNo 是 House B/L 号
 * （业务上 = 母单号-两位序号）。containerNo 只做长度校验不做 ISO 6346 校验位校验：
 * 校验位算法没有在本仓找到权威实现（计划 §13 待验证第 4 项），
 * 与其写一个可能算错的校验，不如只存字符串、由人工对着提单核对。</p>
 */
public record ShipmentItemPayload(
        Long id,

        @Size(max = 32, message = "出口子单号最长 32 字")
        String orderNo,

        @Size(max = 32, message = "分单号最长 32 字")
        String houseNo,

        @Size(max = 16, message = "集装箱号最长 16 字")
        String containerNo,

        @Size(max = 8, message = "箱型最长 8 字")
        String containerType,

        @Size(max = 16, message = "封条号最长 16 字")
        String sealNo,

        @Size(max = 255, message = "唛头最长 255 字")
        String marks,

        @Min(value = 0, message = "件数不能为负")
        Integer pieces,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal grossWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal volume,

        @DecimalMin(value = "0", message = "体积重不能为负")
        BigDecimal volumetricWeight,

        @Min(value = 0, message = "危险品标记只能是 0/1")
        @Max(value = 1, message = "危险品标记只能是 0/1")
        Integer isDangerous,

        @Size(max = 16, message = "UN 编号最长 16 字")
        String unNumber,

        @Size(max = 8, message = "危险品类别最长 8 字")
        String dgClass) {

    public ShipmentItemPayload withId(long newId) {
        return new ShipmentItemPayload(newId, orderNo, houseNo, containerNo, containerType, sealNo, marks,
                pieces, grossWeight, volume, volumetricWeight, isDangerous, unNumber, dgClass);
    }
}
