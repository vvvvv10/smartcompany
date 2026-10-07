package com.example.wms.stockrecord;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 出入库记录创建入参。 */
public record StockRecordPayload(

        @NotNull(message = "仓库 ID 不能为空")
        Long warehouseId,

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 128, message = "商品名称最长 128 字")
        String productName,

        @NotBlank(message = "类型不能为空")
        @Pattern(regexp = "IN|OUT", message = "类型只能是 IN 或 OUT")
        String type,

        @NotNull(message = "数量不能为空")
        Integer quantity,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        /** 关联的 OMS 订单号（选填；OMS 发货联动建出库时回填，手工建可不填）。 */
        @Size(max = 32, message = "关联订单号最长 32 字")
        String orderNo) {
}
