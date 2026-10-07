package com.example.tms.intl.carrier;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 承运商 / 渠道商（运输侧主数据）。
 *
 * <p>carrier_type: OCEAN 海运船司 / AIR 空运航司 / EXPRESS 快递 /
 * NVOCC 无船承运人 / FORWARDER 货代 / AGENT 货代代理 /
 * CLEARANCE 清关服务商 / LAST_MILE 尾程派送</p>
 *
 * <p>这张表只管「运输能力」——时效、截关提前小时、免堆存天数。
 * 「我们跟这家船司的账期与授信」在 CRM 的 crm_carrier_partners，
 * 两套主数据并行是有意的：改账期不该污染运输主数据。</p>
 */
public record Carrier(
        Long id,
        String code,
        String nameZh,
        String nameEn,
        String carrierType,
        String country,
        String contactName,
        String contactPhone,
        String contactEmail,
        String serviceLevel,
        Integer transitDays,
        Integer cutOffHours,
        Integer freeTimeDays,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<Carrier> ROW_MAPPER = (rs, i) -> new Carrier(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name_zh"),
            rs.getString("name_en"),
            rs.getString("carrier_type"),
            rs.getString("country"),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("contact_email"),
            rs.getString("service_level"),
            rs.getObject("transit_days", Integer.class),
            rs.getObject("cut_off_hours", Integer.class),
            rs.getObject("free_time_days", Integer.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
