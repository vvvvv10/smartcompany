-- =============================================================================
-- 34: 主键审计 + 业务索引优化（按各服务 SQL 的 WHERE/JOIN/ORDER BY 模式补齐）
--
-- 主键审计结论（无需变更）：
--   · 17 张业务表统一 `id BIGINT AUTO_INCREMENT` 聚簇主键 ✓
--   · 配置/关联表使用自然键：
--       permissions(code) / roles(code) / role_permissions(role_code,permission_code)
--       / team_members(team_id,user_id) ✓
--
-- 一、新增索引（覆盖业务查询）：
--   1. crm.follow_ups        idx_creator_next(creator_id, next_follow_at)
--        工作台 4 处：待办/逾期/未来7天/最近跟进 WHERE creator_id = ?
--   2. crm.opportunities     idx_owner_stage(owner_id, stage)
--        工作台 4 处：我的商机 WHERE owner_id = ? AND stage NOT IN/WON
--   3. crm.customers         idx_owner_status(owner_id, status)
--        工作台 6 处：owner+status 计数、owner+created_at 近7天、最近建档
--   4. user_center.user_roles idx_role(role_code)
--        PermissionService/AdminUserService 3 处按角色反查成员
--        （原 uk_user_role(user_id,role_code) 无法服务 role_code 单条件查询）
--   5. tms.tms_orders        idx_vehicle(vehicle_id)
--        DashboardService 4 处 JOIN tms_vehicles ON o.vehicle_id = v.id
--   6. wms.wms_stock_records idx_type_created(type, created_at)
--        DashboardService 4 处：日报 WHERE type='IN'/'OUT' AND created_at >= ?
--   7. oms.oms_orders        idx_created_at(created_at)
--        DashboardService 4 处：营收/单量趋势 WHERE created_at >= ?
--   8. oms.oms_products      idx_category(category)
--        ProductService 列表筛选 AND category = :category
--
-- 二、删除冗余索引（均为其他索引/主键的最左前缀，零损失）：
--   1. user_center.team_members.idx_team(team_id)      ← PK(team_id,user_id) 前缀覆盖
--   2. wms.wms_inventory.idx_warehouse(warehouse_id)   ← uk_warehouse_sku 前缀覆盖
--   3. wms.wms_stock_records.idx_type(type)           ← 新 idx_type_created 前缀覆盖
--   4. crm.customers.idx_owner(owner_id)              ← 新 idx_owner_status 前缀覆盖
--
-- 说明：先 ADD 后 DROP，全程无索引空窗；表均为小表，INPLACE DDL 秒级完成。
-- =============================================================================

-- ---- 一、新增索引 ----
ALTER TABLE crm.follow_ups          ADD INDEX idx_creator_next  (creator_id, next_follow_at);
ALTER TABLE crm.opportunities       ADD INDEX idx_owner_stage   (owner_id, stage);
ALTER TABLE crm.customers           ADD INDEX idx_owner_status  (owner_id, status);
ALTER TABLE user_center.user_roles  ADD INDEX idx_role          (role_code);
ALTER TABLE tms.tms_orders          ADD INDEX idx_vehicle       (vehicle_id);
ALTER TABLE wms.wms_stock_records   ADD INDEX idx_type_created  (type, created_at);
ALTER TABLE oms.oms_orders          ADD INDEX idx_created_at    (created_at);
ALTER TABLE oms.oms_products        ADD INDEX idx_category      (category);

-- ---- 二、删除冗余索引 ----
ALTER TABLE user_center.team_members DROP INDEX idx_team;
ALTER TABLE wms.wms_inventory        DROP INDEX idx_warehouse;
ALTER TABLE wms.wms_stock_records    DROP INDEX idx_type;
ALTER TABLE crm.customers            DROP INDEX idx_owner;
