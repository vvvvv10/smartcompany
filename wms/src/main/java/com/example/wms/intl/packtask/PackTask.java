package com.example.wms.intl.packtask;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 备货 / 装箱 / 贴标任务（一个出口子单一条主任务，明细在 wms_pack_task_items）。
 *
 * <p>orderNo 是 OMS 出口子单号，**跨库弱引用**：本服务不校验它在 OMS 存不存在
 * （校验要一次跨库 HTTP）。人工在页面上填单号建任务，这就是 M1 的备货联动方式——
 * OMS→WMS 的自动派单放 M2。</p>
 *
 * <p>warehouseName 是 LEFT JOIN 的展示列；仓库被删/跨租户不可见时为 null，
 * 渲染层显示「—」而不是抛异常（与 TMS 的 carrierName 同一口径）。</p>
 */
public record PackTask(
        Long id,
        String taskNo,
        String orderNo,
        Long warehouseId,
        String warehouseName,
        String locationCode,
        String taskType,
        String status,
        String boxNo,
        String containerType,
        Integer pieces,
        BigDecimal grossWeight,
        BigDecimal volume,
        BigDecimal volumetricWeight,
        String marks,
        Integer isDangerous,
        LocalDateTime labelPrintedAt,
        LocalDateTime handedOverAt,
        Long assigneeId,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<PackTask> ROW_MAPPER = (rs, i) -> new PackTask(
            rs.getLong("id"),
            rs.getString("task_no"),
            rs.getString("order_no"),
            rs.getObject("warehouse_id", Long.class),
            rs.getString("warehouse_name"),
            rs.getString("location_code"),
            rs.getString("task_type"),
            rs.getString("status"),
            rs.getString("box_no"),
            rs.getString("container_type"),
            rs.getObject("pieces", Integer.class),
            rs.getBigDecimal("gross_weight"),
            rs.getBigDecimal("volume"),
            rs.getBigDecimal("volumetric_weight"),
            rs.getString("marks"),
            rs.getObject("is_dangerous", Integer.class),
            rs.getObject("label_printed_at", LocalDateTime.class),
            rs.getObject("handed_over_at", LocalDateTime.class),
            rs.getObject("assignee_id", Long.class),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
