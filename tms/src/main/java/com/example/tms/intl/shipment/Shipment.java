package com.example.tms.intl.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 国际运单（主单 / Master Bill）。
 *
 * <p>batchNo 是与 OMS 出口母单的**唯一关联键**（跨库弱引用，没有外键）。
 * 运输事实以本表 status 为准：OMS 侧只做展示，允许滞后（计划 §11 R1）。</p>
 *
 * <p>carrierName / polName 这类展示列来自 LEFT JOIN，查不到就是 null（前端显「—」），
 * 因为 carrier_id 是跨库弱引用，对方的行随时可能没了——**渲染层不得因为悬空引用报错**。</p>
 *
 * <p>所有 {@code *At} 列都是 UTC；前端 formatUtc 补 Z 再按 Asia/Shanghai 展示。</p>
 */
public record Shipment(
        Long id,
        String shipmentNo,
        String batchNo,
        String awbNo,
        String blNo,
        /** BL 类型：MASTER 主单（船司签发）/ HOUSE 分单（货代签发）。P1-4。 */
        String blType,
        /** BL 签发方（船司代码或货代 partner_code）。P1-4。 */
        String blIssuer,
        String bookingNo,
        Long sailingId,
        String mode,
        Long carrierId,
        String carrierName,
        String polCode,
        String podCode,
        String containerType,
        Integer containerQty,
        String incoterm,
        String currency,
        Integer totalPieces,
        BigDecimal totalGrossWeight,
        BigDecimal totalVolume,
        BigDecimal volumetricWeight,
        BigDecimal chargeableWeight,
        BigDecimal vgmWeight,
        /** 集装箱自重（吨）。SOLAS VGM Method 2 = Σ(货物+包装+绑扎) + 此值。P1-2。 */
        BigDecimal containerTareWeight,
        String marks,
        LocalDateTime etdAt,
        LocalDateTime etaAt,
        LocalDateTime cutoffAt,
        LocalDateTime actualEtdAt,
        LocalDateTime actualEtaAt,
        LocalDateTime podReceivedAt,
        Integer isDangerous,
        String unNumber,
        String dgClass,
        String sanctionFlag,
        String iossNo,
        String status,
        String exceptionReason,
        String remark,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<Shipment> ROW_MAPPER = (rs, i) -> new Shipment(
            rs.getLong("id"),
            rs.getString("shipment_no"),
            rs.getString("batch_no"),
            rs.getString("awb_no"),
            rs.getString("bl_no"),
            rs.getString("bl_type"),
            rs.getString("bl_issuer"),
            rs.getString("booking_no"),
            rs.getObject("sailing_id", Long.class),
            rs.getString("mode"),
            rs.getObject("carrier_id", Long.class),
            rs.getString("carrier_name"),
            rs.getString("pol_code"),
            rs.getString("pod_code"),
            rs.getString("container_type"),
            rs.getObject("container_qty", Integer.class),
            rs.getString("incoterm"),
            rs.getString("currency"),
            rs.getObject("total_pieces", Integer.class),
            rs.getBigDecimal("total_gross_weight"),
            rs.getBigDecimal("total_volume"),
            rs.getBigDecimal("volumetric_weight"),
            rs.getBigDecimal("chargeable_weight"),
            rs.getBigDecimal("vgm_weight"),
            rs.getBigDecimal("container_tare_weight"),
            rs.getString("marks"),
            rs.getObject("etd_at", LocalDateTime.class),
            rs.getObject("eta_at", LocalDateTime.class),
            rs.getObject("cutoff_at", LocalDateTime.class),
            rs.getObject("actual_etd_at", LocalDateTime.class),
            rs.getObject("actual_eta_at", LocalDateTime.class),
            rs.getObject("pod_received_at", LocalDateTime.class),
            rs.getObject("is_dangerous", Integer.class),
            rs.getString("un_number"),
            rs.getString("dg_class"),
            rs.getString("sanction_flag"),
            rs.getString("ioss_no"),
            rs.getString("status"),
            rs.getString("exception_reason"),
            rs.getString("remark"),
            rs.getObject("created_by", Long.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
