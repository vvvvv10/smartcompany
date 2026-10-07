package com.example.tms.intl.customs;

import com.example.tms.intl.IntlNumberGenerator;
import com.example.tms.NotFoundException;
import com.example.tms.intl.IntlPage;
import com.example.tms.intl.IntlStateMachine;
import com.example.tms.intl.IntlFields;
import com.example.tms.intl.shipment.NewTrackingNode;
import com.example.tms.intl.shipment.TrackingNodeWriter;
import com.example.tms.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出口报关单数据访问与状态机。
 *
 * <p><b>放行时的联动</b>：报关单进 RELEASED 时把运单从 BOOKED/PICKED_UP 推到
 * EXPORT_DECLARED，并留一条轨迹。**只推运单，不回调 OMS**——
 * M1 的范围纪律（计划 §8.2）明确「TMS→OMS 的反向回调放 M2 且只做非阻塞」。
 * OMS 侧的子单状态在 M1 由人工在出口订单页点，与本表的联动分开。</p>
 *
 * <p>为什么要把这两个动作放同一个事务：报关放行是「运输事实」，
 * 运单状态是它的投影。两者不一致时，运单表是唯一真相（计划 §11 R1），
 * 所以放行与推进必须同生共死。</p>
 */
@Service
@RequiredArgsConstructor
public class CustomsService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    /** M2-4：轨迹 INSERT 与运单侧共用一份，不在本类里手写。 */
    private final TrackingNodeWriter tracker;

    private static final String BASE_SELECT = """
            SELECT id, declaration_no, shipment_id, shipment_no, batch_no, customs_broker,
                   broker_contact, hs_code_summary, declared_value, currency, duty_amount,
                   vat_amount, consumption_tax, deposit_amount, status, filed_at, released_at,
                   remark, created_at, updated_at
            FROM tms_customs_declarations
            """;

    public IntlPage<CustomsDeclaration> search(String keyword, String status, String shipmentNos,
                                               int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (declaration_no LIKE :kw OR customs_broker LIKE :kw OR hs_code_summary LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }
        if (shipmentNos != null && !shipmentNos.isBlank()) {
            List<String> nos = new ArrayList<>();
            for (String part : shipmentNos.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    nos.add(trimmed);
                }
            }
            if (!nos.isEmpty()) {
                where.append(" AND shipment_no IN (:shipmentNos) ");
                params.addValue("shipmentNos", nos.size() > 200 ? nos.subList(0, 200) : nos);
            }
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM tms_customs_declarations" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<CustomsDeclaration> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, CustomsDeclaration.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public CustomsDeclaration get(long id) {
        List<CustomsDeclaration> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", CustomsDeclaration.ROW_MAPPER,
                id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("报关单不存在: " + id);
        }
        return rows.get(0);
    }

    public Optional<CustomsDeclaration> findByShipmentId(long shipmentId) {
        List<CustomsDeclaration> rows = jdbc.query(
                BASE_SELECT + " WHERE shipment_id = ? AND tenant_id = ? ORDER BY id DESC LIMIT 1",
                CustomsDeclaration.ROW_MAPPER, shipmentId, TenantContext.get());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Transactional
    public CustomsDeclaration create(CustomsDeclarationPayload payload) {
        String shipmentNo = shipmentNoOf(payload.shipmentId());
        String batchNo = jdbc.queryForObject(
                "SELECT batch_no FROM tms_shipments WHERE id = ? AND tenant_id = ?",
                String.class, payload.shipmentId(), TenantContext.get());
        Integer sameShipment = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tms_customs_declarations WHERE shipment_id = ? AND tenant_id = ?",
                Integer.class, payload.shipmentId(), TenantContext.get());
        if (sameShipment != null && sameShipment > 0) {
            throw new IllegalArgumentException("该运单已有报关单（一票货一票报关），请在既有报关单上推进状态");
        }
        named.update("""
                INSERT INTO tms_customs_declarations
                    (tenant_id, declaration_no, shipment_id, shipment_no, batch_no, customs_broker, broker_contact, hs_code_summary, declared_value, currency, duty_amount, vat_amount, consumption_tax, deposit_amount, status, remark)
                VALUES (:tenant_id, :declaration_no, :shipment_id, :shipment_no, :batch_no, :customs_broker, :broker_contact, :hs_code_summary, :declared_value, :currency, :duty_amount, :vat_amount, :consumption_tax, :deposit_amount, :status, :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("declaration_no", IntlFields.orDefault(payload.declarationNo(), generateDeclarationNo()))
                        .addValue("shipment_id", payload.shipmentId())
                        .addValue("shipment_no", shipmentNo)
                        .addValue("batch_no", IntlFields.text(batchNo))
                        .addValue("customs_broker", IntlFields.text(payload.customsBroker()))
                        .addValue("broker_contact", IntlFields.text(payload.brokerContact()))
                        .addValue("hs_code_summary", IntlFields.text(payload.hsCodeSummary()))
                        // 金额列都是 DECIMAL NOT NULL DEFAULT 0：不传就归零，不能写 null
                        .addValue("declared_value", IntlFields.decimal(payload.declaredValue()))
                        .addValue("currency", IntlFields.orDefault(payload.currency(), "USD"))
                        .addValue("duty_amount", IntlFields.decimal(payload.dutyAmount()))
                        .addValue("vat_amount", IntlFields.decimal(payload.vatAmount()))
                        .addValue("consumption_tax", IntlFields.decimal(payload.consumptionTax()))
                        .addValue("deposit_amount", IntlFields.decimal(payload.depositAmount()))
                        .addValue("status", IntlFields.orDefault(payload.status(), "DRAFT"))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public CustomsDeclaration update(CustomsDeclarationPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少报关单 id");
        }
        CustomsDeclaration existing = get(payload.id());
        int updated = jdbc.update("""
                UPDATE tms_customs_declarations
                   SET customs_broker = ?, broker_contact = ?, hs_code_summary = ?,
                       declared_value = ?, currency = ?, duty_amount = ?, vat_amount = ?,
                       consumption_tax = ?, deposit_amount = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                IntlFields.text(payload.customsBroker()),
                IntlFields.text(payload.brokerContact()),
                IntlFields.text(payload.hsCodeSummary()),
                payload.declaredValue() == null ? existing.declaredValue() : payload.declaredValue(),
                IntlFields.orDefault(payload.currency(), existing.currency()),
                payload.dutyAmount() == null ? existing.dutyAmount() : payload.dutyAmount(),
                payload.vatAmount() == null ? existing.vatAmount() : payload.vatAmount(),
                payload.consumptionTax() == null ? existing.consumptionTax() : payload.consumptionTax(),
                payload.depositAmount() == null ? existing.depositAmount() : payload.depositAmount(),
                payload.remark() == null ? existing.remark() : payload.remark(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("报关单不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        CustomsDeclaration existing = get(id);
        if ("RELEASED".equals(existing.status())) {
            throw new IllegalArgumentException("已放行的报关单是海关凭证，不能删除");
        }
        int deleted = jdbc.update("DELETE FROM tms_customs_declarations WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("报关单不存在: " + id);
        }
    }

    /**
     * 推进报关单状态；进 RELEASED 时联动运单 BOOKED|PICKED_UP → EXPORT_DECLARED。
     *
     * <p>运单只在「还没更靠后」时才推：如果运单已经是 DEPARTED（理论上不该发生，
     * 因为 RELEASED 必在 DEPARTED 之前），就只留轨迹不改状态——绝不把单子往回拽。</p>
     */
    @Transactional
    public CustomsDeclaration changeStatus(long id, CustomsStatusPayload payload) {
        CustomsDeclaration existing = get(id);
        IntlStateMachine.assertTransition(IntlStateMachine.DECLARATION, existing.status(), payload.status(), "报关单");

        jdbc.update("""
                UPDATE tms_customs_declarations
                   SET status = ?,
                       filed_at = CASE WHEN ? = 'FILED' THEN COALESCE(filed_at, ?) ELSE filed_at END,
                       released_at = CASE WHEN ? = 'RELEASED' THEN ? ELSE released_at END
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.status(),
                payload.status(), nowUtc(),
                payload.status(), nowUtc(),
                id, TenantContext.get());

        if ("RELEASED".equals(payload.status())) {
            advanceShipmentToDeclared(existing);
        }
        return get(id);
    }

    private void advanceShipmentToDeclared(CustomsDeclaration declaration) {
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM tms_shipments WHERE id = ? AND tenant_id = ?",
                String.class, declaration.shipmentId(), TenantContext.get());
        if (shipmentStatus == null) {
            return;
        }
        boolean canAdvance = "BOOKED".equals(shipmentStatus) || "PICKED_UP".equals(shipmentStatus);
        if (!canAdvance) {
            // 已经更靠后或已异常：只记一条轨迹说明「报关已放行」，不改状态
            insertNode(declaration, "EXPORT_DECLARED", "已报关", "报关单 " + declaration.declarationNo() + " 已放行");
            return;
        }
        jdbc.update("UPDATE tms_shipments SET status = 'EXPORT_DECLARED' WHERE id = ? AND tenant_id = ?",
                declaration.shipmentId(), TenantContext.get());
        insertNode(declaration, "EXPORT_DECLARED", "已报关",
                "报关单 " + declaration.declarationNo() + " 放行，运单自动推进到已报关");
    }

    private void insertNode(CustomsDeclaration declaration, String nodeCode, String nodeName, String remark) {
        // M2-4：INSERT 收口到 TrackingNodeWriter。本轮给 tms_tracking_nodes 加了三列
        // （external_code / external_source / raw_payload），留着这份手写副本就等于
        // 留一个「报关联动的轨迹永远没有承运商原文」的暗坑——将来加第四列要改三处 SQL。
        NewTrackingNode node = new NewTrackingNode(
                TenantContext.get(), declaration.shipmentId(), declaration.shipmentNo(),
                nodeCode, nodeName, nowUtc(), "",
                NewTrackingNode.SOURCE_MANUAL, "系统", remark, "", "", null);
        if (!tracker.exists(node.tenantId(), node.shipmentNo(), node.nodeCode(),
                node.nodeTime(), node.externalCode())) {
            tracker.append(node);
        }
    }

    /** 关单号位数与编码规则【待验证】；这里只保证租户内唯一（原子发号，见 IntlNumberGenerator）。 */
    private String generateDeclarationNo() {
        return IntlNumberGenerator.nextNo(named, TenantContext.get(), "DECL", "DECL", 3);
    }

    private String shipmentNoOf(long shipmentId) {
        String no = jdbc.queryForObject(
                "SELECT shipment_no FROM tms_shipments WHERE id = ? AND tenant_id = ?",
                String.class, shipmentId, TenantContext.get());
        if (no == null) {
            throw new NotFoundException("运单不存在: " + shipmentId);
        }
        return no;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
