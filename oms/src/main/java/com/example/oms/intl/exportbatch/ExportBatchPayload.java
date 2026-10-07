package com.example.oms.intl.exportbatch;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出口母单创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>orderNos 是组批入口：创建时传它就是「新组一批」；更新时传它会**追加**成员
 * （成员必须未组批或属于本批）。清空成员请走 DELETE /{id}/orders/{orderNo}，
 * 不用「传一个空列表」——那分不清是「不动成员」还是「清空成员」。</p>
 *
 * <p>totalQty/totalWeight/totalVolume **不用传**：母单的汇总一律从子单回算，
 * 传了也会被覆盖。留着字段是为了让 payload 的形状与库里一致，便于将来做只读展示。</p>
 */
public record ExportBatchPayload(
        Long id,

        @Size(max = 32, message = "母单号最长 32 字")
        String batchNo,

        @Pattern(regexp = "(SEA|LCL|AIR|RAIL|TRUCK|EXPRESS)?",
                message = "运输方式只能是 SEA/LCL/AIR/RAIL/TRUCK/EXPRESS")
        String mode,

        @Size(max = 8, message = "起运港码最长 8 字")
        String polCode,

        @Size(max = 64, message = "起运港名最长 64 字")
        String polName,

        @Size(max = 8, message = "目的港码最长 8 字")
        String podCode,

        @Size(max = 64, message = "目的港名最长 64 字")
        String podName,

        Long routeId,

        Long carrierId,

        @Pattern(regexp = "(20GP|40GP|40HQ|LCL)?", message = "箱型只能是 20GP/40GP/40HQ/LCL")
        String containerType,

        @Min(value = 0, message = "箱量不能为负")
        Integer containerQty,

        @Pattern(regexp = "(EXW|FCA|FOB|CFR|CIF|CPT|CIP|DAP|DPU|DDP)?",
                message = "贸易条款只能是 EXW/FCA/FOB/CFR/CIF/CPT/CIP/DAP/DPU/DDP")
        String incoterm,

        @Pattern(regexp = "([A-Z]{3})?", message = "币种只能是三位 ISO 代码，如 USD")
        String currency,

        Integer totalQty,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal totalWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal totalVolume,

        @DecimalMin(value = "0", message = "体积重不能为负")
        BigDecimal volumetricWeight,

        LocalDate etdDate,

        LocalDate etaDate,

        /** 截关/截仓（UTC 时刻）。 */
        LocalDateTime cutoffAt,

        @Pattern(regexp = "(DRAFT|CONFIRMED|WAITING_GOODS|BOOKING|BOOKED|LOADING|DEPARTED|IN_TRANSIT"
                + "|ARRIVED|CLEARING|CLEARED|LAST_MILE|DELIVERED|CLOSED|EXCEPTION|CANCELLED)?",
                message = "不是合法的出口母单状态")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        List<String> orderNos) {

    public ExportBatchPayload withId(long newId) {
        return new ExportBatchPayload(newId, batchNo, mode, polCode, polName, podCode, podName, routeId,
                carrierId, containerType, containerQty, incoterm, currency, totalQty, totalWeight,
                totalVolume, volumetricWeight, etdDate, etaDate, cutoffAt, status, remark, orderNos);
    }
}
