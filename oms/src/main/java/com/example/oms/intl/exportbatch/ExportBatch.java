package com.example.oms.intl.exportbatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 出口母单（拼批：多张子单 → 一票货）。
 *
 * <p>母单是「运输与报关的主体」：订舱、报关、开航都挂在它身上，
 * 子单是它下面的分单。routeId/carrierId 是 TMS tms_routes / tms_carriers 的**跨库弱引用**，
 * 本服务不校验它们存在，查不到时前端显示「—」。</p>
 *
 * <p>cutoffAt 是 UTC 时刻（列名 _at）；etdDate/etaDate 是当地日期。</p>
 */
public record ExportBatch(
        Long id,
        String batchNo,
        String mode,
        String polCode,
        String polName,
        String podCode,
        String podName,
        Long routeId,
        Long carrierId,
        String containerType,
        Integer containerQty,
        String incoterm,
        String currency,
        Integer totalQty,
        BigDecimal totalWeight,
        BigDecimal totalVolume,
        BigDecimal volumetricWeight,
        LocalDate etdDate,
        LocalDate etaDate,
        LocalDateTime cutoffAt,
        String status,
        String remark,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** 非表字段：跨库聚合回的 TMS 运单号（列表「运单号」列）。 */
        String shipmentNo,
        /** 非表字段：TMS 运单状态。 */
        String shipmentStatus) {

    public static final RowMapper<ExportBatch> ROW_MAPPER = (rs, i) -> new ExportBatch(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getString("mode"),
            rs.getString("pol_code"),
            rs.getString("pol_name"),
            rs.getString("pod_code"),
            rs.getString("pod_name"),
            rs.getObject("route_id", Long.class),
            rs.getObject("carrier_id", Long.class),
            rs.getString("container_type"),
            rs.getObject("container_qty", Integer.class),
            rs.getString("incoterm"),
            rs.getString("currency"),
            rs.getObject("total_qty", Integer.class),
            rs.getBigDecimal("total_weight"),
            rs.getBigDecimal("total_volume"),
            rs.getBigDecimal("volumetric_weight"),
            rs.getObject("etd_date", LocalDate.class),
            rs.getObject("eta_date", LocalDate.class),
            rs.getObject("cutoff_at", LocalDateTime.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_by", Long.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("shipment_no"),
            rs.getString("shipment_status"));

    public ExportBatch withShipment(String shipmentNo, String shipmentStatus) {
        return new ExportBatch(id, batchNo, mode, polCode, polName, podCode, podName, routeId, carrierId,
                containerType, containerQty, incoterm, currency, totalQty, totalWeight, totalVolume,
                volumetricWeight, etdDate, etaDate, cutoffAt, status, remark, createdBy, createdAt,
                updatedAt, shipmentNo, shipmentStatus);
    }
}
