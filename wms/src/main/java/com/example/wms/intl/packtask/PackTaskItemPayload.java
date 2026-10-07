package com.example.wms.intl.packtask;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 备货任务明细行入参。
 *
 * <p>quantity（应备）必填且 ≥0；pickedQuantity 缺省取应备——
 * 拣完没缺就是实备=应备，缺的那一栏留空在 PATCH 状态时再补。</p>
 */
public record PackTaskItemPayload(
        Long id,

        @NotBlank(message = "SKU 不能为空")
        @Size(max = 64, message = "SKU 最长 64 字")
        String sku,

        @Size(max = 128, message = "品名最长 128 字")
        String productName,

        @Size(max = 32, message = "批次号最长 32 字")
        String batchNo,

        LocalDate productionDate,

        LocalDate expiryDate,

        @Min(value = 0, message = "应备数量不能为负")
        Integer quantity,

        @Min(value = 0, message = "实备数量不能为负")
        Integer pickedQuantity,

        @Size(max = 12, message = "HS 编码最长 12 字")
        String hsCode) {
}
