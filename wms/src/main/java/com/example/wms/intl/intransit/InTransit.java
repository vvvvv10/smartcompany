package com.example.wms.intl.intransit;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 在途库存（已出库未入库的货）。
 *
 * <p>shipmentNo 是 TMS 运单号、orderNo 是 OMS 出口子单号——**两个都是跨库弱引用**，
 * 本服务不校验它们在对方库是否存在（跨库校验要网络调用）。
 * 列表里「运单号/子单号」列显示不出来时显示原字符串（这是货的唯一标识，
 * 总比「—」有用），而「在哪个仓」这类本地字段才可能为 null。</p>
 *
 * <p>shippedAt/arrivedAt 是 UTC 时刻；列表页按 Asia/Shanghai 展示。</p>
 *
 * <p>status 里 HELD（海关查验扣留）与 LOST（丢件）必须分开：HELD 是货在海关手里、
 * 责任未定，LOST 有理赔/索赔后果并会进丢件率看板。合成一个状态，演示数据就在说谎。</p>
 *
 * <p>remark 解释「为什么是这个状态」（查验单号 / 索赔工单号）。状态本身不解释原因，
 * 而在途表一行只能一个状态，无法从行内推断出这批货到底出了什么事。</p>
 */
public record InTransit(
        Long id,
        String shipmentNo,
        String orderNo,
        String sku,
        String productName,
        String batchNo,
        Integer quantity,
        Long fromWarehouseId,
        String fromWarehouseName,
        Long toWarehouseId,
        String toWarehouseName,
        LocalDateTime shippedAt,
        LocalDateTime arrivedAt,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<InTransit> ROW_MAPPER = (rs, i) -> new InTransit(
            rs.getLong("id"),
            rs.getString("shipment_no"),
            rs.getString("order_no"),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getString("batch_no"),
            rs.getObject("quantity", Integer.class),
            rs.getObject("from_warehouse_id", Long.class),
            rs.getString("from_warehouse_name"),
            rs.getObject("to_warehouse_id", Long.class),
            rs.getString("to_warehouse_name"),
            rs.getObject("shipped_at", LocalDateTime.class),
            rs.getObject("arrived_at", LocalDateTime.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
