package com.example.wms.intl.intransit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 在途库存创建入参。
 *
 * <p>shippedAt 必填（UTC）——在途行的意义就是「什么时候出的库」，
 * 缺了这个时间就没法算在途天数，也没法回答「这批货走了多久」。</p>
 */
public record InTransitPayload(
        @NotBlank(message = "运单号不能为空")
        @Size(max = 32, message = "运单号最长 32 字")
        String shipmentNo,

        @Size(max = 32, message = "出口子单号最长 32 字")
        String orderNo,

        @Size(max = 64, message = "SKU 最长 64 字")
        String sku,

        @Size(max = 128, message = "品名最长 128 字")
        String productName,

        @Size(max = 32, message = "批次号最长 32 字")
        String batchNo,

        @NotNull(message = "在途数量不能为空")
        @Min(value = 1, message = "在途数量至少 1")
        Integer quantity,

        Long fromWarehouseId,

        Long toWarehouseId,

        @NotNull(message = "出库时间不能为空")
        LocalDateTime shippedAt,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
