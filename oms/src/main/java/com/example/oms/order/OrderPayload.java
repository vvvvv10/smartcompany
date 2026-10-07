package com.example.oms.order;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 订单创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>items 为 null 表示"不动明细"（更新时保留原明细）；为空列表表示清空明细。
 * status/channel 留空时走默认值 UNPAID / OFFLINE。</p>
 */
public record OrderPayload(
        Long id,

        @Size(max = 32, message = "订单号最长 32 字")
        String orderNo,

        @Pattern(regexp = "(DOUYIN|TAOBAO|P1688|OFFLINE|WECHAT)?",
                message = "渠道只能是 DOUYIN/TAOBAO/P1688/OFFLINE/WECHAT")
        String channel,

        @Size(max = 64, message = "客户名称最长 64 字")
        String customerName,

        @Size(max = 20, message = "电话最长 20 字")
        String phone,

        @Size(max = 255, message = "地址最长 255 字")
        String address,

        @Pattern(regexp = "(UNPAID|TO_SHIP|SHIPPED|DONE|AFTER_SALE|CANCELLED)?",
                message = "状态只能是 UNPAID/TO_SHIP/SHIPPED/DONE/AFTER_SALE/CANCELLED")
        String status,

        Integer totalQty,

        // 对应库表 DECIMAL(10,2)；不传就按明细 quantity × price 合计
        @Digits(integer = 8, fraction = 2, message = "金额最多 8 位整数 + 2 位小数")
        BigDecimal totalAmount,

        @Size(max = 255, message = "备注最长 255 字")
        String remark,

        List<OrderItemPayload> items) {
}
