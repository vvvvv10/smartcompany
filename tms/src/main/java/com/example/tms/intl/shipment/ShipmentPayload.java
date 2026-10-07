package com.example.tms.intl.shipment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 国际运单创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p><b>更新只改「可改字段」</b>：容器/唛头/单证号/时效/备注。
 * 状态不在这里改（走 PATCH /{id}/status 走状态机），运输方案（承运商/航线）
 * 与货物汇总也不在这里改——它们被下游单据引用，改了会出现「箱还在旧柜里」。
 * 这不是保守，是让「一次修改能连带影响多少张单」这件事保持可数。</p>
 *
 * <p>items 为 null 表示不动箱明细；空列表表示清空箱明细。</p>
 */
public record ShipmentPayload(
        Long id,

        @Size(max = 32, message = "母单号最长 32 字")
        String batchNo,

        @Size(max = 32, message = "空运单号最长 32 字")
        String awbNo,

        @Size(max = 32, message = "海运提单号最长 32 字")
        String blNo,

        /**
         * BL 类型（P1-4）：MASTER=主单（船司签发给货代/NVOCC），HOUSE=分单（货代签发给
         * 真实货主）。一柜多张HBL 对应一张 MBL 是LCL 的常态——目的港清关与提货凭 HBL，
         * 船司只认 MBL，没有这个字段就答不出「向谁索赔、跟谁对账」。
         */
        @Pattern(regexp = "(MASTER|HOUSE)?", message = "BL 类型只能是 MASTER/HOUSE")
        String blType,

        /** BL 签发方（船司代码或货代 partner_code）。blType=HOUSE 时必填（Service 层校验）。 */
        @Size(max = 64, message = "BL 签发方最长 64 字")
        String blIssuer,

        @Size(max = 32, message = "订舱号最长 32 字")
        String bookingNo,

        Long sailingId,

        @Pattern(regexp = "(SEA|AIR|RAIL|TRUCK|EXPRESS|LCL)?",
                message = "运输方式只能是 SEA/AIR/RAIL/TRUCK/EXPRESS/LCL")
        String mode,

        @NotNull(message = "承运商不能为空")
        Long carrierId,

        @Size(max = 8, message = "起运港码最长 8 字")
        String polCode,

        @Size(max = 8, message = "目的港码最长 8 字")
        String podCode,

        @Pattern(regexp = "(20GP|40GP|40HQ|LCL)?", message = "箱型只能是 20GP/40GP/40HQ/LCL")
        String containerType,

        @Min(value = 0, message = "箱量不能为负")
        Integer containerQty,

        @Pattern(regexp = "(EXW|FCA|FOB|CFR|CIF|CPT|CIP|DAP|DPU|DDP)?",
                message = "贸易条款只能是 EXW/FCA/FOB/CFR/CIF/CPT/CIP/DAP/DPU/DDP")
        String incoterm,

        @Pattern(regexp = "([A-Z]{3})?", message = "币种只能是三位 ISO 代码，如 USD")
        String currency,

        @Min(value = 0, message = "件数不能为负")
        Integer totalPieces,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal totalGrossWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal totalVolume,

        @DecimalMin(value = "0", message = "体积重不能为负")
        BigDecimal volumetricWeight,

        /**
         * 计费重。不填则由服务端按模式回算（见 ShipmentService.recalcWeights）：
         * 海运取 W/M、空运与快递取 max(毛重, 体积重)。手工填是为了「谈好的价不是
         * 算出来的价」这种情况——但那样必须能覆盖回算值。
         */
        @DecimalMin(value = "0", message = "计费重不能为负")
        BigDecimal chargeableWeight,

        @DecimalMin(value = "0", message = "VGM 不能为负")
        BigDecimal vgmWeight,

        /**
         * 集装箱自重（吨）。SOLAS VGM Method 2 必需项：核实总重 = Σ(货物+包装+绑扎)
         * + 集装箱自重。40HQ 柜约 3.9 吨——不把它算进 VGM，等于把申报重量少报几吨，
         * 船司会以SOLAS「未取得 VGM 的柜不得装载」直接拒载。
         */
        @DecimalMin(value = "0", message = "集装箱自重不能为负")
        BigDecimal containerTareWeight,

        @Size(max = 255, message = "唛头最长 255 字")
        String marks,

        LocalDateTime etdAt,

        LocalDateTime etaAt,

        LocalDateTime cutoffAt,

        @Min(value = 0, message = "危险品标记只能是 0/1")
        @Max(value = 1, message = "危险品标记只能是 0/1")
        Integer isDangerous,

        @Size(max = 16, message = "UN 编号最长 16 字")
        String unNumber,

        @Size(max = 8, message = "危险品类别最长 8 字")
        String dgClass,

        @Pattern(regexp = "(CLEAR|PENDING|HIT)?", message = "制裁筛查只能是 CLEAR/PENDING/HIT")
        String sanctionFlag,

        @Size(max = 32, message = "IOSS 编号最长 32 字")
        String iossNo,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        List<ShipmentItemPayload> items) {

    /** 重建一份带上路径 id 的 payload（PUT 时 payload.id() 可能漏传）。 */
    public ShipmentPayload withId(long newId) {
        return new ShipmentPayload(newId, batchNo, awbNo, blNo, blType, blIssuer, bookingNo, sailingId, mode, carrierId,
                polCode, podCode, containerType, containerQty, incoterm, currency, totalPieces,
                totalGrossWeight, totalVolume, volumetricWeight, chargeableWeight, vgmWeight, containerTareWeight, marks,
                etdAt, etaAt, cutoffAt, isDangerous, unNumber, dgClass, sanctionFlag, iossNo, remark, items);
    }
}
