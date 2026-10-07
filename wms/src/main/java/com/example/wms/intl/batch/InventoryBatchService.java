package com.example.wms.intl.batch;

import com.example.wms.NotFoundException;
import com.example.wms.intl.IntlFields;
import com.example.wms.intl.IntlPage;
import com.example.wms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 库存批次数据访问。
 *
 * <p>available_qty 是 SELECT 里现算的 {@code (quantity - reserved_qty)}，
 * 不是表列——见 {@link InventoryBatch} 的类注释。</p>
 *
 * <p>expiringWithinDays 筛「N 天内到期」用 {@code UTC_DATE()}：
 * 效期列是 DATE（当地日期），但比较基准用 UTC 今天——库表存 UTC 的口径决定了这一个。
 * 差 8 小时只可能把「刚好卡在边界」的一行算进或不算进，不影响整体正确性。</p>
 */
@Service
@RequiredArgsConstructor
public class InventoryBatchService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT b.id, b.warehouse_id, w.name AS warehouse_name, b.sku, b.product_name,
                   b.batch_no, b.production_date, b.expiry_date, b.quantity, b.reserved_qty,
                   (b.quantity - b.reserved_qty) AS available_qty, b.unit_cost, b.location_code,
                   b.status, b.created_at, b.updated_at
            FROM wms_inventory_batches b
            LEFT JOIN wms_warehouses w ON w.id = b.warehouse_id
            """;

    public IntlPage<InventoryBatch> search(Long warehouseId, String sku, String keyword, String status,
                                           Integer expiringWithinDays, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND b.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (warehouseId != null) {
            where.append(" AND b.warehouse_id = :warehouseId ");
            params.addValue("warehouseId", warehouseId);
        }
        if (sku != null && !sku.isBlank()) {
            where.append(" AND b.sku = :sku ");
            params.addValue("sku", sku.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (b.sku LIKE :kw OR b.product_name LIKE :kw OR b.batch_no LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND b.status = :status ");
            params.addValue("status", status.trim());
        }
        if (expiringWithinDays != null && expiringWithinDays > 0) {
            where.append(" AND b.expiry_date IS NOT NULL AND b.expiry_date <= UTC_DATE() + INTERVAL :days DAY");
            params.addValue("days", expiringWithinDays);
        }

        Long total = named.queryForObject("SELECT COUNT(*) FROM wms_inventory_batches b" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<InventoryBatch> list = named.query(
                BASE_SELECT + where + " ORDER BY b.expiry_date IS NULL, b.expiry_date, b.id DESC "
                        + "LIMIT :limit OFFSET :offset",
                params, InventoryBatch.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public InventoryBatch get(long id) {
        List<InventoryBatch> rows = jdbc.query(
                BASE_SELECT + " WHERE b.id = ? AND b.tenant_id = ?", InventoryBatch.ROW_MAPPER,
                id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("库存批次不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * FEFO 推荐：某仓某 SKU 的库存批次按**效期升序**（idx_fefo 服务这个查询）。
     * 只返回还有在库量的批次——效期最近但已经拣空的那批没有推荐价值。
     */
    public List<InventoryBatch> fefo(long warehouseId, String sku) {
        return jdbc.query(
                BASE_SELECT + " WHERE b.tenant_id = ? AND b.warehouse_id = ? AND b.sku = ? "
                        + "AND b.quantity > 0 ORDER BY b.expiry_date IS NULL, b.expiry_date LIMIT 50",
                InventoryBatch.ROW_MAPPER, TenantContext.get(), warehouseId, sku);
    }

    public InventoryBatch create(InventoryBatchPayload payload) {
        int reserved = IntlFields.zero(payload.reservedQty());
        int quantity = IntlFields.zero(payload.quantity());
        if (reserved > quantity) {
            throw new IllegalArgumentException("占用数量 " + reserved + " 不能大于在库数量 " + quantity);
        }
        named.update("""
                INSERT INTO wms_inventory_batches
                    (tenant_id, warehouse_id, sku, product_name, batch_no, production_date, expiry_date, quantity, reserved_qty, unit_cost, location_code, status)
                VALUES (:tenant_id, :warehouse_id, :sku, :product_name, :batch_no, :production_date, :expiry_date, :quantity, :reserved_qty, :unit_cost, :location_code, :status)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("warehouse_id", payload.warehouseId())
                        .addValue("sku", payload.sku().trim())
                        .addValue("product_name", IntlFields.text(payload.productName()))
                        .addValue("batch_no", payload.batchNo().trim())
                        .addValue("production_date", payload.productionDate())
                        .addValue("expiry_date", payload.expiryDate())
                        .addValue("quantity", quantity)
                        .addValue("reserved_qty", reserved)
                        .addValue("unit_cost", IntlFields.decimal(payload.unitCost()))
                        .addValue("location_code", IntlFields.text(payload.locationCode()))
                        .addValue("status", IntlFields.orDefault(payload.status(), "NORMAL"))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public InventoryBatch update(InventoryBatchPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少库存批次 id");
        }
        InventoryBatch existing = get(payload.id());
        int reserved = payload.reservedQty() == null ? IntlFields.zero(existing.reservedQty()) : payload.reservedQty();
        int quantity = payload.quantity() == null ? IntlFields.zero(existing.quantity()) : payload.quantity();
        if (reserved > quantity) {
            throw new IllegalArgumentException("占用数量 " + reserved + " 不能大于在库数量 " + quantity);
        }
        int updated = jdbc.update("""
                UPDATE wms_inventory_batches
                   SET sku = ?, product_name = ?, production_date = ?, expiry_date = ?,
                       quantity = ?, reserved_qty = ?, unit_cost = ?, location_code = ?, status = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.sku().trim(),
                payload.productName() == null ? existing.productName() : payload.productName(),
                payload.productionDate() == null ? existing.productionDate() : payload.productionDate(),
                payload.expiryDate() == null ? existing.expiryDate() : payload.expiryDate(),
                quantity, reserved,
                payload.unitCost() == null ? existing.unitCost() : payload.unitCost(),
                payload.locationCode() == null ? existing.locationCode() : payload.locationCode(),
                IntlFields.orDefault(payload.status(), existing.status()),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("库存批次不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        get(id);
        int deleted = jdbc.update("DELETE FROM wms_inventory_batches WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("库存批次不存在: " + id);
        }
    }
}
