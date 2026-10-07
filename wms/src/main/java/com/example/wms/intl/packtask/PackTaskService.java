package com.example.wms.intl.packtask;

import com.example.wms.intl.IntlNumberGenerator;
import com.example.wms.ConflictException;
import com.example.wms.NotFoundException;
import com.example.wms.intl.IntlFields;
import com.example.wms.intl.IntlPage;
import com.example.wms.intl.IntlStateMachine;
import com.example.wms.intl.batch.InventoryBatch;
import com.example.wms.intl.batch.InventoryBatchService;
import com.example.wms.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 备货任务数据访问与状态机。
 *
 * <p><b>M1 的备货是弱联动</b>：人工在本页填 OMS 出口子单号建任务，
 * WMS 不回查 OMS、不回调它（计划 §8.2 明确 M1 只保留 OMS→TMS 一条同步写链路）。
 * 好处是备货环节挂了不影响接单；代价是「子单没有备货任务」这件事系统不会自动发现，
 * 只能靠出口订单列表上的「备货」列显示 — 让操作员自己看出来。</p>
 *
 * <p><b>任务数从明细回算</b>：pieces/grossWeight/volume 在写完 items 后重算，
 * 与明细一致为准，不信前端传来的合计。</p>
 */
@Service
@RequiredArgsConstructor
public class PackTaskService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final InventoryBatchService batchService;

    private static final String BASE_SELECT = """
            SELECT t.id, t.task_no, t.order_no, t.warehouse_id, w.name AS warehouse_name,
                   t.location_code, t.task_type, t.status, t.box_no, t.container_type, t.pieces,
                   t.gross_weight, t.volume, t.volumetric_weight, t.marks, t.is_dangerous,
                   t.label_printed_at, t.handed_over_at, t.assignee_id, t.remark,
                   t.created_at, t.updated_at
            FROM wms_pack_tasks t
            LEFT JOIN wms_warehouses w ON w.id = t.warehouse_id
            """;

    private static final String ITEM_SELECT = """
            SELECT id, task_id, sku, product_name, batch_no, production_date, expiry_date,
                   quantity, picked_quantity, hs_code, created_at
            FROM wms_pack_task_items
            """;

    public IntlPage<PackTask> search(String keyword, String status, String orderNos, Long warehouseId,
                                     Long assigneeId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.append(" AND t.tenant_id = :tenant ");
        params.addValue("tenant", TenantContext.get());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (t.task_no LIKE :kw OR t.order_no LIKE :kw OR t.box_no LIKE :kw OR t.marks LIKE :kw) ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND t.status = :status ");
            params.addValue("status", status.trim());
        }
        if (warehouseId != null) {
            where.append(" AND t.warehouse_id = :warehouseId ");
            params.addValue("warehouseId", warehouseId);
        }
        if (assigneeId != null) {
            where.append(" AND t.assignee_id = :assigneeId ");
            params.addValue("assigneeId", assigneeId);
        }
        appendNos(where, params, orderNos);

        Long total = named.queryForObject("SELECT COUNT(*) FROM wms_pack_tasks t" + where, params, Long.class);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<PackTask> list = named.query(
                BASE_SELECT + where + " ORDER BY t.id DESC LIMIT :limit OFFSET :offset",
                params, PackTask.ROW_MAPPER);
        return IntlPage.of(list, total == null ? 0 : total, safePage, safeSize);
    }

    public PackTask get(long id) {
        List<PackTask> rows = jdbc.query(
                BASE_SELECT + " WHERE t.id = ? AND t.tenant_id = ?", PackTask.ROW_MAPPER, id, TenantContext.get());
        if (rows.isEmpty()) {
            throw new NotFoundException("备货任务不存在: " + id);
        }
        return rows.get(0);
    }

    public List<PackTask> brief(String status) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get());
        sql.append(" WHERE t.tenant_id = ?");
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY t.id DESC LIMIT 500");
        return jdbc.query(sql.toString(), PackTask.ROW_MAPPER, args.toArray());
    }

    public List<PackTaskItem> items(long taskId) {
        return jdbc.query(ITEM_SELECT + " WHERE task_id = ? AND tenant_id = ? ORDER BY id",
                PackTaskItem.ROW_MAPPER, taskId, TenantContext.get());
    }

    /**
     * 详情 = 主表 + 明细 + FEFO 候选。
     *
     * <p>FEFO 候选按任务里出现的 SKU 去重后取（一个任务 3 个 SKU 就是 3 次查询，
     * 同库同表，比一次大 JOIN 便宜也更好读）。</p>
     */
    public PackTaskDetail detail(long id) {
        PackTask task = get(id);
        List<PackTaskItem> items = items(id);
        Set<String> skus = new LinkedHashSet<>();
        for (PackTaskItem item : items) {
            if (item.sku() != null && !item.sku().isBlank()) {
                skus.add(item.sku());
            }
        }
        List<InventoryBatch> candidates = new ArrayList<>();
        for (String sku : skus) {
            candidates.addAll(batchService.fefo(task.warehouseId(), sku));
        }
        return new PackTaskDetail(task, items, candidates);
    }

    @Transactional
    public PackTaskDetail create(PackTaskPayload payload) {
        requireWarehouse(payload.warehouseId());
        named.update("""
                INSERT INTO wms_pack_tasks
                    (tenant_id, task_no, order_no, warehouse_id, location_code, task_type, status, box_no, container_type, pieces, gross_weight, volume, volumetric_weight, marks, is_dangerous, assignee_id, remark)
                VALUES (:tenant_id, '', :order_no, :warehouse_id, :location_code, :task_type, :status, :box_no, :container_type, 0, 0, 0, 0, :marks, :is_dangerous, :assignee_id, :remark)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("order_no", payload.orderNo().trim())
                        .addValue("warehouse_id", payload.warehouseId())
                        .addValue("location_code", IntlFields.text(payload.locationCode()))
                        .addValue("task_type", IntlFields.orDefault(payload.taskType(), "PACK"))
                        .addValue("status", IntlFields.orDefault(payload.status(), "PENDING"))
                        .addValue("box_no", IntlFields.text(payload.boxNo()))
                        .addValue("container_type", IntlFields.orDefault(payload.containerType(), "CARTON"))
                        .addValue("marks", IntlFields.text(payload.marks()))
                        .addValue("is_dangerous", IntlFields.zero(payload.isDangerous()))
                        .addValue("assignee_id", payload.assigneeId() == null ? 0L : payload.assigneeId())
                        .addValue("remark", IntlFields.text(payload.remark()))
                        );
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE wms_pack_tasks SET task_no = ? WHERE id = ? AND tenant_id = ?",
                generateTaskNo(), id, TenantContext.get());

        insertItems(id, payload.items());
        recalcTotals(id);
        return detail(id);
    }

    @Transactional
    public PackTaskDetail update(PackTaskPayload payload) {
        if (payload.id() == null) {
            throw new IllegalArgumentException("缺少备货任务 id");
        }
        PackTask existing = get(payload.id());
        if (!"PENDING".equals(existing.status()) && !"PICKING".equals(existing.status())) {
            // 已装箱的任务要改明细等于把已经贴好标、打好唛头的货「变回去」，
            // 现场已经发生的动作无法回滚，所以这里明确拒绝而不是默默改。
            throw new ConflictException("任务已到 " + existing.status() + "，不能直接改；请用状态推进接口");
        }
        requireWarehouse(payload.warehouseId());
        int updated = jdbc.update("""
                UPDATE wms_pack_tasks
                   SET order_no = ?, warehouse_id = ?, location_code = ?, task_type = ?, box_no = ?,
                       container_type = ?, marks = ?, is_dangerous = ?, assignee_id = ?, remark = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.orderNo().trim(), payload.warehouseId(),
                IntlFields.text(payload.locationCode()),
                IntlFields.orDefault(payload.taskType(), existing.taskType()),
                IntlFields.text(payload.boxNo()),
                IntlFields.orDefault(payload.containerType(), existing.containerType()),
                payload.marks() == null ? existing.marks() : payload.marks(),
                payload.isDangerous() == null ? existing.isDangerous() : payload.isDangerous(),
                payload.assigneeId() == null ? existing.assigneeId() : payload.assigneeId(),
                payload.remark() == null ? existing.remark() : payload.remark(),
                payload.id(),
                TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("备货任务不存在: " + payload.id());
        }
        if (payload.items() != null) {
            jdbc.update("DELETE FROM wms_pack_task_items WHERE task_id = ? AND tenant_id = ?",
                    payload.id(), TenantContext.get());
            insertItems(payload.id(), payload.items());
            recalcTotals(payload.id());
        }
        return detail(payload.id());
    }

    public void delete(long id) {
        PackTask existing = get(id);
        if (!"PENDING".equals(existing.status()) && !"PICKING".equals(existing.status())
                && !"CANCELLED".equals(existing.status())) {
            throw new ConflictException("任务已到 " + existing.status() + "，货已经在作业流程里，不能删；"
                    + "确实不需要请先置为「已取消」");
        }
        jdbc.update("DELETE FROM wms_pack_task_items WHERE task_id = ? AND tenant_id = ?", id, TenantContext.get());
        int deleted = jdbc.update("DELETE FROM wms_pack_tasks WHERE id = ? AND tenant_id = ?",
                id, TenantContext.get());
        if (deleted == 0) {
            throw new NotFoundException("备货任务不存在: " + id);
        }
    }

    /**
     * 推进任务状态。**唯一的推进入口**，每次过状态机。
     *
     * <p>状态与它带出来的数据（箱号/唛头/毛重）在同一条 UPDATE 里落：
     * 不会出现「已装箱但箱号是空的」这种需要再去查一遍才看得出的中间态。</p>
     *
     * <p>M1 不联动 OMS：任务到 HANDED_OVER 时不回写子单状态（那是非阻塞反向回调，
     * 属于 M2）。这是刻意的范围取舍，见计划 §8.2 与 §11 R1。</p>
     */
    @Transactional
    public PackTaskDetail changeStatus(long id, PackTaskStatusPayload payload) {
        PackTask existing = get(id);
        IntlStateMachine.assertPackTask(existing.status(), payload.status());

        jdbc.update("""
                UPDATE wms_pack_tasks
                   SET status = ?,
                       box_no = COALESCE(NULLIF(?, ''), box_no),
                       container_type = COALESCE(NULLIF(?, ''), container_type),
                       pieces = COALESCE(?, pieces),
                       gross_weight = COALESCE(?, gross_weight),
                       volume = COALESCE(?, volume),
                       volumetric_weight = COALESCE(?, volumetric_weight),
                       marks = COALESCE(NULLIF(?, ''), marks),
                       is_dangerous = COALESCE(?, is_dangerous),
                       assignee_id = COALESCE(?, assignee_id),
                       label_printed_at = CASE WHEN ? = 'LABELLED' THEN ? ELSE label_printed_at END,
                       handed_over_at = CASE WHEN ? = 'HANDED_OVER' THEN ? ELSE handed_over_at END,
                       remark = COALESCE(NULLIF(?, ''), remark)
                 WHERE id = ? AND tenant_id = ?
                """,
                payload.status(),
                IntlFields.text(payload.boxNo()),
                IntlFields.text(payload.containerType()),
                payload.pieces(),
                payload.grossWeight(),
                payload.volume(),
                payload.volumetricWeight(),
                IntlFields.text(payload.marks()),
                payload.isDangerous(),
                payload.assigneeId(),
                payload.status(), nowUtc(),
                payload.status(), nowUtc(),
                IntlFields.text(payload.remark()),
                id, TenantContext.get());
        return detail(id);
    }

    /** 明细行增删（页面上的「加一行 SKU」）。任务必须在 PENDING/PICKING。 */
    @Transactional
    public PackTaskItem addItem(long taskId, PackTaskItemPayload payload) {
        PackTask task = get(taskId);
        requireEditable(task);
        insertItem(taskId, payload);
        recalcTotals(taskId);
        List<PackTaskItem> items = items(taskId);
        return items.get(items.size() - 1);
    }

    /**
     * 改一行明细。
     *
     * <p>为什么用 COALESCE 而不是整行覆盖：product_name / batch_no / hs_code 在建表时是
     * {@code NOT NULL DEFAULT ''}，而页面上的「加一行 SKU」表单里品名是选填。整行覆盖
     * 会把没传的字段写成 NULL，MySQL 直接抛「Column 'product_name' cannot be null」，
     * 用户看到的是一句没有信息量的 500。半覆盖 + NULLIF 的语义和 changeStatus 一致：
     * 传了就改，传空/不传就保持原值。</p>
     */
    @Transactional
    public PackTaskItem updateItem(long taskId, long itemId, PackTaskItemPayload payload) {
        PackTask task = get(taskId);
        requireEditable(task);
        int updated = jdbc.update("""
                UPDATE wms_pack_task_items
                   SET sku = ?,
                       product_name = COALESCE(NULLIF(?, ''), product_name),
                       batch_no = COALESCE(NULLIF(?, ''), batch_no),
                       production_date = ?,
                       expiry_date = ?,
                       quantity = ?,
                       picked_quantity = ?,
                       hs_code = COALESCE(NULLIF(?, ''), hs_code)
                 WHERE id = ? AND task_id = ? AND tenant_id = ?
                """,
                payload.sku().trim(),
                IntlFields.text(payload.productName()),
                IntlFields.text(payload.batchNo()),
                payload.productionDate(),
                payload.expiryDate(),
                IntlFields.zero(payload.quantity()),
                payload.pickedQuantity() == null ? IntlFields.zero(payload.quantity()) : payload.pickedQuantity(),
                IntlFields.text(payload.hsCode()),
                itemId, taskId, TenantContext.get());
        if (updated == 0) {
            throw new NotFoundException("备货任务明细不存在: " + itemId);
        }
        recalcTotals(taskId);
        return items(taskId).stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new NotFoundException("备货任务明细不存在: " + itemId));
    }

    @Transactional
    public void deleteItem(long taskId, long itemId) {
        requireEditable(get(taskId));
        jdbc.update("DELETE FROM wms_pack_task_items WHERE id = ? AND task_id = ? AND tenant_id = ?",
                itemId, taskId, TenantContext.get());
        recalcTotals(taskId);
    }

    private void requireEditable(PackTask task) {
        if (!"PENDING".equals(task.status()) && !"PICKING".equals(task.status())) {
            throw new ConflictException("任务已到 " + task.status() + "，不能改明细");
        }
    }

    private void requireWarehouse(Long warehouseId) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("缺少作业仓库");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_warehouses WHERE id = ? AND tenant_id = ?",
                Integer.class, warehouseId, TenantContext.get());
        if (count == null || count == 0) {
            throw new NotFoundException("仓库不存在: " + warehouseId);
        }
    }

    private void insertItems(long taskId, List<PackTaskItemPayload> items) {
        if (items == null) {
            return;
        }
        for (PackTaskItemPayload item : items) {
            insertItem(taskId, item);
        }
    }

    private void insertItem(long taskId, PackTaskItemPayload item) {
        named.update("""
                INSERT INTO wms_pack_task_items
                    (tenant_id, task_id, sku, product_name, batch_no, production_date, expiry_date, quantity, picked_quantity, hs_code)
                VALUES (:tenant_id, :task_id, :sku, :product_name, :batch_no, :production_date, :expiry_date, :quantity, :picked_quantity, :hs_code)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant_id", TenantContext.get())
                        .addValue("task_id", taskId)
                        .addValue("sku", item.sku().trim())
                        .addValue("product_name", IntlFields.text(item.productName()))
                        .addValue("batch_no", IntlFields.text(item.batchNo()))
                        .addValue("production_date", item.productionDate())
                        .addValue("expiry_date", item.expiryDate())
                        .addValue("quantity", IntlFields.zero(item.quantity()))
                        .addValue("picked_quantity", item.pickedQuantity() == null ? IntlFields.zero(item.quantity()) : item.pickedQuantity())
                        .addValue("hs_code", IntlFields.text(item.hsCode()))
                        );
    }

    /**
     * 件数从明细回算（应备之和）。
     *
     * <p><b>毛重与体积不在这里回算</b>：wms_pack_task_items 没有单件重量/体积列
     * （计划 §3.4.2 也没设计这两列），装箱时人工过磅才知道，所以它们由
     * PATCH /status 的 PACKED 请求带进来。硬算一个「看起来对」的数反而会掩盖
     * 「这一箱还没称重」这个事实。</p>
     */
    private void recalcTotals(long taskId) {
        named.update("""
                UPDATE wms_pack_tasks t
                   JOIN (SELECT task_id, SUM(quantity) AS pieces
                           FROM wms_pack_task_items
                          WHERE tenant_id = :tenant AND task_id = :taskId
                          GROUP BY task_id) s ON s.task_id = t.id
                   SET t.pieces = COALESCE(s.pieces, 0)
                 WHERE t.id = :taskId AND t.tenant_id = :tenant
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", TenantContext.get())
                        .addValue("taskId", taskId));
    }

    /** PK + yyyyMMdd + 4 位序列，走原子自增发号器（见 IntlNumberGenerator）。 */
    private String generateTaskNo() {
        return IntlNumberGenerator.nextNo(named, TenantContext.get(), "TASK", "PK", 4);
    }

    private static void appendNos(StringBuilder where, MapSqlParameterSource params, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        List<String> nos = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                nos.add(trimmed);
            }
        }
        if (nos.isEmpty()) {
            return;
        }
        where.append(" AND t.order_no IN (:orderNos) ");
        params.addValue("orderNos", nos.size() > 200 ? nos.subList(0, 200) : nos);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
