package com.example.crm.opportunity;

import com.example.crm.NotFoundException;
import com.example.crm.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 商机数据访问。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class OpportunityService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT o.id, o.customer_id, c.name AS customer_name, o.name, o.amount, o.stage,
                   o.probability, o.expected_close_date, o.remark, o.owner_id, o.owner_name,
                   o.created_at, o.updated_at
              FROM opportunities o
              LEFT JOIN customers c ON c.id = o.customer_id
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Opportunity> search(String keyword, String stage, Long customerId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.name LIKE :kw OR c.name LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (stage != null && !stage.isBlank()) {
            where.append(" AND o.stage = :stage ");
            params.addValue("stage", stage);
        }
        if (customerId != null) {
            where.append(" AND o.customer_id = :customerId ");
            params.addValue("customerId", customerId);
        }
        where.append(" AND o.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM opportunities o LEFT JOIN customers c ON c.id = o.customer_id" + where,
                params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Opportunity> list = named.query(
                BASE_SELECT + where + " ORDER BY o.id DESC LIMIT :limit OFFSET :offset",
                params, Opportunity.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Opportunity get(long id) {
        List<Opportunity> rows = jdbc.query(
                BASE_SELECT + " WHERE o.id = ? AND o.tenant_id = ?", Opportunity.ROW_MAPPER,
                id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("商机不存在: " + id);
        }
        return rows.get(0);
    }

    public Opportunity create(OpportunityPayload payload, long ownerId, String ownerName) {
        jdbc.update("""
                INSERT INTO opportunities (tenant_id, customer_id, name, amount, stage, probability,
                                           expected_close_date, remark, owner_id, owner_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                payload.customerId(),
                payload.name().trim(),
                payload.amount() == null ? java.math.BigDecimal.ZERO : payload.amount(),
                stageOrDefault(payload.stage()),
                probabilityOrDefault(payload.probability(), payload.stage()),
                parseDate(payload.expectedCloseDate()),
                payload.remark() == null ? "" : payload.remark().trim(),
                ownerId,
                ownerName);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Opportunity update(OpportunityPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少商机 id");
        }
        int updated = jdbc.update("""
                UPDATE opportunities
                   SET customer_id = ?, name = ?, amount = ?, stage = ?, probability = ?,
                       expected_close_date = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.customerId(),
                payload.name().trim(),
                payload.amount() == null ? java.math.BigDecimal.ZERO : payload.amount(),
                stageOrDefault(payload.stage()),
                probabilityOrDefault(payload.probability(), payload.stage()),
                parseDate(payload.expectedCloseDate()),
                payload.remark() == null ? "" : payload.remark().trim(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("商机不存在: " + payload.id());
        }
        return get(payload.id());
    }

    /** 阶段流转（推进/回退），顺带按阶段回填默认赢率。 */
    public Opportunity moveStage(long id, String stage) {
        String normalized = stageOrDefault(stage);
        int updated = jdbc.update(
                "UPDATE opportunities SET stage = ?, probability = ? WHERE id = ? AND tenant_id = ?",
                normalized, probabilityOrDefault(null, normalized), id, TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("商机不存在: " + id);
        }
        return get(id);
    }

    public void delete(long id) {
        if (jdbc.update("DELETE FROM opportunities WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get()) == 0) {
            throw new NotFoundException("商机不存在: " + id);
        }
    }

    private static String stageOrDefault(String stage) {
        if (stage == null || stage.isBlank()) {
            return "LEAD";
        }
        return stage.trim().toUpperCase();
    }

    /** 每个阶段的默认赢率，调用方没给 probability 时按阶段带出。 */
    private static Integer probabilityOrDefault(Integer probability, String stage) {
        if (probability != null) {
            return Math.max(0, Math.min(100, probability));
        }
        return switch (stageOrDefault(stage)) {
            case "WON" -> 100;
            case "LOST" -> 0;
            case "NEGOTIATION" -> 70;
            case "PROPOSAL" -> 50;
            default -> 20;
        };
    }

    private static java.sql.Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.sql.Date.valueOf(LocalDate.parse(value.trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("预计成交日期格式应为 yyyy-MM-dd: " + value);
        }
    }
}
