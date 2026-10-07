-- =============================================================================
-- 51 号迁移：国际物流单号原子发号器（P1-7）
--
-- 问题：五个发号器（运单/母单/备货任务/报关单/工单）都是「COUNT(*) + 1」，
--       并发下必然撞号；撞了之后唯一键兜底抛 DuplicateKeyException → 用户看到 500
--       而不是 409，而且原来的「重试 5 次」重试的是同一个 COUNT（表没变），
--       5 次都撞同一个号，重试完全无效。
--
-- 做法：一张序号表 + UPDATE ... SET next_seq = next_seq + 1 的原子自增。
--       InnoDB 的行锁保证同一个 (tenant_id, biz_type, biz_date) 串行取号，
--       不需要发号器服务，也不需要 Redis。取不到行时 INSERT IGNORE 一行再自增
--       （并发 INSERT 撞主键的一方走 UPDATE 分支）。
--
-- 幂等：可重复执行；每次执行只会把next_seq 往上走（号段被跳过，不影响唯一性）。
-- =============================================================================

USE oms;

CREATE TABLE IF NOT EXISTS oms_number_sequences (
    tenant_id  VARCHAR(32) NOT NULL COMMENT '租户',
    biz_type   VARCHAR(16) NOT NULL COMMENT '单据类型：ORDER/BATCH/TASK/DECL/TICKET/SHIPMENT',
    biz_date   DATE        NOT NULL COMMENT '号段日期（UTC 日期）',
    next_seq   INT         NOT NULL COMMENT '下一个可用序号（取号即 +1）',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, biz_type, biz_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '国际物流单号序号表：原子自增发号，避免 COUNT(*)+1 的并发撞号';

-- 存量数据对齐：把当日各单据类型的已用张数写进 next_seq，避免从 1 重发已用过的号
INSERT INTO oms_number_sequences (tenant_id, biz_type, biz_date, next_seq)
SELECT tenant_id, 'ORDER', UTC_DATE(), COUNT(*) + 1 FROM oms_export_orders
 WHERE order_no LIKE CONCAT('EXP', DATE_FORMAT(UTC_DATE(), '%Y%m%d'), '%')
 GROUP BY tenant_id
ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq));

USE wms;

CREATE TABLE IF NOT EXISTS wms_number_sequences (
    tenant_id  VARCHAR(32) NOT NULL COMMENT '租户',
    biz_type   VARCHAR(16) NOT NULL COMMENT '单据类型：TASK',
    biz_date   DATE        NOT NULL COMMENT '号段日期（UTC 日期）',
    next_seq   INT         NOT NULL COMMENT '下一个可用序号（取号即 +1）',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, biz_type, biz_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '备货任务单号序号表：原子自增发号';

INSERT INTO wms_number_sequences (tenant_id, biz_type, biz_date, next_seq)
SELECT tenant_id, 'TASK', UTC_DATE(), COUNT(*) + 1 FROM wms_pack_tasks
 WHERE task_no LIKE CONCAT('PK', DATE_FORMAT(UTC_DATE(), '%Y%m%d'), '%')
 GROUP BY tenant_id
ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq));

USE crm;

CREATE TABLE IF NOT EXISTS crm_number_sequences (
    tenant_id  VARCHAR(32) NOT NULL COMMENT '租户',
    biz_type   VARCHAR(16) NOT NULL COMMENT '单据类型：TICKET',
    biz_date   DATE        NOT NULL COMMENT '号段日期（UTC 日期）',
    next_seq   INT         NOT NULL COMMENT '下一个可用序号（取号即 +1）',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, biz_type, biz_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '工单单号序号表：原子自增发号';

INSERT INTO crm_number_sequences (tenant_id, biz_type, biz_date, next_seq)
SELECT tenant_id, 'TICKET', UTC_DATE(), COUNT(*) + 1 FROM crm_tickets
 WHERE ticket_no LIKE CONCAT('TK', DATE_FORMAT(UTC_DATE(), '%Y%m%d'), '%')
 GROUP BY tenant_id
ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq));

USE tms;

CREATE TABLE IF NOT EXISTS tms_number_sequences (
    tenant_id  VARCHAR(32) NOT NULL COMMENT '租户',
    biz_type   VARCHAR(16) NOT NULL COMMENT '单据类型：SHIPMENT/DECL',
    biz_date   DATE        NOT NULL COMMENT '号段日期（UTC 日期）',
    next_seq   INT         NOT NULL COMMENT '下一个可用序号（取号即 +1）',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, biz_type, biz_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '运单/报关单单号序号表：原子自增发号';

INSERT INTO tms_number_sequences (tenant_id, biz_type, biz_date, next_seq)
SELECT tenant_id, 'SHIPMENT', UTC_DATE(), COUNT(*) + 1 FROM tms_shipments
 WHERE shipment_no LIKE CONCAT('SHP', DATE_FORMAT(UTC_DATE(), '%Y%m%d'), '%')
 GROUP BY tenant_id
ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq));

INSERT INTO tms_number_sequences (tenant_id, biz_type, biz_date, next_seq)
SELECT tenant_id, 'DECL', UTC_DATE(), COUNT(*) + 1 FROM tms_customs_declarations
 WHERE declaration_no LIKE CONCAT('DECL', DATE_FORMAT(UTC_DATE(), '%Y%m%d'), '%')
 GROUP BY tenant_id
ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq));