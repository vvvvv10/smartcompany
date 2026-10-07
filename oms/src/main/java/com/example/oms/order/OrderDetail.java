package com.example.oms.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情 = 订单主表字段 + 明细列表（一次请求拿到整单，前端不用再查第二遍）。
 *
 * <p>tmsStatus/tmsOrigin/tmsDestination/outQty 随主表回填（见 {@link Order}），
 * 详情接口经 LinkageService.enrichOne 聚合后展示「运单/出库」。</p>
 */
public record OrderDetail(
        Long id,
        String orderNo,
        String channel,
        String customerName,
        String phone,
        String address,
        String status,
        Integer totalQty,
        BigDecimal totalAmount,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String tmsStatus,
        String tmsOrigin,
        String tmsDestination,
        Integer outQty,
        List<OrderItem> items) {

    public static OrderDetail of(Order order, List<OrderItem> items) {
        return new OrderDetail(
                order.id(),
                order.orderNo(),
                order.channel(),
                order.customerName(),
                order.phone(),
                order.address(),
                order.status(),
                order.totalQty(),
                order.totalAmount(),
                order.remark(),
                order.createdAt(),
                order.updatedAt(),
                order.tmsStatus(),
                order.tmsOrigin(),
                order.tmsDestination(),
                order.outQty(),
                items);
    }
}
