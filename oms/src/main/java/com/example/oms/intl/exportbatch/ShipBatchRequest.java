package com.example.oms.intl.exportbatch;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /batches/{id}/ship（母单「发起订舱」）的入参。
 *
 * <p>只有运输方案相关的字段：承运商、方式、起运港/目的港、箱型箱量。
 * ETD/ETA 缺省时取母单上已填的日期，截关缺省取母单的 cutoffAt——
 * 订舱时再填一遍同样的日期，是运营最容易出错的地方（填错一处就出现
 * 「母单说 10-14 开船、订舱单说 10-12」）。</p>
 */
public record ShipBatchRequest(
        Long carrierId,

        @Pattern(regexp = "(SEA|LCL|AIR|RAIL|TRUCK|EXPRESS)?",
                message = "运输方式只能是 SEA/LCL/AIR/RAIL/TRUCK/EXPRESS")
        String mode,

        @Size(max = 8, message = "起运港码最长 8 字")
        String polCode,

        @Size(max = 8, message = "目的港码最长 8 字")
        String podCode,

        @Pattern(regexp = "(20GP|40GP|40HQ|LCL)?", message = "箱型只能是 20GP/40GP/40HQ/LCL")
        String containerType,

        @Min(value = 0, message = "箱量不能为负")
        Integer containerQty,

        Long sailingId,

        String awbNo,

        String blNo,

        String bookingNo,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
