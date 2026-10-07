package com.example.oms.intl.exportorder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 出口单明细（报关申报要素的一行）。
 *
 * <p>hsCode/customsName/unNumber/dgClass 这些列是**商品档案的快照**：
 * 建单时从 oms_products 带出，之后商品档案改了也不动这张已报关的单子——
 * 报关单已经提交了，改它等于篡改历史凭证。</p>
 */
public record ExportOrderItem(
        Long id,
        Long orderId,
        String orderNo,
        Integer lineNo,
        Long productId,
        String sku,
        String productName,
        String customsName,
        String customsNameEn,
        String hsCode,
        String originCountry,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal declaredValue,
        BigDecimal netWeight,
        BigDecimal grossWeight,
        BigDecimal volume,
        Integer isDangerous,
        String unNumber,
        String dgClass,
        String packingInstruction,
        BigDecimal batteryWattHours,
        String remark,
        LocalDateTime createdAt) {

    public static final RowMapper<ExportOrderItem> ROW_MAPPER = (rs, i) -> new ExportOrderItem(
            rs.getLong("id"),
            rs.getObject("order_id", Long.class),
            rs.getString("order_no"),
            rs.getObject("line_no", Integer.class),
            rs.getObject("product_id", Long.class),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getString("customs_name"),
            rs.getString("customs_name_en"),
            rs.getString("hs_code"),
            rs.getString("origin_country"),
            rs.getObject("quantity", Integer.class),
            rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("declared_value"),
            rs.getBigDecimal("net_weight"),
            rs.getBigDecimal("gross_weight"),
            rs.getBigDecimal("volume"),
            rs.getObject("is_dangerous", Integer.class),
            rs.getString("un_number"),
            rs.getString("dg_class"),
            rs.getString("packing_instruction"),
            rs.getBigDecimal("battery_watt_hours"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class));
}
