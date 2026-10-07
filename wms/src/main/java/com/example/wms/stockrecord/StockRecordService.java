package com.example.wms.stockrecord;

import com.example.wms.NotFoundException;
import com.example.wms.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出入库记录数据访问。用 NamedParameterJdbcTemplate 拼条件查询，
 * warehouseId/type 全部可选筛选。
 *
 * <p>所有查询按 tenant_id 行级隔离，取自 TenantContext。</p>
 *
 * <p>创建出入库记录时会同步更新库存数量（事务保证一致性）。</p>
 */
@Service
@RequiredArgsConstructor
public class StockRecordService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    private static final String BASE_SELECT = """
            SELECT r.id, r.warehouse_id, w.name AS warehouse_name,
                   r.product_name, r.type, r.quantity, r.remark, r.order_no, r.created_at
            FROM wms_stock_records r
            LEFT JOIN wms_warehouses w ON w.id = r.warehouse_id
            """;

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    /**
     * 出入库记录查询。orderNos 为逗号分隔的 OMS 订单号（精确 IN 匹配），
     * OMS 列表聚合出库情况、按订单反查记录都用它——与 keyword 模糊搜互补。
     */
    public PageResult<StockRecord> search(Long warehouseId, String type, String orderNos, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND r.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (warehouseId != null) {
            where.append(" AND r.warehouse_id = :warehouseId ");
            params.addValue("warehouseId", warehouseId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND r.type = :type ");
            params.addValue("type", type);
        }
        if (orderNos != null && !orderNos.isBlank()) {
            List<String> nos = java.util.Arrays.stream(orderNos.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(200)
                    .toList();
            if (!nos.isEmpty()) {
                where.append(" AND r.order_no IN (:orderNos) ");
                params.addValue("orderNos", nos);
            }
        }

        Long total = named.queryForObject(
                "SELECT COUNT(*) FROM wms_stock_records r" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<StockRecord> list = named.query(
                BASE_SELECT + where + " ORDER BY r.id DESC LIMIT :limit OFFSET :offset",
                params, StockRecord.ROW_MAPPER);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    public StockRecord get(long id) {
        List<StockRecord> rows = jdbc.query(
                BASE_SELECT + " WHERE r.id = ? AND r.tenant_id = ?", StockRecord.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("出入库记录不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 创建出入库记录，并同步更新库存。
     *
     * <p>入库（IN）：库存增加</p>
     * <p>出库（OUT）：库存减少，如果库存不足则抛出异常</p>
     */
    @Transactional
    public StockRecord create(StockRecordPayload payload) {
        // 检查仓库是否存在
        Integer warehouseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_warehouses WHERE id = ? AND tenant_id = ?",
                Integer.class, payload.warehouseId(), TenantContext.get());
        if (warehouseCount == null || warehouseCount == 0) {
            throw new NotFoundException("仓库不存在: " + payload.warehouseId());
        }

        // 检查库存是否充足（出库时）
        if ("OUT".equals(payload.type())) {
            Integer currentQty = getInventoryQuantity(payload.warehouseId(), payload.productName());
            if (currentQty != null && currentQty < payload.quantity()) {
                throw new IllegalArgumentException("库存不足，当前库存: " + currentQty + "，出库数量: " + payload.quantity());
            }
        }

        // 插入出入库记录（order_no 关联 OMS 订单号，手工建不填则空串）
        jdbc.update("""
                INSERT INTO wms_stock_records (tenant_id, warehouse_id, product_name, type, quantity, remark, order_no)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.get(),
                payload.warehouseId(),
                payload.productName().trim(),
                payload.type(),
                payload.quantity(),
                nullSafe(payload.remark()),
                nullSafe(payload.orderNo()));

        // 更新库存
        upsertInventory(payload.warehouseId(), payload.productName().trim(), payload.type(), payload.quantity());

        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM wms_stock_records WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("出入库记录不存在: " + id);
        }
    }

    /**
     * 获取当前库存数量。
     */
    private Integer getInventoryQuantity(long warehouseId, String productName) {
        List<Integer> quantities = jdbc.query(
                "SELECT quantity FROM wms_inventory WHERE warehouse_id = ? AND product_name = ? AND tenant_id = ?",
                (rs, i) -> rs.getInt("quantity"),
                warehouseId, productName, TenantContext.get());
        return quantities.isEmpty() ? null : quantities.get(0);
    }

    /**
     * 更新库存（UPSERT 语义）。
     */
    private void upsertInventory(long warehouseId, String productName, String type, int quantity) {
        // 先尝试更新已有记录
        int updated = jdbc.update("""
                UPDATE wms_inventory
                   SET quantity = quantity + ?
                 WHERE warehouse_id = ? AND product_name = ? AND tenant_id = ?
                """,
                "IN".equals(type) ? quantity : -quantity,
                warehouseId,
                productName,
                TenantContext.get());

        if (updated > 0) {
            return;
        }

        // 不存在则插入（仅入库时创建新库存记录）
        if ("IN".equals(type)) {
            jdbc.update("""
                    INSERT INTO wms_inventory (tenant_id, warehouse_id, product_name, sku, quantity)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    TenantContext.get(),
                    warehouseId,
                    productName,
                    "SKU-" + System.currentTimeMillis(),
                    quantity);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }
}
