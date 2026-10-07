-- =============================================================================
-- 46: CRM 国际跨境物流域表结构（M1 纵向切片）
--
-- 两张新表：
--   crm_carrier_partners  货代与渠道商（合作关系、账期、授信、评级）
--   crm_tickets           出口异常工单（破损/延误/清关查验/改单/弃货，带 SLA）
-- 另给 customers 加 8 列国际属性（客户类型/国家/税号/账期/授信/默认币种/IOSS）。
--
-- 为什么渠道商表与 tms_carriers 并存而不是合表：
--   tms_carriers 管的是**运输能力**（时效、截关、免堆存、体积重除数），
--   crm_carrier_partners 管的是**合作关系**（结算币种、账期、授信、评级）。
--   同一个 MAEU 在两张表里各有一行，是有意的——把「船司的船期信息」和
--   「我们给这家船司的账期」塞进一张表，改账期就会污染运输主数据。
--
-- 两套主数据并行，不等于两张表可以毫无关联：carrier_code 指出「这家渠道商
-- 背后是哪家承运商」。少了它，「我们和 COSCO 的账期是多少」答不出来，
-- M3 生成对账单时也不知道该发给哪个 partner_id。
--
-- 报价单/合同/对账（crm_quotations / crm_quotation_items / crm_contracts）归 M3，
-- 随 M3 的端点一起建，本文件不建——建了也是无人读写的死表。
--
-- 时区口径：本文件所有 DATETIME 列一律存 **UTC**，列名一律 `_at` 后缀并在 COMMENT
-- 标注「（UTC）」；credit / sign_date 等业务日期是 DATE，不加后缀。
--
-- 幂等：建表 IF NOT EXISTS；加列走 41 号的 information_schema + PREPARE 模式。
-- 注意：carrier_code 只对**首次初始化**的库生效；已存在的实例由 54 号迁移补列并回填。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS crm DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE crm;

-- ---------------- 货代与渠道商 ----------------
CREATE TABLE IF NOT EXISTS crm_carrier_partners (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID（36 号规范）',
    partner_code    VARCHAR(32)  NOT NULL COMMENT '渠道商编号',
    carrier_code    VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '对应 TMS tms_carriers.code（承运商代码）；跨库弱引用：空串=不是承运商（报关行/海外仓/卡航），写路径不跨库校验',
    partner_name    VARCHAR(64)  NOT NULL COMMENT '渠道商名称',
    partner_type    VARCHAR(20)  NOT NULL DEFAULT 'FORWARDER' COMMENT 'FORWARDER货代/NVOCC无船承运人/AGENT货代代理/CLEARANCE_BROKER报关行/LAST_MILE尾程派送/TRUCKING卡航',
    contact_name    VARCHAR(64)  NOT NULL DEFAULT '',
    contact_phone   VARCHAR(32)  NOT NULL DEFAULT '',
    contact_email   VARCHAR(64)  NOT NULL DEFAULT '',
    country         VARCHAR(2)   NOT NULL DEFAULT 'CN' COMMENT '国家 ISO-3166 alpha-2',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD' COMMENT '结算币种 ISO-4217',
    payment_term_days INT        NOT NULL DEFAULT 0 COMMENT '我方对其账期（天）',
    credit_limit    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '我方在其处的信用额度',
    credit_used     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已用额度',
    rating          TINYINT      NOT NULL DEFAULT 3 COMMENT '合作评级 1~5【待验证】评分标准未定义',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE合作中/INACTIVE已停用',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_code (tenant_id, partner_code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_type (partner_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '货代与渠道商';

-- ---------------- 出口异常工单 ----------------
-- 关联键是 shipment_no / order_no 字符串（跨库弱引用），
-- 不存 customer_id 外键——客户名做成快照，对方改名/删档不影响历史工单可读。
CREATE TABLE IF NOT EXISTS crm_tickets (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    ticket_no       VARCHAR(32)  NOT NULL COMMENT '工单号 TK+yyyyMMdd+4位',
    customer_id     BIGINT       NOT NULL DEFAULT 0 COMMENT 'customers.id（弱引用）',
    customer_name   VARCHAR(128) NOT NULL DEFAULT '' COMMENT '客户名称快照',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '出口子单号',
    shipment_no     VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '国际运单号',
    category        VARCHAR(16)  NOT NULL DEFAULT 'DELAY' COMMENT 'DELAY延误/DAMAGE破损/CUSTOMS_HOLD清关查验/AMEND改单/ABANDON弃货/OTHER其他',
    severity        VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN待处理/FOLLOWING跟进中/RESOLVED已解决/CLOSED已关闭',
    title           VARCHAR(128) NOT NULL COMMENT '标题',
    detail          VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '问题描述',
    resolution      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '处理结果',
    owner_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '跟进人 users.id（个人工作台行级过滤口径）',
    owner_name      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '跟进人花名快照',
    due_at          DATETIME     NULL DEFAULT NULL COMMENT 'SLA 到期（UTC）',
    resolved_at     DATETIME     NULL DEFAULT NULL COMMENT '解决时间（UTC）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (tenant_id, ticket_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_status (status),
    KEY idx_owner (owner_id),
    KEY idx_shipment (shipment_no),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口异常工单';

-- ---------------- customers 增国际属性（8 列） ----------------
-- 账期与授信是报价/合同的商业基础；credit_used 在 M1 只做展示与人工维护，
-- 真正的「确认账单时累加」随 M3 的对账域一起做（无对账单就没有累加时点）。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，沿用 41 号脚本模式。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'customer_type'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN customer_type VARCHAR(16) NOT NULL DEFAULT ''SELLER'' COMMENT ''SELLER卖家/FORWARDER货代/FACTORY工厂/DISTRIBUTOR经销'' AFTER level',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'country'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN country VARCHAR(2) NOT NULL DEFAULT ''CN'' COMMENT ''国家 ISO-3166 alpha-2'' AFTER customer_type',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'tax_no'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN tax_no VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''税号'' AFTER country',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'payment_term_days'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN payment_term_days INT NOT NULL DEFAULT 0 COMMENT ''账期天数（0=款到发货）'' AFTER tax_no',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'credit_limit'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN credit_limit DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT ''信用额度（默认币种）'' AFTER payment_term_days',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'credit_used'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN credit_used DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT ''已用额度（默认币种）'' AFTER credit_limit',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'default_currency'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN default_currency CHAR(3) NOT NULL DEFAULT ''USD'' COMMENT ''默认币种 ISO-4217'' AFTER credit_used',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'ioss_no'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE customers ADD COLUMN ioss_no VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''IOSS 编号（欧盟 B2C 进口 VAT）【待验证】'' AFTER default_currency',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
