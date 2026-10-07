-- =============================================================================
-- 44: OMS 国际跨境物流域表结构（M1 纵向切片）
--
-- 三张新表（不复用 oms_orders）：
--   oms_export_orders      出口子单——客户的一笔货（收货人/条款/金额/申报要素）
--   oms_export_order_items 子单明细（逐行 HS 编码、申报货值、危险品属性）
--   oms_export_batches     出口母单——多张子单拼成一票货（拼批/订舱/报关的主体）
-- 为什么不用 oms_orders：它是国内 B2C 电商零售单（channel + 6 态 + 收货人），
-- 与「一票货」的语义不在一个维度上；硬改会破坏 39 号三系统联动与移动端只读页签。
--
-- 另给 oms_products 加 12 列国际申报属性：商品档案是报关申报要素的**唯一来源**，
-- 明细行只做快照。若不加这一层，每行明细都要人肉重填 HS 编码与危险品属性。
--
-- 时区口径：本文件所有 DATETIME 列一律存 **UTC**，列名一律 `_at` 后缀并在 COMMENT
-- 标注「（UTC）」；etd_date/eta_date/ready_date 是 DATE，表达「当地日期」这个
-- 业务事实，不加后缀、不做时区换算。
--
-- **但「当地」到底是哪个时区，必须逐列写清，不能只写「当地」两个字**。这是P2-2 的教训：
-- 种子就是照着「当地」两个字把 UTC 日期抄了进去，43 张子单里 6 行差一天，
-- 于是子单列表显示 10-13、详情页同一票货显示 10-14 04:00——界面自己跟自己打架：
--   etd_date  = 起运港当地日期（Asia/Shanghai）= DATE(tms_shipments.etd_at + 8h)
--   eta_date  = 目的港当地日期。**当前按 UTC 日期近似**，因为目的港时区要
--               pod_code → 国家 → 时区，本项目还没有这张表（M3 补）。不要拿
--               Asia/Shanghai 去换算 ETA，那等于用起运港时区解释目的港日期。
--   ready_date = 境内业务日期（Asia/Shanghai），不跨时区。
-- 列 COMMENT 已按此逐列写明；57 号迁移负责给存量库补同样的口径并修正数据。
--
-- 唯一索引的坑：batch_no 初始为 ''（未组批），若建唯一索引多条未组批子单会撞。
-- 因此 batch_no 只建普通索引（服务「按母单号取全部子单」），组批的唯一性由
-- 应用层在事务内保证（子单 batch_no 为空才允许写入，天然互斥）。
--
-- 幂等：建表 IF NOT EXISTS；加列走 41 号的 information_schema + PREPARE 模式
--       （MySQL 8 不支持 ADD COLUMN IF NOT EXISTS），可重复执行。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS oms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE oms;

-- ---------------- 出口子单（客户一笔货 = 一行） ----------------
-- 索引依据：idx_tenant 服务「按租户 + id 倒序分页」；idx_status 服务看板与筛选；
-- idx_batch 服务「按母单号取全部子单」（母单详情一次拉齐）；idx_customer 服务 CRM 反查。
CREATE TABLE IF NOT EXISTS oms_export_orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID（36 号规范）',
    order_no        VARCHAR(32)  NOT NULL COMMENT '出口子单号 EXP+yyyyMMdd+3位',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '出口母单号（空=未组批）；不加唯一索引，MySQL 空串会撞',
    is_batch_locked TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=已组批，组批后不允许改明细行',

    -- 客户与收货
    customer_id     BIGINT       NOT NULL DEFAULT 0 COMMENT 'CRM customers.id（跨库弱引用，只存 id；查不到时前端显示 —）',
    customer_name   VARCHAR(128) NOT NULL DEFAULT '' COMMENT '客户名称快照（跨库读不到时前端仍能显示）',
    consignee_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '境外收货人',
    consignee_company VARCHAR(128) NOT NULL DEFAULT '' COMMENT '境外收货公司',
    consignee_country  VARCHAR(2)   NOT NULL DEFAULT '' COMMENT '收货国 ISO-3166 alpha-2，如 US/DE',
    consignee_address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '境外收货地址',
    consignee_phone VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '收货联系电话（国际格式，含 + 号）',
    consignee_email VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '收货邮箱',

    -- 贸易条款与币种
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB' COMMENT 'EXW/FCA/FOB/CFR/CIF/CPT/CIP/DAP/DPU/DDP【待验证】',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD' COMMENT 'ISO-4217',
    total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '订单金额（原币）',
    freight_amount  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '应收运费（原币）',
    total_qty       INT          NOT NULL DEFAULT 0 COMMENT '总件数',
    total_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总毛重 KG',
    total_volume    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积重 KG',

    -- 时效（DATE = 当地日期，不做时区换算）
    etd_date        DATE         NULL DEFAULT NULL
        COMMENT '计划开港/起飞日（起运港当地日期=Asia/Shanghai）。= DATE(tms_shipments.etd_at + 8h)，不要再抄 UTC 日期（P2-2）',
    eta_date        DATE         NULL DEFAULT NULL
        COMMENT '预计到港日（目的港当地日期）。当前按 UTC 日期近似：目的港时区需 pod_code→国家→时区表，M3 补',
    ready_date      DATE         NULL DEFAULT NULL COMMENT '备货完成日（境内业务日期=Asia/Shanghai，不跨时区）',

    -- 状态
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/PICKING/PACKED/EXPORT_DECLARED/SHIPPED/IN_TRANSIT/ARRIVED/CUSTOMS_CLEARING/CUSTOMS_CLEARED/LAST_MILE/DELIVERED/CLOSED/EXCEPTION/CANCELLED',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_by      BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人 users.id（网关注入的 X-User-Id）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (tenant_id, order_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_batch (batch_no),
    KEY idx_status (status),
    KEY idx_customer (customer_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口订单（子单）';

-- ---------------- 出口单明细（报关申报要素） ----------------
CREATE TABLE IF NOT EXISTS oms_export_order_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    order_id        BIGINT       NOT NULL COMMENT 'oms_export_orders.id',
    order_no        VARCHAR(32)  NOT NULL COMMENT '冗余单号，列表聚合免 JOIN',
    line_no         INT          NOT NULL DEFAULT 1 COMMENT '行号（1 起）',
    product_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'oms_products.id，0=自由品名未挂商品档案',
    sku             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'SKU 快照',
    product_name    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '中文品名快照',
    customs_name    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '报关品名（可与商品名不同，报关行要求）',
    customs_name_en VARCHAR(255) NOT NULL DEFAULT '' COMMENT '英文报关品名',
    hs_code         VARCHAR(12)  NOT NULL DEFAULT '' COMMENT 'HS 编码（中国出口位数【待验证】）',
    origin_country  VARCHAR(2)   NOT NULL DEFAULT 'CN' COMMENT '原产国 ISO-3166 alpha-2',
    quantity        INT          NOT NULL DEFAULT 1 COMMENT '数量（件）',
    unit_price      DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '成交单价（原币）',
    declared_value  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '申报货值（原币）',
    net_weight      DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '单件净重 KG',
    gross_weight    DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '单件毛重 KG',
    volume          DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '单件体积 CBM',
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=危险品',
    un_number       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'UN 编号，如 UN3481【待验证】',
    dg_class        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '危险品类别，3=易燃液体 9=锂电池',
    packing_instruction VARCHAR(8) NOT NULL DEFAULT '' COMMENT '包装说明 PI967/PI966/PI969【待验证】',
    battery_watt_hours DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '电池额定瓦时 Wh（锂电池申报必填）',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_hs_code (hs_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口单明细（报关申报要素）';

-- ---------------- 出口母单（拼批：多子单 → 一票货） ----------------
-- shipment_no 在 M1 不落库：TMS 运单号由 TMS 生成，OMS 侧一律按 batch_no 反查
-- （列表聚合走 LinkageClient 批量回填）。少一列就少一处「两库要同步」的地方。
CREATE TABLE IF NOT EXISTS oms_export_batches (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    batch_no        VARCHAR(32)  NOT NULL COMMENT '母单号 MEXP+yyyyMMdd+3位',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA海运/LCL海运拼箱/AIR空运/RAIL中欧班列/TRUCK卡航/EXPRESS国际快递',
    pol_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '起运港/机场码',
    pol_name        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '起运港中文名',
    pod_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '目的港/目的地码',
    pod_name        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '目的港中文名',
    route_id        BIGINT       NOT NULL DEFAULT 0 COMMENT 'TMS tms_routes.id（弱引用）',
    carrier_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'TMS tms_carriers.id（弱引用）',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '20GP' COMMENT '20GP/40GP/40HQ/LCL',
    container_qty   INT          NOT NULL DEFAULT 1 COMMENT '箱量',
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    total_qty       INT          NOT NULL DEFAULT 0 COMMENT '本批总件数（组批时重算）',
    total_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本批总毛重 KG',
    total_volume    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本批总体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本批总体积重 KG',
    etd_date        DATE         NULL DEFAULT NULL
        COMMENT 'ETD 计划开港日（起运港当地日期=Asia/Shanghai）。= DATE(tms_shipments.etd_at + 8h)',
    eta_date        DATE         NULL DEFAULT NULL COMMENT 'ETA 预计到港日（当地）',
    cutoff_at       DATETIME     NULL DEFAULT NULL COMMENT '截关/截仓时间（UTC）',
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/BOOKING/BOOKED/LOADING/DEPARTED/IN_TRANSIT/ARRIVED/CLEARING/CLEARED/LAST_MILE/DELIVERED/CLOSED/EXCEPTION/CANCELLED',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_by      BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人 users.id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (tenant_id, batch_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_status (status),
    KEY idx_pol_pod (pol_code, pod_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口母单（拼批/一票货）';

-- ---------------- oms_products 增国际申报属性（12 列） ----------------
-- 为什么加在商品档案而不是只在明细行：报关申报要素是「货的属性」，
-- 同一 SKU 在不同子单里申报要素应当一致；让人手在明细里重填既慢又容易填错。
-- 明细行仍然存快照——商品档案改了也不该改已报关的历史单据。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，沿用 41 号脚本：查 information_schema 再 PREPARE。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'hs_code'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN hs_code VARCHAR(12) NOT NULL DEFAULT '''' COMMENT ''HS 编码'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'customs_name'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN customs_name VARCHAR(255) NOT NULL DEFAULT '''' COMMENT ''报关品名（中文）'' AFTER hs_code',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'customs_name_en'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN customs_name_en VARCHAR(255) NOT NULL DEFAULT '''' COMMENT ''报关品名（英文）'' AFTER customs_name',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'origin_country'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN origin_country VARCHAR(2) NOT NULL DEFAULT ''CN'' COMMENT ''原产国'' AFTER customs_name_en',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'is_dangerous'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN is_dangerous TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=危险品'' AFTER origin_country',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'un_number'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN un_number VARCHAR(16) NOT NULL DEFAULT '''' COMMENT ''UN 编号'' AFTER is_dangerous',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'dg_class'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN dg_class VARCHAR(8) NOT NULL DEFAULT '''' COMMENT ''危险品类别（9=锂电池）'' AFTER un_number',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'packing_instruction'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN packing_instruction VARCHAR(8) NOT NULL DEFAULT '''' COMMENT ''包装说明 PI967/PI966/PI969'' AFTER dg_class',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'battery_watt_hours'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN battery_watt_hours DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT ''电池额定瓦时 Wh'' AFTER packing_instruction',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'net_weight'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN net_weight DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT ''单件净重 KG'' AFTER battery_watt_hours',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'gross_weight'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN gross_weight DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT ''单件毛重 KG'' AFTER net_weight',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'oms' AND TABLE_NAME = 'oms_products' AND COLUMN_NAME = 'volume_cbm'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE oms_products ADD COLUMN volume_cbm DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT ''单件体积 CBM'' AFTER gross_weight',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
