package com.example.wms.intl.batch;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存批次创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>uk_wh_sku_batch(warehouse_id, sku, batch_no) 是幂等键也是真实业务键：
 * 同一仓同一 SKU 同一批次号只能有一行——批次号就是货的身份证，
 * 出现两行意味着有人重复入库了。</p>
 *
 * <p>reservedQty 与 quantity 一起校验（占用不能超过在库），
 * 否则「可用量」会算成负数，而负数在页面上看着像 bug，排查又找不到源头。</p>
 */
public record InventoryBatchPayload(
        Long id,

        @NotNull(message = "仓库不能为空")
        Long warehouseId,

        @NotBlank(message = "SKU 不能为空")
        @Size(max = 64, message = "SKU 最长 64 字")
        String sku,

        @Size(max = 128, message = "品名最长 128 字")
        String productName,

        @NotBlank(message = "批次号不能为空")
        @Size(max = 32, message = "批次号最长 32 字")
        String batchNo,

        LocalDate productionDate,

        LocalDate expiryDate,

        @Min(value = 0, message = "在库数量不能为负")
        Integer quantity,

        @Min(value = 0, message = "占用数量不能为负")
        Integer reservedQty,

        @DecimalMin(value = "0", message = "单位成本不能为负")
        BigDecimal unitCost,

        @Size(max = 32, message = "库位编码最长 32 字")
        String locationCode,

        @Pattern(regexp = "(NORMAL|NEAR_EXPIRY|EXPIRED|FROZEN)?",
                message = "批次状态只能是 NORMAL/NEAR_EXPIRY/EXPIRED/FROZEN")
        String status) {

    public InventoryBatchPayload withId(long newId) {
        return new InventoryBatchPayload(newId, warehouseId, sku, productName, batchNo, productionDate,
                expiryDate, quantity, reservedQty, unitCost, locationCode, status);
    }
}
