package com.example.wms.intl.packtask;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 备货任务创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>orderNo 是 OMS 出口子单号（必填）——M1 的备货是**弱联动**：
 * 人工在 WMS 页面上填单号建任务，WMS 不回查 OMS。
 * 等 M2 加了 OMS→WMS 的非阻塞回调之后，才由「填单号」变成「派单」。</p>
 *
 * <p>status 在这里可以传但只用于**创建时定初值**；推进状态一律走 PATCH /{id}/status，
 * 那条路径才有状态机校验。</p>
 */
public record PackTaskPayload(
        Long id,

        @NotBlank(message = "出口子单号不能为空")
        @Size(max = 32, message = "出口子单号最长 32 字")
        String orderNo,

        @NotNull(message = "作业仓库不能为空")
        Long warehouseId,

        @Size(max = 32, message = "库位编码最长 32 字")
        String locationCode,

        @Pattern(regexp = "(PACK|LABEL)?", message = "任务类型只能是 PACK 或 LABEL")
        String taskType,

        @Pattern(regexp = "(PENDING|PICKING|PACKED|LABELLED|HANDED_OVER|SHORTAGE|CANCELLED)?",
                message = "不是合法的备货任务状态")
        String status,

        @Size(max = 32, message = "箱号最长 32 字")
        String boxNo,

        @Pattern(regexp = "(CARTON|PALLET|BAG|DRUM)?", message = "外包装只能是 CARTON/PALLET/BAG/DRUM")
        String containerType,

        @Min(value = 0, message = "件数不能为负")
        Integer pieces,

        @DecimalMin(value = "0", message = "毛重不能为负")
        BigDecimal grossWeight,

        @DecimalMin(value = "0", message = "体积不能为负")
        BigDecimal volume,

        @DecimalMin(value = "0", message = "体积重不能为负")
        BigDecimal volumetricWeight,

        @Size(max = 255, message = "唛头最长 255 字")
        String marks,

        @Min(value = 0, message = "危险品标记只能是 0/1")
        Integer isDangerous,

        Long assigneeId,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        List<PackTaskItemPayload> items) {

    public PackTaskPayload withId(long newId) {
        return new PackTaskPayload(newId, orderNo, warehouseId, locationCode, taskType, status, boxNo,
                containerType, pieces, grossWeight, volume, volumetricWeight, marks, isDangerous,
                assigneeId, remark, items);
    }
}
