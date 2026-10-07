package com.example.tms.intl.shipment;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import org.springframework.jdbc.core.RowMapper;

/** 运单箱明细 / 分单（一个 House B/L 或一个实箱）。 */
public record ShipmentItem(
        Long id,
        Long shipmentId,
        String shipmentNo,
        String orderNo,
        String houseNo,
        String containerNo,
        String containerType,
        String sealNo,
        String marks,
        Integer pieces,
        BigDecimal grossWeight,
        BigDecimal volume,
        BigDecimal volumetricWeight,
        Integer isDangerous,
        String unNumber,
        String dgClass,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<ShipmentItem> ROW_MAPPER = (rs, i) -> new ShipmentItem(
            rs.getLong("id"),
            rs.getObject("shipment_id", Long.class),
            rs.getString("shipment_no"),
            rs.getString("order_no"),
            rs.getString("house_no"),
            rs.getString("container_no"),
            rs.getString("container_type"),
            rs.getString("seal_no"),
            rs.getString("marks"),
            rs.getObject("pieces", Integer.class),
            rs.getBigDecimal("gross_weight"),
            rs.getBigDecimal("volume"),
            rs.getBigDecimal("volumetric_weight"),
            rs.getObject("is_dangerous", Integer.class),
            rs.getString("un_number"),
            rs.getString("dg_class"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
