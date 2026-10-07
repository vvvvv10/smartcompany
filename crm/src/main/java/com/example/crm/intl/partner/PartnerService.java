package com.example.crm.intl.partner;

import com.example.crm.NotFoundException;
import com.example.crm.intl.IntlFields;
import com.example.crm.intl.IntlPage;
import com.example.crm.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 渠道商数据访问。 */
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT id, partner_code, carrier_code, partner_name, partner_type, contact_name, contact_phone,
                   contact_email, country, currency, payment_term_days, credit_limit, credit_used,
                   rating, status, remark, created_at, updated_at
            FROM crm_carrier_partners
            """;

    public IntlPage<CarrierPartner> search(String keyword, String partnerType, String status,
                                            int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (partner_code LIKE :kw OR partner_name LIKE :kw OR contact_name LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (partnerType != null && !partnerType.isBlank()) {
            where.append(" AND partner_type = :type ");
            params.addValue("type", partnerType.trim());
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM crm_carrier_partners" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<CarrierPartner> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, CarrierPartner.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public CarrierPartner get(long id) {
        List<CarrierPartner> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", CarrierPartner.ROW_MAPPER,
                id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("渠道商不存在: " + id);
        }
        return rows.get(0);
    }

    public List<CarrierPartner> brief(String partnerType) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get());
        sql.append(" WHERE tenant_id = ?");
        if (partnerType != null && !partnerType.isBlank()) {
            sql.append(" AND partner_type = ?");
            args.add(partnerType.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        return jdbc.query(sql.toString(), CarrierPartner.ROW_MAPPER, args.toArray());
    }

    public CarrierPartner create(PartnerPayload payload) {
        named.update("""
                INSERT INTO crm_carrier_partners
                    (tenant_id, partner_code, carrier_code, partner_name, partner_type, contact_name, contact_phone, contact_email, country, currency, payment_term_days, credit_limit, credit_used, rating, status, remark)
                VALUES (:tenant_id, :partner_code, :carrier_code, :partner_name, :partner_type, :contact_name, :contact_phone, :contact_email, :country, :currency, :payment_term_days, :credit_limit, :credit_used, :rating, :status, :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("partner_code", payload.partnerCode().trim())
                        // 跨库弱引用：只存 tms_carriers.code 的字符串，不校验它在
                        // TMS 里存不存在（跨库校验要网络调用，见 PartnerPayload 注释）。
                        .addValue("carrier_code", IntlFields.text(payload.carrierCode()))
                        .addValue("partner_name", payload.partnerName().trim())
                        .addValue("partner_type", IntlFields.orDefault(payload.partnerType(), "FORWARDER"))
                        .addValue("contact_name", IntlFields.text(payload.contactName()))
                        .addValue("contact_phone", IntlFields.text(payload.contactPhone()))
                        .addValue("contact_email", IntlFields.text(payload.contactEmail()))
                        .addValue("country", IntlFields.orDefault(payload.country(), "CN"))
                        .addValue("currency", IntlFields.orDefault(payload.currency(), "USD"))
                        .addValue("payment_term_days", IntlFields.zero(payload.paymentTermDays()))
                        .addValue("credit_limit", IntlFields.decimal(payload.creditLimit()))
                        .addValue("credit_used", IntlFields.decimal(payload.creditUsed()))
                        .addValue("rating", payload.rating() == null ? 3 : payload.rating())
                        .addValue("status", IntlFields.orDefault(payload.status(), "ACTIVE"))
                        .addValue("remark", IntlFields.text(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public CarrierPartner update(PartnerPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少渠道商 id");
        }
        CarrierPartner existing = get(payload.id());
        int updated = jdbc.update("""
                UPDATE crm_carrier_partners
                   SET partner_code = ?, carrier_code = ?, partner_name = ?, partner_type = ?,
                       contact_name = ?, contact_phone = ?, contact_email = ?, country = ?, currency = ?,
                       payment_term_days = ?, credit_limit = ?, credit_used = ?, rating = ?,
                       status = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.partnerCode().trim(),
                // 显式传空串 = 解除关联，这是合法动作（把渠道商与承运商脱钩），
                // 所以不能用「空则保留旧值」的合并语义——那样就再也解不开了。
                IntlFields.text(payload.carrierCode()),
                payload.partnerName().trim(),
                IntlFields.orDefault(payload.partnerType(), existing.partnerType()),
                IntlFields.text(payload.contactName()),
                IntlFields.text(payload.contactPhone()),
                IntlFields.text(payload.contactEmail()),
                IntlFields.orDefault(payload.country(), existing.country()),
                IntlFields.orDefault(payload.currency(), existing.currency()),
                payload.paymentTermDays() == null ? existing.paymentTermDays() : payload.paymentTermDays(),
                payload.creditLimit() == null ? existing.creditLimit() : payload.creditLimit(),
                payload.creditUsed() == null ? existing.creditUsed() : payload.creditUsed(),
                payload.rating() == null ? existing.rating() : payload.rating(),
                IntlFields.orDefault(payload.status(), existing.status()),
                payload.remark() == null ? existing.remark() : payload.remark(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("渠道商不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        get(id);
        Integer deleted = jdbc.update("DELETE FROM crm_carrier_partners WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("渠道商不存在: " + id);
        }
    }
}
