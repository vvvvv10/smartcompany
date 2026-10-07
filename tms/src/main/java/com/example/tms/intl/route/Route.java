package com.example.tms.intl.route;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 航线（一条 POL→POD 的运输通道 + 其时效与体积重口径）。
 *
 * <p>pol_code/pod_code 用 8 位宽列同时兼容海运 UN/LOCODE 5 位与空运 IATA 3 位——
 * 这两种码在真实业务里是混用的（海运报关用 LOCODE、空运舱单用 IATA），
 * 分成两列反而逼着运营在两个字段之间做人工映射。【待验证】以货代口径复核。</p>
 */
public record Route(
        Long id,
        String routeCode,
        Long carrierId,
        String carrierName,
        String mode,
        String polCode,
        String podCode,
        Integer transitDays,
        Integer volumetricDivisor,
        String viaPorts,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final RowMapper<Route> ROW_MAPPER = (rs, i) -> new Route(
            rs.getLong("id"),
            rs.getString("route_code"),
            rs.getObject("carrier_id", Long.class),
            rs.getString("carrier_name"),
            rs.getString("mode"),
            rs.getString("pol_code"),
            rs.getString("pod_code"),
            rs.getObject("transit_days", Integer.class),
            rs.getObject("volumetric_divisor", Integer.class),
            rs.getString("via_ports"),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));
}
