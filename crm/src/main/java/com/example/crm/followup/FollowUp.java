package com.example.crm.followup;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 跟进记录：对某客户的一次触达。
 *
 * <p>type: CALL 电话 / VISIT 拜访 / WECHAT 微信 / MAIL 邮件 / OTHER 其他</p>
 */
public record FollowUp(
        Long id,
        Long customerId,
        String customerName,
        String type,
        String content,
        LocalDateTime nextFollowAt,
        Long creatorId,
        String creatorName,
        LocalDateTime createdAt) {

    public static final RowMapper<FollowUp> ROW_MAPPER = (rs, i) -> new FollowUp(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getString("customer_name"),
            rs.getString("type"),
            rs.getString("content"),
            rs.getObject("next_follow_at", LocalDateTime.class),
            rs.getLong("creator_id"),
            rs.getString("creator_name"),
            rs.getObject("created_at", LocalDateTime.class));
}
