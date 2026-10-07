package com.example.wms.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 库存创建/更新入参。id 为空是创建，非空是更新。 */
public record InventoryPayload(
        Long id,

        @NotNull(message = "仓库 ID 不能为空")
        Long warehouseId,

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 128, message = "商品名称最长 128 字")
        String productName,

        @NotBlank(message = "SKU 不能为空")
        @Size(max = 64, message = "SKU 最长 64 字")
        String sku,

        @NotNull(message = "数量不能为空")
        Integer quantity) {
}
