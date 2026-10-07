-- =============================================================================
-- 38: 默认租户 default → alibaba（示例集团集团）
--
-- 36 号把存量全归 'default'（集团）。本次统一换默认租户：
--   一、tenants 字典：先 upsert 'alibaba' = 示例集团集团，存量改写完再删 'default' 行；
--   二、存量数据：users + 13 张业务表中 tenant_id = 'default' 的行全部改写为 'alibaba'；
--   三、列默认值：14 张表 tenant_id 的 DEFAULT 'default' → 'alibaba'，以后新建（INSERT
--       不带租户列）也落 alibaba；应用侧兜底常量同步改（JdbcUserDirectory / UserDirectory
--       / AdminUserController / 各服务 TenantContext.DEFAULT，随镜像一起发布）。
--
-- 展示约定：web/App「我的身份」的公司行显示 tenants.name（示例集团集团）；
-- 万一还有残留的 'default'，显示层兜底成「未知」。
--
-- 幂等：upsert + WHERE 过滤 + MODIFY 同值，可重复执行。
-- 令牌：老 access token 的 tid 仍是 default（15 分钟过期；refresh 会按 users 表重签），
--       迁移后短时间内老会话的业务查询可能为空，重新登录/等刷新即可。
-- =============================================================================

-- ---------------- 一、租户字典 + 用户存量 ----------------
USE user_center;

INSERT INTO tenants (id, name) VALUES ('alibaba', '示例集团集团')
ON DUPLICATE KEY UPDATE name = '示例集团集团';

UPDATE users SET tenant_id = 'alibaba' WHERE tenant_id = 'default';

DELETE FROM tenants WHERE id = 'default';

ALTER TABLE users
    MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';

-- ---------------- 二、CRM ----------------
USE crm;

UPDATE customers     SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE contacts      SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE opportunities SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE follow_ups    SET tenant_id = 'alibaba' WHERE tenant_id = 'default';

ALTER TABLE customers     MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE contacts      MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE opportunities MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE follow_ups    MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';

-- ---------------- 三、TMS ----------------
USE tms;

UPDATE tms_orders   SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE tms_vehicles SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE tms_drivers  SET tenant_id = 'alibaba' WHERE tenant_id = 'default';

ALTER TABLE tms_orders   MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE tms_vehicles MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE tms_drivers  MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';

-- ---------------- 四、WMS ----------------
USE wms;

UPDATE wms_warehouses    SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE wms_inventory     SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE wms_stock_records SET tenant_id = 'alibaba' WHERE tenant_id = 'default';

ALTER TABLE wms_warehouses    MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE wms_inventory     MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE wms_stock_records MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';

-- ---------------- 五、OMS ----------------
USE oms;

UPDATE oms_products    SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE oms_orders      SET tenant_id = 'alibaba' WHERE tenant_id = 'default';
UPDATE oms_order_items SET tenant_id = 'alibaba' WHERE tenant_id = 'default';

ALTER TABLE oms_products    MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE oms_orders      MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
ALTER TABLE oms_order_items MODIFY tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID';
