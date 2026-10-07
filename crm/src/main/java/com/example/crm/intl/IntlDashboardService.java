package com.example.crm.intl;

import com.example.crm.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 国际 CRM 看板（客户 / 渠道商 / 工单）。
 *
 * <p><b>禁止跨币种聚合</b>：授信已用与授信总额按 default_currency 分组返回，
 * 不做 SUM。多币种授信直接相加得到的数字没有意义（计划 §11 R3）。</p>
 *
 * <p>与现有 {@code /api/crm/dashboard} 并存：那个是国内零售 CRM 的经营概览
 * （商机金额、跟进活跃度），与出口业务的口径不可比。</p>
 */
@Service
@RequiredArgsConstructor
public class IntlDashboardService {

    private final JdbcTemplate jdbc;

    public record TicketCount(String status, long count) {
    }

    public record CategoryCount(String category, long count) {
    }

    public record CurrencyAmount(String currency, BigDecimal amount) {
    }

    public record PartnerCount(String partnerType, long count) {
    }

    public record Dashboard(
            long customerTotal,
            long overseaCustomer,
            long partnerTotal,
            long ticketOpen,
            long ticketOverdue,
            List<TicketCount> ticketDistribution,
            List<CategoryCount> ticketByCategory,
            List<CurrencyAmount> creditUsedByCurrency,
            List<CurrencyAmount> creditLimitByCurrency,
            List<PartnerCount> partnerByType) {
    }

    public Dashboard dashboard() {
        String tenant = TenantContext.get();

        Integer customers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE tenant_id = ?", Integer.class, tenant);
        Integer oversea = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE tenant_id = ? AND country <> 'CN'",
                Integer.class, tenant);
        Integer partners = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_carrier_partners WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenant);

        List<TicketCount> ticketDistribution = jdbc.query(
                "SELECT status, COUNT(*) AS c FROM crm_tickets WHERE tenant_id = ? GROUP BY status",
                (rs, i) -> new TicketCount(rs.getString("status"), rs.getLong("c")), tenant);
        List<CategoryCount> byCategory = jdbc.query(
                "SELECT category, COUNT(*) AS c FROM crm_tickets WHERE tenant_id = ? GROUP BY category "
                        + "ORDER BY c DESC LIMIT 6",
                (rs, i) -> new CategoryCount(rs.getString("category"), rs.getLong("c")), tenant);
        Long open = count(ticketDistribution, "OPEN") + count(ticketDistribution, "FOLLOWING");
        Long overdue = jdbc.queryForObject("""
                SELECT COUNT(*) FROM crm_tickets
                 WHERE tenant_id = ? AND due_at IS NOT NULL AND due_at < UTC_DATE()
                   AND status NOT IN ('RESOLVED','CLOSED')
                """, Long.class, tenant);

        List<CurrencyAmount> creditUsed = jdbc.query(
                "SELECT default_currency, COALESCE(SUM(credit_used), 0) FROM customers "
                        + "WHERE tenant_id = ? GROUP BY default_currency ORDER BY 2 DESC",
                (rs, i) -> new CurrencyAmount(rs.getString(1), rs.getBigDecimal(2)), tenant);
        List<CurrencyAmount> creditLimit = jdbc.query(
                "SELECT default_currency, COALESCE(SUM(credit_limit), 0) FROM customers "
                        + "WHERE tenant_id = ? GROUP BY default_currency ORDER BY 2 DESC",
                (rs, i) -> new CurrencyAmount(rs.getString(1), rs.getBigDecimal(2)), tenant);
        List<PartnerCount> partnerByType = jdbc.query(
                "SELECT partner_type, COUNT(*) FROM crm_carrier_partners "
                        + "WHERE tenant_id = ? AND status = 'ACTIVE' GROUP BY partner_type",
                (rs, i) -> new PartnerCount(rs.getString(1), rs.getLong(2)), tenant);

        return new Dashboard(
                customers == null ? 0 : customers,
                oversea == null ? 0 : oversea,
                partners == null ? 0 : partners,
                open == null ? 0 : open,
                overdue == null ? 0 : overdue,
                ticketDistribution, byCategory, creditUsed, creditLimit, partnerByType);
    }

    private static long count(List<TicketCount> distribution, String status) {
        return distribution.stream().filter(d -> status.equals(d.status()))
                .mapToLong(TicketCount::count).sum();
    }
}
