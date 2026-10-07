package com.example.tms.intl.customs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 出口报关单。
 *
 * <p>一个运单一张报关单（一票货一票报关）。关单号位数与编码规则【待验证】，
 * 所以这里只存 VARCHAR(32)，不写死 18 位。</p>
 *
 * <p>所有金额与 currency 成对：报关单上的货值/关税/VAT/消费税/保证金
 * 都可能被单独引用，缺了币种就没法算「这票货海关收了多少」。</p>
 */
public record CustomsDeclaration(
        Long id,
        String declarationNo,
        Long shipmentId,
        String shipmentNo,
        String batchNo,
        String customsBroker,
        String brokerContact,
        String hsCodeSummary,
        BigDecimal declaredValue,
        String currency,
        BigDecimal dutyAmount,
        BigDecimal vatAmount,
        BigDecimal consumptionTax,
        BigDecimal depositAmount,
        String status,
        LocalDateTime filedAt,
        LocalDateTime releasedAt,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<CustomsDeclaration> ROW_MAPPER = (rs, i) -> new CustomsDeclaration(
            rs.getLong("id"),
            rs.getString("declaration_no"),
            rs.getObject("shipment_id", Long.class),
            rs.getString("shipment_no"),
            rs.getString("batch_no"),
            rs.getString("customs_broker"),
            rs.getString("broker_contact"),
            rs.getString("hs_code_summary"),
            rs.getBigDecimal("declared_value"),
            rs.getString("currency"),
            rs.getBigDecimal("duty_amount"),
            rs.getBigDecimal("vat_amount"),
            rs.getBigDecimal("consumption_tax"),
            rs.getBigDecimal("deposit_amount"),
            rs.getString("status"),
            rs.getObject("filed_at", LocalDateTime.class),
            rs.getObject("released_at", LocalDateTime.class),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
