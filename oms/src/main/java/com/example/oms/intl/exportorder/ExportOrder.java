package com.example.oms.intl.exportorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 出口订单（子单）——客户的一笔货。
 *
 * <p>下面四个聚合列（batchStatus/shipmentNo/shipmentStatus/packStatus/awbNo/bookingNo/etdAt/etaAt）
 * 不是表字段：BASE_SELECT 用 NULL 占位，查询后由 {@link com.example.oms.linkage.LinkageService}
 * 按 batch_no / order_no 批量跨库回填。**下游挂了各自降级为 null，列显示「—」**，
 * 绝不因为 TMS/WMS 不可用就让整个出口订单列表打不开（同 39 号 enrich 的口径）。</p>
 *
 * <p>date 型（etdDate/etaDate/readyDate）是**当地日期**（ETD 当地是 10-14 就写 2026-10-14），
 * 不做时区换算；etdAt/etaAt 则是 UTC 时刻。两者并存是有意的：
 * 「几号开船」是业务事实，「几点开船」才是需要换算的时刻。</p>
 */
public record ExportOrder(
        Long id,
        String orderNo,
        String batchNo,
        Integer isBatchLocked,
        Long customerId,
        String customerName,
        String consigneeName,
        String consigneeCompany,
        String consigneeCountry,
        String consigneeAddress,
        String consigneePhone,
        String consigneeEmail,
        String incoterm,
        String currency,
        BigDecimal totalAmount,
        BigDecimal freightAmount,
        Integer totalQty,
        BigDecimal totalWeight,
        BigDecimal totalVolume,
        BigDecimal volumetricWeight,
        LocalDate etdDate,
        LocalDate etaDate,
        LocalDate readyDate,
        String status,
        String remark,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // ---- 以下为跨库聚合列，非表字段 ----
        String batchStatus,
        String shipmentNo,
        String shipmentStatus,
        String awbNo,
        String bookingNo,
        LocalDateTime etdAt,
        LocalDateTime etaAt,
        String packStatus,
        String boxNo) {

    public static final RowMapper<ExportOrder> ROW_MAPPER = (rs, i) -> new ExportOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("batch_no"),
            rs.getObject("is_batch_locked", Integer.class),
            rs.getObject("customer_id", Long.class),
            rs.getString("customer_name"),
            rs.getString("consignee_name"),
            rs.getString("consignee_company"),
            rs.getString("consignee_country"),
            rs.getString("consignee_address"),
            rs.getString("consignee_phone"),
            rs.getString("consignee_email"),
            rs.getString("incoterm"),
            rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("freight_amount"),
            rs.getObject("total_qty", Integer.class),
            rs.getBigDecimal("total_weight"),
            rs.getBigDecimal("total_volume"),
            rs.getBigDecimal("volumetric_weight"),
            rs.getObject("etd_date", LocalDate.class),
            rs.getObject("eta_date", LocalDate.class),
            rs.getObject("ready_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_by", Long.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("batch_status"),
            rs.getString("shipment_no"),
            rs.getString("shipment_status"),
            rs.getString("awb_no"),
            rs.getString("booking_no"),
            rs.getObject("etd_at", LocalDateTime.class),
            rs.getObject("eta_at", LocalDateTime.class),
            rs.getString("pack_status"),
            rs.getString("box_no"));

    /**
     * 回填聚合列的副本（record 不可变，聚合结果必须整体覆盖写回）。
     * 全量重建 record 是这个仓库的既有风格（Order.withLinkage 同款）：
     * 比「加 8 个可变字段」更安全——加字段时编译器会强制这里同步改。
     */
    public ExportOrder withLinkage(String batchStatus, String shipmentNo, String shipmentStatus,
                                   String awbNo, String bookingNo, LocalDateTime etdAt, LocalDateTime etaAt,
                                   String packStatus, String boxNo) {
        return new ExportOrder(id, orderNo, batchNo, isBatchLocked, customerId, customerName,
                consigneeName, consigneeCompany, consigneeCountry, consigneeAddress, consigneePhone,
                consigneeEmail, incoterm, currency, totalAmount, freightAmount, totalQty, totalWeight,
                totalVolume, volumetricWeight, etdDate, etaDate, readyDate, status, remark, createdBy,
                createdAt, updatedAt, batchStatus, shipmentNo, shipmentStatus, awbNo, bookingNo,
                etdAt, etaAt, packStatus, boxNo);
    }
}
