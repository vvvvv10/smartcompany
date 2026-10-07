package com.example.oms.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 订单主表。
 *
 * <p>status: UNPAID 待付款 / TO_SHIP 待发货 / SHIPPED 已发货 / DONE 已完成 /
 * AFTER_SALE 售后 / CANCELLED 已取消</p>
 *
 * <p>channel: DOUYIN 抖音 / TAOBAO 淘宝 / P1688 批发 / OFFLINE 线下 / WECHAT 微信</p>
 *
 * <p>tmsStatus/tmsOrigin/tmsDestination/outQty 不是表字段：BASE_SELECT 用
 * NULL 占位（列表/brief 先为 null），查询后再由 LinkageService 按订单号批量
 * 查 TMS/WMS 回填（{@link #withLinkage}）。下游故障时保持 null，列显示 —。</p>
 */
public record Order(
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
        Integer outQty) {

    public static final RowMapper<Order> ROW_MAPPER = (rs, i) -> new Order(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("channel"),
            rs.getString("customer_name"),
            rs.getString("phone"),
            rs.getString("address"),
            rs.getString("status"),
            rs.getObject("total_qty", Integer.class),
            rs.getBigDecimal("total_amount"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("tms_status"),
            rs.getString("tms_origin"),
            rs.getString("tms_destination"),
            rs.getObject("out_qty", Integer.class));

    /** 回填关联字段的副本（record 不可变，聚合结果要覆盖写回）。 */
    public Order withLinkage(String tmsStatus, String tmsOrigin, String tmsDestination, Integer outQty) {
        return new Order(
                id, orderNo, channel, customerName, phone, address, status,
                totalQty, totalAmount, remark, createdAt, updatedAt,
                tmsStatus, tmsOrigin, tmsDestination, outQty);
    }
}
