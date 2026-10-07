package com.example.tms.intl.sailing;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 船期 / 班期（一条航线上的一班船或一班机）。
 *
 * <p>etdAt/etaAt/cutoffAt 一律 **UTC**（列名 _at + COMMENT 标注），与库表口径一致；
 * 前端展示层按 Asia/Shanghai 换算（intl/shared.tsx 的 formatUtc）。</p>
 */
public record Sailing(
        Long id,
        Long routeId,
        String routeCode,
        String polCode,
        String podCode,
        String vesselName,
        String voyageNo,
        LocalDateTime etdAt,
        LocalDateTime etaAt,
        LocalDateTime cutoffAt,
        Integer freeTimeDays,
        Integer spaceLeft,
        String status,
        String remark,
        LocalDateTime createdAt) {

    public static final RowMapper<Sailing> ROW_MAPPER = (rs, i) -> new Sailing(
            rs.getLong("id"),
            rs.getObject("route_id", Long.class),
            rs.getString("route_code"),
            rs.getString("pol_code"),
            rs.getString("pod_code"),
            rs.getString("vessel_name"),
            rs.getString("voyage_no"),
            rs.getObject("etd_at", LocalDateTime.class),
            rs.getObject("eta_at", LocalDateTime.class),
            rs.getObject("cutoff_at", LocalDateTime.class),
            rs.getObject("free_time_days", Integer.class),
            rs.getObject("space_left", Integer.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class));
}
