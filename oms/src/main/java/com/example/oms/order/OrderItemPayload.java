package com.example.oms.order;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 订单明细入参。
 *
 * <p>字段都可选：给了 productId 时，款号/品名/颜色/尺码/价格缺哪项就回表
 * 从 oms_products 补哪项；数量缺省为 1，价格缺省为 0。</p>
 */
public record OrderItemPayload(
        Long id,
        Long productId,

        @Size(max = 32, message = "款号最长 32 字")
        String styleNo,

        @Size(max = 128, message = "品名最长 128 字")
        String productName,

        @Size(max = 32, message = "颜色最长 32 字")
        String color,

        @Size(max = 16, message = "尺码最长 16 字")
        String size,

        Integer quantity,

        // 对应库表 DECIMAL(10,2)
        @Digits(integer = 8, fraction = 2, message = "单价最多 8 位整数 + 2 位小数")
        BigDecimal price) {
}
