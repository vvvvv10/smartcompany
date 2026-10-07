package com.example.tms.intl.sailing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 船期创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>etdAt/etaAt 收 ISO 字符串（前端发 {@code 2026-10-14T01:00:00}），约定一律是 **UTC**：
 * 服务端不做任何时区转换，转换点只留在展示层，避免「谁转了一次」说不清。</p>
 */
public record SailingPayload(
        Long id,

        @NotNull(message = "航线不能为空")
        Long routeId,

        @Size(max = 64, message = "船名/航班号最长 64 字")
        String vesselName,

        @Size(max = 32, message = "航次号最长 32 字")
        String voyageNo,

        @NotNull(message = "ETD 不能为空")
        LocalDateTime etdAt,

        @NotNull(message = "ETA 不能为空")
        LocalDateTime etaAt,

        /** 截关时间。留空时按承运商 cut_off_hours 从 ETD 反推，不强制填。 */
        LocalDateTime cutoffAt,

        @Min(value = 0, message = "免堆存天数不能为负")
        @Max(value = 90, message = "免堆存天数最多 90")
        Integer freeTimeDays,

        @Min(value = 0, message = "剩余舱位不能为负")
        @Max(value = 99999, message = "剩余舱位最多 99999")
        Integer spaceLeft,

        @Pattern(regexp = "(SCHEDULED|BOOKING|CLOSED|CANCELLED)?",
                message = "船期状态只能是 SCHEDULED/BOOKING/CLOSED/CANCELLED")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
