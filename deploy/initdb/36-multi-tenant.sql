-- =============================================================================
-- 36: 真实多租户
--
-- 一、租户定义（user_center.tenants）
--    'alibaba' = 示例集团集团（默认，存量用户/存量业务数据全在这里；38 号前为 default=集团）
--    'taotian' = 淘天集团
--    'cainiao' = 菜鸟集团
--    users.tenant_id 早已存在（20 号迁移），登录签发 JWT 的 tid claim，
--    网关验签后注入 X-Tenant-Id 头（trusted-headers，客户端伪造会被剥掉）——
--    这条链路是现成的，本次只补「租户有名字」和「业务数据认租户」两段。
--
-- 二、业务表加 tenant_id 行级隔离（crm/tms/wms/oms 共 13 张）
--    · NOT NULL DEFAULT 'alibaba'：存量数据全部归示例集团集团，行为与改造前一致
--    · 索引 idx_tenant(tenant_id, id) 只加在「顶层列表按租户扫」的 11 张上：
--      WHERE tenant_id = ? ... ORDER BY id DESC LIMIT 正好走它的最左前缀；
--      contacts / oms_order_items 永远由父表锚定（customer_id / order_id），
--      租户条件是残余过滤，单加 (tenant_id, id) 选不中——按 34 号审计
--      「不加选不中的索引」的同一标准跳过。
--
-- 幂等性：与 31 号同样为「一次性迁移」（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS），
-- 重复执行会报 Duplicate column，不影响已执行实例。
-- =============================================================================

-- ---------------- 租户表 ----------------
USE user_center;

CREATE TABLE IF NOT EXISTS tenants (
    id         VARCHAR(32) NOT NULL COMMENT '租户ID（与 users.tenant_id、JWT tid 对齐）',
    name       VARCHAR(64) NOT NULL COMMENT '租户显示名',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '租户表';

INSERT INTO tenants (id, name) VALUES
    ('alibaba', '示例集团集团'),
    ('taotian', '淘天集团'),
    ('cainiao', '菜鸟集团')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- ---------------- CRM ----------------
USE crm;

ALTER TABLE customers
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE contacts
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id;
ALTER TABLE opportunities
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE follow_ups
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);

-- ---------------- TMS ----------------
USE tms;

ALTER TABLE tms_orders
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE tms_vehicles
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE tms_drivers
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);

-- ---------------- WMS ----------------
USE wms;

ALTER TABLE wms_warehouses
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE wms_inventory
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE wms_stock_records
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);

-- ---------------- OMS ----------------
USE oms;

ALTER TABLE oms_products
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE oms_orders
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id,
    ADD KEY idx_tenant (tenant_id, id);
ALTER TABLE oms_order_items
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'alibaba' COMMENT '租户ID' AFTER id;
