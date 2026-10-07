package com.example.wms.intl.packtask;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * PATCH /pack-tasks/{id}/status 入参。
 *
 * <p>装箱（PACKED）时可以顺带带上箱号/唛头/毛重体积：
 * 这些是「装箱时才知道」的数据，让它们和状态在同一个请求里落库，
 * 比让人先改状态再发一次改箱号的请求要少一次中间态
 * （不会出现「已装箱但没有箱号」而没人知道）。</p>
 */
public record PackTaskStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(PENDING|PICKING|PACKED|LABELLED|HANDED_OVER|SHORTAGE|CANCELLED)",
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
        String remark) {
}
