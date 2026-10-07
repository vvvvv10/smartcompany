-- =============================================================================
-- 45: WMS 国际跨境物流域表结构（M1 纵向切片）
--
-- 四张新表：
--   wms_pack_tasks          备货/装箱/贴标任务（拣货→装箱→贴标→交接四阶段）
--   wms_pack_task_items     任务明细（批次 + 生产日期 + 效期，FEFO 的取用单位）
--   wms_inventory_batches   库存批次与效期（按批次而不是按 SKU 记在库量）
--   wms_in_transit          在途库存（已出库未入库的货）
--
-- 为什么不动 wms_inventory：它的唯一键是 uk_warehouse_sku(warehouse_id, sku)，
-- 一个 SKU 只有一行，没有「批次」这个维度。国际货按批次备货（FEFO 先到期先出）、
-- 且效期是硬合规要求，所以在旁边加一张批次表，两张表并存而不是改主键。
--
-- wms_pack_tasks 不联 wms_inventory 做实时扣减：M1 的备货任务与库存批次是
-- **弱联动**（人工在 WMS 页建任务时填 order_no），库存的占用/释放随 M2 的
-- 出库联动一起做。现在就扣库存会让「演示数据一跑就变成负数」。
--
-- 在途的 status 取值域刻意把 HELD（海关查验扣留）与 LOST（丢件）分成两个值：
-- LOST 有理赔/索赔后果，看板上会进「丢件率」；HELD 只是货被海关扣着、还在。
-- 合成一个状态，演示数据就在说谎，将来丢件率看板也会被查验率污染。
--
-- 时区口径：本文件所有 DATETIME 列一律存 **UTC**，列名一律 `_at` 后缀并在 COMMENT
-- 标注「（UTC）」；production_date / expiry_date 是 DATE，表达「当地日期」这个
-- 业务事实，不加后缀、不做时区换算。
--
-- 幂等：建表 IF NOT EXISTS；加列走 41 号的 information_schema + PREPARE 模式。
-- 注意：本文件里的在途表 CREATE TABLE 改动（HELD/EXCEPTION 状态与 remark 列）
-- 只对**首次初始化**的库生效；已存在的实例由 54 号迁移补 ALTER。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS wms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wms;

-- ---------------- 备货 / 装箱 / 贴标任务 ----------------
-- 索引依据：idx_order_no 服务 OMS 列表聚合反查与「按子单取任务」；
-- idx_status 服务看板与筛选；idx_warehouse 服务仓内作业视图。
CREATE TABLE IF NOT EXISTS wms_pack_tasks (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID（36 号规范）',
    task_no         VARCHAR(32)  NOT NULL COMMENT '任务号 PK+yyyyMMdd+4位',
    order_no        VARCHAR(32)  NOT NULL COMMENT '关联出口子单号 oms_export_orders.order_no（跨库弱引用）',
    warehouse_id    BIGINT       NOT NULL COMMENT 'wms_warehouses.id',
    location_code   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '库位编码（批次作业定位，如 A-01-03）',
    task_type       VARCHAR(16)  NOT NULL DEFAULT 'PACK' COMMENT 'PACK装箱/LABEL贴标',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待处理/PICKING拣货中/PACKED已装箱/LABELLED已贴标/HANDED_OVER已交接/SHORTAGE缺料/CANCELLED已取消',
    box_no          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '箱号（自生成，如 CTN-EXP20261010001-01）',
    container_type  VARCHAR(8)   NOT NULL DEFAULT 'CARTON' COMMENT '外包装 CARTON纸箱/PALLET托盘/BAG袋/DRUM桶',
    pieces          INT          NOT NULL DEFAULT 0 COMMENT '件数',
    gross_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '毛重 KG',
    volume          DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积重 KG',
    marks           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '唛头（装箱时生成，多行用 \\n）',
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=含危险品',
    label_printed_at DATETIME    NULL DEFAULT NULL COMMENT '贴标完成时间（UTC）',
    handed_over_at  DATETIME     NULL DEFAULT NULL COMMENT '交接承运人时间（UTC）',
    assignee_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '作业人 users.id',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (tenant_id, task_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_order_no (order_no),
    KEY idx_status (status),
    KEY idx_warehouse (warehouse_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '备货/装箱/贴标任务';

-- ---------------- 备货任务明细 ----------------
CREATE TABLE IF NOT EXISTS wms_pack_task_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    task_id         BIGINT       NOT NULL COMMENT 'wms_pack_tasks.id',
    sku             VARCHAR(64)  NOT NULL COMMENT 'SKU',
    product_name    VARCHAR(128) NOT NULL DEFAULT '',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '批次号（FEFO 取用）',
    production_date DATE         NULL DEFAULT NULL COMMENT '生产日期（当地）',
    expiry_date     DATE         NULL DEFAULT NULL COMMENT '效期（当地，FEFO 主排序键）',
    quantity        INT          NOT NULL DEFAULT 0 COMMENT '应备数量',
    picked_quantity INT          NOT NULL DEFAULT 0 COMMENT '实备数量（缺料时小于应备）',
    hs_code         VARCHAR(12)  NOT NULL DEFAULT '' COMMENT 'HS 编码快照（报关要用）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_task (task_id),
    KEY idx_sku (sku)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '备货任务明细';

-- ---------------- 库存批次与效期 ----------------
-- 可用量口径 available = quantity - reserved_qty，**不建列、查询时算**。
-- 理由：多一列就多一处「谁负责维护」的问题（本项目没有触发器、没有定时对账），
-- 而算出来的口径不会漂。uk_wh_sku_batch 是种子脚本幂等的唯一键。
CREATE TABLE IF NOT EXISTS wms_inventory_batches (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    warehouse_id    BIGINT       NOT NULL COMMENT 'wms_warehouses.id',
    sku             VARCHAR(64)  NOT NULL COMMENT 'SKU',
    product_name    VARCHAR(128) NOT NULL DEFAULT '',
    batch_no        VARCHAR(32)  NOT NULL COMMENT '批次号',
    production_date DATE         NULL DEFAULT NULL COMMENT '生产日期（当地，FEFO 辅助）',
    expiry_date     DATE         NULL DEFAULT NULL COMMENT '效期（当地，FEFO 主排序键）',
    quantity        INT          NOT NULL DEFAULT 0 COMMENT '在库数量',
    reserved_qty    INT          NOT NULL DEFAULT 0 COMMENT '占用数量（已被任务/订单占用）',
    unit_cost       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '单位成本',
    location_code   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '库位',
    status          VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL正常/NEAR_EXPIRY临期/EXPIRED过期/FROZEN冻结',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wh_sku_batch (warehouse_id, sku, batch_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_fefo (warehouse_id, sku, expiry_date),
    KEY idx_expiry (expiry_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存批次（效期/FEFO）';

-- ---------------- 在途库存 ----------------
CREATE TABLE IF NOT EXISTS wms_in_transit (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT 'TMS tms_shipments.shipment_no（跨库弱引用）',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '出口子单号',
    sku             VARCHAR(64)  NOT NULL DEFAULT '',
    product_name    VARCHAR(128) NOT NULL DEFAULT '',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '',
    quantity        INT          NOT NULL DEFAULT 0 COMMENT '在途数量',
    from_warehouse_id BIGINT     NOT NULL DEFAULT 0 COMMENT '发货仓',
    to_warehouse_id BIGINT       NOT NULL DEFAULT 0 COMMENT '目的仓（海外仓/目的港仓）',
    shipped_at      DATETIME     NOT NULL COMMENT '出库时间（UTC）',
    arrived_at      DATETIME     NULL DEFAULT NULL COMMENT '到达时间（UTC）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT 'IN_TRANSIT在途/ARRIVED已到仓/RECEIVED已入库（终态）/HELD海关查验扣留（货在，未丢）/EXCEPTION异常挂起/LOST丢件（终态，触发理赔，不等于查验）',
    remark          VARCHAR(255) NOT NULL DEFAULT '' COMMENT '状态原因（如查验单号/索赔工单号）；状态本身不解释为什么，这列解释',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_shipment (shipment_no),
    KEY idx_order_no (order_no),
    KEY idx_status (status),
    KEY idx_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '在途库存';

-- ---------------- wms_warehouses 增国际属性（5 列） ----------------
-- 仓型决定备货任务的默认仓与在途入库的目标仓；时区决定海外仓的「今天」从几点开始。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，沿用 41 号脚本模式。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'wms' AND TABLE_NAME = 'wms_warehouses' AND COLUMN_NAME = 'warehouse_type'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wms_warehouses ADD COLUMN warehouse_type VARCHAR(16) NOT NULL DEFAULT ''DOMESTIC'' COMMENT ''DOMESTIC境内/OVERSEAS海外/BONDED保税/TRANSIT中转'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'wms' AND TABLE_NAME = 'wms_warehouses' AND COLUMN_NAME = 'country'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wms_warehouses ADD COLUMN country VARCHAR(2) NOT NULL DEFAULT ''CN'' COMMENT ''所在国 ISO-3166 alpha-2'' AFTER warehouse_type',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'wms' AND TABLE_NAME = 'wms_warehouses' AND COLUMN_NAME = 'timezone'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wms_warehouses ADD COLUMN timezone VARCHAR(32) NOT NULL DEFAULT ''Asia/Shanghai'' COMMENT ''IANA 时区'' AFTER country',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'wms' AND TABLE_NAME = 'wms_warehouses' AND COLUMN_NAME = 'oversea_operator'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wms_warehouses ADD COLUMN oversea_operator VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''海外仓服务商'' AFTER timezone',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'wms' AND TABLE_NAME = 'wms_warehouses' AND COLUMN_NAME = 'is_bonded'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wms_warehouses ADD COLUMN is_bonded TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=保税仓'' AFTER oversea_operator',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
