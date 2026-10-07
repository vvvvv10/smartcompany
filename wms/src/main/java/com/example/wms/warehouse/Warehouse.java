package com.example.wms.warehouse;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 仓库。
 *
 * <p>status: ACTIVE 启用 / INACTIVE 停用</p>
 *
 * <p>末尾 5 个字段是 45 号迁移加的国际仓属性：仓库类型（境内/海外/保税/中转）、
 * 所在国、IANA 时区、海外仓服务商、是否保税。timezone 之所以要存：
 * 海外仓的「今天从几点开始」是当地零点，备货排程与临期判断都得按它算。</p>
 */
public record Warehouse(
        Long id,
        String name,
        String location,
        Integer capacity,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String warehouseType,
        String country,
        String timezone,
        String overseaOperator,
        Integer isBonded) {

    public static final RowMapper<Warehouse> ROW_MAPPER = (rs, i) -> new Warehouse(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("location"),
            rs.getObject("capacity", Integer.class),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("warehouse_type"),
            rs.getString("country"),
            rs.getString("timezone"),
            rs.getString("oversea_operator"),
            rs.getObject("is_bonded", Integer.class));
}
