package com.example.oms.product;

import com.example.oms.NotFoundException;
import com.example.oms.intl.IntlFields;
import com.example.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 商品数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * keyword/category/status 全部可选筛选（keyword 匹配款号/品名/颜色/SKU）。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT id, style_no, name, category, season, color, size, sku,
                   price, status, remark, created_at, updated_at,
                   hs_code, customs_name, customs_name_en, origin_country, is_dangerous,
                   un_number, dg_class, packing_instruction, battery_watt_hours,
                   net_weight, gross_weight, volume_cbm
            FROM oms_products
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Product> search(String keyword, String category, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (style_no LIKE :kw OR name LIKE :kw OR color LIKE :kw OR sku LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = :category ");
            params.addValue("category", category.trim());
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status ");
            params.addValue("status", status.trim());
        }

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM oms_products" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Product> list = named.query(
                BASE_SELECT + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, Product.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Product get(long id) {
        List<Product> rows = jdbc.query(
                BASE_SELECT + " WHERE id = ? AND tenant_id = ?", Product.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("商品不存在: " + id);
        }
        return rows.get(0);
    }

    public Product create(ProductPayload payload) {
        named.update("""
                INSERT INTO oms_products
                    (tenant_id, style_no, name, category, season, color, size, sku, price, status, remark, hs_code, customs_name, customs_name_en, origin_country, is_dangerous, un_number, dg_class, packing_instruction, battery_watt_hours, net_weight, gross_weight, volume_cbm)
                VALUES (:tenant_id, :style_no, :name, :category, :season, :color, :size, :sku, :price, :status, :remark, :hs_code, :customs_name, :customs_name_en, :origin_country, :is_dangerous, :un_number, :dg_class, :packing_instruction, :battery_watt_hours, :net_weight, :gross_weight, :volume_cbm)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("style_no", payload.styleNo().trim())
                        .addValue("name", payload.name().trim())
                        .addValue("category", defaultIfBlank(payload.category(), "TOP"))
                        .addValue("season", nullSafe(payload.season()))
                        .addValue("color", nullSafe(payload.color()))
                        .addValue("size", nullSafe(payload.size()))
                        .addValue("sku", resolveSku(payload))
                        .addValue("price", IntlFields.decimal(payload.price()))
                        .addValue("status", defaultIfBlank(payload.status(), "ON"))
                        .addValue("remark", nullSafe(payload.remark()))
                        .addValue("hs_code", nullSafe(payload.hsCode()))
                        .addValue("customs_name", nullSafe(payload.customsName()))
                        .addValue("customs_name_en", nullSafe(payload.customsNameEn()))
                        .addValue("origin_country", defaultIfBlank(payload.originCountry(), "CN"))
                        .addValue("is_dangerous", payload.isDangerous() == null ? 0 : payload.isDangerous())
                        .addValue("un_number", nullSafe(payload.unNumber()))
                        .addValue("dg_class", nullSafe(payload.dgClass()))
                        .addValue("packing_instruction", nullSafe(payload.packingInstruction()))
                        .addValue("battery_watt_hours", decimalOrZero(payload.batteryWattHours()))
                        .addValue("net_weight", decimalOrZero(payload.netWeight()))
                        .addValue("gross_weight", decimalOrZero(payload.grossWeight()))
                        .addValue("volume_cbm", decimalOrZero(payload.volumeCbm()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Product update(ProductPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少商品 id");
        }
        int updated = jdbc.update("""
                UPDATE oms_products
                   SET style_no = ?, name = ?, category = ?, season = ?, color = ?, size = ?,
                       sku = ?, price = ?, status = ?, remark = ?,
                       hs_code = ?, customs_name = ?, customs_name_en = ?, origin_country = ?,
                       is_dangerous = ?, un_number = ?, dg_class = ?, packing_instruction = ?,
                       battery_watt_hours = ?, net_weight = ?, gross_weight = ?, volume_cbm = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.styleNo().trim(),
                payload.name().trim(),
                defaultIfBlank(payload.category(), "TOP"),
                nullSafe(payload.season()),
                nullSafe(payload.color()),
                nullSafe(payload.size()),
                resolveSku(payload),
                priceOrZero(payload.price()),
                defaultIfBlank(payload.status(), "ON"),
                nullSafe(payload.remark()),
                nullSafe(payload.hsCode()),
                nullSafe(payload.customsName()),
                nullSafe(payload.customsNameEn()),
                defaultIfBlank(payload.originCountry(), "CN"),
                payload.isDangerous() == null ? 0 : payload.isDangerous(),
                nullSafe(payload.unNumber()),
                nullSafe(payload.dgClass()),
                nullSafe(payload.packingInstruction()),
                decimalOrZero(payload.batteryWattHours()),
                decimalOrZero(payload.netWeight()),
                decimalOrZero(payload.grossWeight()),
                decimalOrZero(payload.volumeCbm()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("商品不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM oms_products WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("商品不存在: " + id);
        }
    }

    /** 商品下拉选择用（id / 款号 / 品名 / 颜色 / 尺码 / 价格）。 */
    public List<ProductBrief> brief() {
        return jdbc.query(
                """
                SELECT id, style_no, name, color, size, price
                  FROM oms_products
                 WHERE tenant_id = ?
                 ORDER BY id DESC
                 LIMIT 500
                """,
                ProductBrief.ROW_MAPPER, TenantContext.get());
    }

    /** sku 生成规则：款号-颜色-尺码（入参给了 sku 就用入参的，颜色/尺码可为空串直接拼接）。 */
    private static String resolveSku(ProductPayload payload) {
        if (payload.sku() != null && !payload.sku().isBlank()) {
            return payload.sku().trim();
        }
        return payload.styleNo().trim() + "-" + nullSafe(payload.color()) + "-" + nullSafe(payload.size());
    }

    private static BigDecimal priceOrZero(BigDecimal price) {
        return price == null ? BigDecimal.ZERO : price;
    }

    /** 国际申报属性的数值列：DECIMAL NOT NULL DEFAULT 0，所以 null 要归零而不是写 null。 */
    private static BigDecimal decimalOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
