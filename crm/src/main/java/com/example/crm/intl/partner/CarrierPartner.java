package com.example.crm.intl.partner;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 货代与渠道商（**合作关系**侧主数据）。
 *
 * <p>与 TMS 的 tms_carriers 并存是有意的分工：那边管运输能力
 * （时效、截关、免堆存、体积重除数），这边管我们与对方的商业关系
 * （结算币种、账期、授信、评级）。把两者塞进一张表，改一次账期就会污染运输主数据。</p>
 *
 * <p>但两套主数据并行不等于两张表毫无关联：{@code carrierCode} 指出
 * 「这家渠道商背后是哪家承运商」（对应 tms_carriers.code，跨库弱引用）。
 * 少了它，「我们和 COSCO 的账期是多少」答不出来，M3 也无从知道
 * 对账单该发给哪个 partner_id。空串 = 不是承运商（报关行/海外仓/卡航）。</p>
 */
public record CarrierPartner(
        Long id,
        String partnerCode,
        String carrierCode,
        String partnerName,
        String partnerType,
        String contactName,
        String contactPhone,
        String contactEmail,
        String country,
        String currency,
        Integer paymentTermDays,
        BigDecimal creditLimit,
        BigDecimal creditUsed,
        Integer rating,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<CarrierPartner> ROW_MAPPER = (rs, i) -> new CarrierPartner(
            rs.getLong("id"),
            rs.getString("partner_code"),
            rs.getString("carrier_code"),
            rs.getString("partner_name"),
            rs.getString("partner_type"),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("contact_email"),
            rs.getString("country"),
            rs.getString("currency"),
            rs.getObject("payment_term_days", Integer.class),
            rs.getBigDecimal("credit_limit"),
            rs.getBigDecimal("credit_used"),
            rs.getObject("rating", Integer.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
