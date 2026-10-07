package com.example.tms.intl.shipment;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 运单轨迹节点（**只追加，不更新**）。
 *
 * <p>source 是这套设计的核心：它回答「这一条是人点的、承运商推的还是系统推的」。
 * 将来排查「客户说船没走、我们系统显示已开航」时，第一眼就看这个标签。</p>
 *
 * <p>三个来源的区别不只是「谁点的」，而是**谁承担这个判断的后果**：
 * <ul>
 *   <li>{@code MANUAL} —— 人点的。人已经看过现场，知道货走了才点。</li>
 *   <li>{@code CARRIER_CALLBACK} —— 承运商服务器推的。我们只做了映射，
 *       没做判断；出问题时该找承运商对报文（{@code raw_payload} 就是证据）。</li>
 *   <li>{@code SCHEDULED} —— 系统按 ETD/ETA 到点推的。**谁都没看过**，
 *       只是时间到了就认为事实成立；所以它必须最容易被质疑——
 *       这就是它必须单独留一条 WARN 日志的原因（见 IntlTrackingScheduler）。</li>
 * </ul>
 * M1 的种子数据里已经混了一些 CARRIER_CALLBACK 行（49 号迁移为了让页面像真的，
 * 手工灌的演示数据），所以「数据里有 CARRIER_CALLBACK」不能证明回调链路通了——
 * 要证明得看 {@code external_code} 非空的行。</p>
 *
 * <p>后三列（external_code / external_source / raw_payload）M2 加，理由见
 * 56 号迁移的注释，核心是「对账时承运商会拿他们的报文质疑我们的节点时间」。</p>
 */
public record TrackingNode(
        Long id,
        Long shipmentId,
        String shipmentNo,
        String nodeCode,
        String nodeName,
        LocalDateTime nodeTime,
        String location,
        String source,
        String operator,
        String remark,
        String externalCode,
        String externalSource,
        LocalDateTime createdAt) {

    public static final RowMapper<TrackingNode> ROW_MAPPER = (rs, i) -> new TrackingNode(
            rs.getLong("id"),
            rs.getObject("shipment_id", Long.class),
            rs.getString("shipment_no"),
            rs.getString("node_code"),
            rs.getString("node_name"),
            rs.getObject("node_time", LocalDateTime.class),
            rs.getString("location"),
            rs.getString("source"),
            rs.getString("operator"),
            rs.getString("remark"),
            rs.getString("external_code"),
            rs.getString("external_source"),
            rs.getObject("created_at", LocalDateTime.class));
}