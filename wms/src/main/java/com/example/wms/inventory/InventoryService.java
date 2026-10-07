package com.example.wms.inventory;

import com.example.wms.NotFoundException;
import com.example.wms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 库存数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * warehouseId/keyword 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT i.id, i.warehouse_id, w.name AS warehouse_name,
                   i.product_name, i.sku, i.quantity, i.updated_at
            FROM wms_inventory i
            LEFT JOIN wms_warehouses w ON w.id = i.warehouse_id
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public PageResult<Inventory> search(Long warehouseId, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND i.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (warehouseId != null) {
            where.append(" AND i.warehouse_id = :warehouseId ");
            params.addValue("warehouseId", warehouseId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (i.product_name LIKE :kw OR i.sku LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM wms_inventory i" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<Inventory> list = named.query(
                BASE_SELECT + where + " ORDER BY i.id DESC LIMIT :limit OFFSET :offset",
                params, Inventory.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public Inventory get(long id) {
        List<Inventory> rows = jdbc.query(
                BASE_SELECT + " WHERE i.id = ? AND i.tenant_id = ?", Inventory.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("库存记录不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 创建库存。如果同一仓库同一 SKU 已存在则更新数量（UPSERT 语义）。
     */
    public Inventory create(InventoryPayload payload) {
        // 先尝试更新已有记录
        int updated = jdbc.update("""
                UPDATE wms_inventory
                   SET product_name = ?, quantity = ?
                 WHERE warehouse_id = ? AND sku = ? AND tenant_id = ?
                """,
                payload.productName().trim(),
                payload.quantity(),
                payload.warehouseId(),
                payload.sku().trim(),
                TenantContext.get());

        if (updated > 0) {
            return findByWarehouseAndSku(payload.warehouseId(), payload.sku().trim());
        }

        // 不存在则插入
        jdbc.update("""
                INSERT INTO wms_inventory (tenant_id, warehouse_id, product_name, sku, quantity)
                VALUES (?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                payload.warehouseId(),
                payload.productName().trim(),
                payload.sku().trim(),
                payload.quantity());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public Inventory update(InventoryPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少库存记录 id");
        }
        int updated = jdbc.update("""
                UPDATE wms_inventory
                   SET warehouse_id = ?, product_name = ?, sku = ?, quantity = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.warehouseId(),
                payload.productName().trim(),
                payload.sku().trim(),
                payload.quantity(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("库存记录不存在: " + payload.id());
        }
        return get(payload.id());
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM wms_inventory WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("库存记录不存在: " + id);
        }
    }

    private Inventory findByWarehouseAndSku(long warehouseId, String sku) {
        List<Inventory> rows = jdbc.query(
                BASE_SELECT + " WHERE i.warehouse_id = ? AND i.sku = ? AND i.tenant_id = ?",
                Inventory.ROW_MAPPER, warehouseId, sku, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("库存记录不存在");
        }
        return rows.get(0);
    }
}
