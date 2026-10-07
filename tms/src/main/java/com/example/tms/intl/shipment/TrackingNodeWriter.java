package com.example.tms.intl.shipment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 轨迹节点（tms_tracking_nodes）的唯一写入组件。
 *
 * <p><b>为什么抽出来</b>：M2 之前插轨迹的 SQL 有两份手写副本（ShipmentService 人工录入、
 * CustomsService 报关联动）。本轮要给回调和定时任务再加两条写入路径，同时给表加三列——
 * 四份副本意味着「回调写出来的行 external_code 恒为空」这种 bug 一定会发生，
 * 而且它不报错，只是安静地丢证据。这里收口成一份，四处调用方共用。</p>
 *
 * <p><b>幂等的两层保证</b>，调用方按是否处于事务中选不同的方法，这一点很关键：
 * <ol>
 *   <li>{@link #exists} —— 「先查后插」。适合已经在事务里的调用方
 *       （{@code changeStatus}、{@code addTracking}、报关联动）：
 *       撞重复会被 {@link #append} 抛成 {@code DuplicateKeyException}，
 *       而在事务里捕获它会把整个事务标记为 rollback-only，Spring 在提交时抛
 *       {@code UnexpectedRollbackException}，反而变成一个更难懂的 500。
 *       所以事务内一律「先查 + 不捕获」。</li>
 *   <li>{@link #append} 直接抛 {@code DuplicateKeyException} —— 适合
 *       **不在**事务里的调用方（承运商回调）：捕获它就等于「这一条是重复投递」，
 *       语义准确，且唯一索引才是并发下真正生效的那道闸（先查后插在并发下必有窗口）。</li>
 * </ol>
 * 也就是说「并发推同一批报文」的正确姿势不是「多加一层先查」，而是
 * **数据库上的唯一索引 + 捕获冲突**，两者缺一不可。</p>
 */
@Component
@RequiredArgsConstructor
public class TrackingNodeWriter {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    /**
     * 幂等键判重：tenant_id + shipment_no + node_code + node_time + external_code。
     * 这五列上有唯一索引 {@code uk_node_event}（56 号迁移），
     * 所以本方法既是「省一次无谓写入」，也是「提前把冲突挡在事务里」。
     */
    public boolean exists(String tenantId, String shipmentNo, String nodeCode,
                          java.time.LocalDateTime nodeTime, String externalCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tms_tracking_nodes
                 WHERE tenant_id = ? AND shipment_no = ? AND node_code = ?
                   AND node_time = ? AND external_code = ?
                """, Integer.class, tenantId, shipmentNo, nodeCode, nodeTime,
                externalCode == null ? "" : externalCode);
        return count != null && count > 0;
    }

    /**
     * 插一条轨迹。冲突时抛 {@code DuplicateKeyException}（唯一索引 uk_node_event）。
     *
     * <p>用命名参数而不是一串 {@code ?}：这张表本轮刚加到 14 个列，
     * 手写占位符对齐极易错位，而错位的表现是「Parameter index out of range」
     * 这种完全看不出指向哪一列的异常。</p>
     */
    public void append(NewTrackingNode raw) {
        NewTrackingNode node = NewTrackingNode.withTruncatedCode(raw);
        named.update("""
                INSERT INTO tms_tracking_nodes
                    (tenant_id, shipment_id, shipment_no, node_code, node_name, node_time, location,
                     source, operator, remark, external_code, external_source, raw_payload)
                VALUES (:tenant_id, :shipment_id, :shipment_no, :node_code, :node_name, :node_time,
                        :location, :source, :operator, :remark, :external_code, :external_source, :raw_payload)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", node.tenantId())
                        .addValue("shipment_id", node.shipmentId())
                        .addValue("shipment_no", node.shipmentNo())
                        .addValue("node_code", node.nodeCode())
                        .addValue("node_name", node.nodeName() == null ? "" : node.nodeName())
                        .addValue("node_time", node.nodeTime())
                        .addValue("location", node.location() == null ? "" : node.location())
                        .addValue("source", node.source())
                        .addValue("operator", node.operator() == null ? "" : node.operator())
                        .addValue("remark", node.remark() == null ? "" : node.remark())
                        .addValue("external_code", node.externalCode() == null ? "" : node.externalCode())
                        .addValue("external_source", node.externalSource() == null ? "" : node.externalSource())
                        .addValue("raw_payload", node.rawPayload()));
    }
}