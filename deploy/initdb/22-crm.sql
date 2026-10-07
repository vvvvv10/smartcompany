-- CRM 库：客户、联系人、商机、跟进记录
-- 幂等：可在已有实例上重复执行（initdb 只在 MySQL 首次初始化时自动跑，
-- 已有部署用 cat 22-crm.sql | docker exec -i stack-mysql mysql 手动导入）

CREATE DATABASE IF NOT EXISTS crm DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE crm;

-- ---------------- 客户 ----------------
-- status: POTENTIAL 潜在 / FOLLOWING 跟进中 / DEAL 成交 / LOST 流失
-- level:  KEY 重点 / NORMAL 普通 / LOW 低优先级
CREATE TABLE IF NOT EXISTS customers (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '客户ID',
    name        VARCHAR(64)  NOT NULL COMMENT '客户名称',
    industry    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '行业',
    level       VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'KEY/NORMAL/LOW',
    source      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '来源',
    status      VARCHAR(16)  NOT NULL DEFAULT 'POTENTIAL' COMMENT 'POTENTIAL/FOLLOWING/DEAL/LOST',
    phone       VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '电话',
    email       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '邮箱',
    address     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '地址',
    remark      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
    owner_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '归属人 users.id（网关注入的 X-User-Id）',
    owner_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '归属人展示名（占位，不作安全依据）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_owner (owner_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CRM客户表';

-- ---------------- 联系人 ----------------
CREATE TABLE IF NOT EXISTS contacts (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id BIGINT      NOT NULL COMMENT 'customers.id',
    name        VARCHAR(32) NOT NULL COMMENT '姓名',
    position    VARCHAR(32) NOT NULL DEFAULT '' COMMENT '职位',
    phone       VARCHAR(32) NOT NULL DEFAULT '',
    email       VARCHAR(64) NOT NULL DEFAULT '',
    is_primary  TINYINT     NOT NULL DEFAULT 0 COMMENT '1=主要联系人',
    owner_id    BIGINT      NOT NULL DEFAULT 0,
    owner_name  VARCHAR(64) NOT NULL DEFAULT '',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_customer (customer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CRM联系人表';

-- ---------------- 商机 ----------------
-- stage: LEAD 线索 / PROPOSAL 方案 / NEGOTIATION 谈判 / WON 赢单 / LOST 输单
CREATE TABLE IF NOT EXISTS opportunities (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id        BIGINT        NOT NULL COMMENT 'customers.id',
    name               VARCHAR(64)   NOT NULL COMMENT '商机名称',
    amount             DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '金额',
    stage              VARCHAR(16)   NOT NULL DEFAULT 'LEAD' COMMENT 'LEAD/PROPOSAL/NEGOTIATION/WON/LOST',
    probability        INT           NOT NULL DEFAULT 20 COMMENT '赢率 0-100',
    expected_close_date DATE         NULL DEFAULT NULL COMMENT '预计成交日期',
    remark             VARCHAR(255)  NOT NULL DEFAULT '',
    owner_id           BIGINT        NOT NULL DEFAULT 0,
    owner_name         VARCHAR(64)   NOT NULL DEFAULT '',
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_customer (customer_id),
    KEY idx_stage (stage)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CRM商机表';

-- ---------------- 跟进记录 ----------------
-- type: CALL 电话 / VISIT 拜访 / WECHAT 微信 / MAIL 邮件 / OTHER 其他
CREATE TABLE IF NOT EXISTS follow_ups (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id    BIGINT       NOT NULL COMMENT 'customers.id',
    type           VARCHAR(16)  NOT NULL DEFAULT 'OTHER' COMMENT 'CALL/VISIT/WECHAT/MAIL/OTHER',
    content        VARCHAR(1000) NOT NULL COMMENT '跟进内容',
    next_follow_at DATETIME     NULL DEFAULT NULL COMMENT '下次跟进时间，NULL=暂不安排',
    creator_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人 users.id',
    creator_name   VARCHAR(64)  NOT NULL DEFAULT '',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_customer (customer_id),
    KEY idx_next_follow (next_follow_at),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CRM跟进记录表';

-- ---------------- 种子数据（仅当表为空时插入，重复执行不重复灌数据） ----------------
INSERT INTO customers (name, industry, level, source, status, phone, email, address, remark, owner_id, owner_name)
SELECT * FROM (
    SELECT '云启科技' AS name, '互联网' AS industry, 'KEY' AS level, '线上咨询' AS source,
           'FOLLOWING' AS status, '021-66881000' AS phone, 'user15@example.com' AS email,
           '上海市浦东新区张江路 88 号' AS address, '重点客户，季度复购意向强' AS remark,
           1 AS owner_id, '管理员' AS owner_name
    UNION ALL SELECT '恒远制造', '制造业', 'NORMAL', '展会', 'DEAL', '0755-88990011',
           'user61@example.com', '深圳市宝安区工业大道 12 号', '已签约年度服务合同', 1, '管理员'
    UNION ALL SELECT '蓝海医疗', '医疗健康', 'KEY', '转介绍', 'POTENTIAL', '010-55667788',
           'user35@example.com', '北京市海淀区中关村大街 5 号', '价格敏感，需突出性价比', 1, '管理员'
    UNION ALL SELECT '晨光教育', '教育培训', 'LOW', '电话开发', 'LOST', '027-87654321',
           'user33@example.com', '武汉市武昌区珞喻路 100 号', '预算不足暂缓', 1, '管理员'
    UNION ALL SELECT '星辰物流', '物流运输', 'NORMAL', '线上咨询', 'FOLLOWING', '021-22334455',
           'user58@example.com', '上海市青浦区华徐公路 333 号', '正在做方案对比', 1, '管理员'
) seed
WHERE NOT EXISTS (SELECT 1 FROM customers);

INSERT INTO contacts (customer_id, name, position, phone, email, is_primary, owner_id, owner_name)
SELECT * FROM (
    SELECT 1 AS customer_id, '林建国' AS name, '采购总监' AS position, '13800000006' AS phone,
           'user44@example.com' AS email, 1 AS is_primary, 1 AS owner_id, '管理员' AS owner_name
    UNION ALL SELECT 1, '赵敏', '技术负责人', '13800000006', 'user78@example.com', 0, 1, '管理员'
    UNION ALL SELECT 2, '孙立', '厂长', '13800000006', 'user70@example.com', 1, 1, '管理员'
    UNION ALL SELECT 3, '周慧', '信息科主任', '13800000006', 'user79@example.com', 1, 1, '管理员'
    UNION ALL SELECT 5, '吴刚', '运营经理', '13800000006', 'user75@example.com', 1, 1, '管理员'
) seed
WHERE NOT EXISTS (SELECT 1 FROM contacts);

INSERT INTO opportunities (customer_id, name, amount, stage, probability, expected_close_date, remark, owner_id, owner_name)
SELECT * FROM (
    SELECT 1 AS customer_id, '云启-平台年费续约' AS name, 120000.00 AS amount, 'NEGOTIATION' AS stage,
           70 AS probability, '2026-10-31' AS expected_close_date, '续约谈判中，可给 5% 折扣' AS remark,
           1 AS owner_id, '管理员' AS owner_name
    UNION ALL SELECT 3, '蓝海-HIS 系统集成', 860000.00, 'PROPOSAL', 50, '2026-11-15', '方案已提交，等待评审', 1, '管理员'
    UNION ALL SELECT 5, '星辰-调度系统一期', 450000.00, 'LEAD', 20, '2026-12-20', '需求调研阶段', 1, '管理员'
    UNION ALL SELECT 2, '恒远-产线 IoT 改造', 230000.00, 'WON', 100, '2026-08-30', '已签单，进入交付', 1, '管理员'
    UNION ALL SELECT 4, '晨光-教务系统', 60000.00, 'LOST', 0, '2026-07-10', '被竞品以低价抢走', 1, '管理员'
) seed
WHERE NOT EXISTS (SELECT 1 FROM opportunities);

INSERT INTO follow_ups (customer_id, type, content, next_follow_at, creator_id, creator_name)
SELECT * FROM (
    SELECT 1 AS customer_id, 'CALL' AS type, '与林总确认续约意向，对方要求季度付款' AS content,
           '2026-10-08 10:00:00' AS next_follow_at, 1 AS creator_id, '管理员' AS creator_name
    UNION ALL SELECT 1, 'WECHAT', '发送了新版报价单，等待反馈', NULL, 1, '管理员'
    UNION ALL SELECT 3, 'VISIT', '上门演示方案，信息科反馈良好', '2026-10-05 14:00:00', 1, '管理员'
    UNION ALL SELECT 5, 'CALL', '首次接通，约定下周技术交流', '2026-10-03 15:30:00', 1, '管理员'
    UNION ALL SELECT 2, 'MAIL', '合同扫描件已回签，归档完成', NULL, 1, '管理员'
) seed
WHERE NOT EXISTS (SELECT 1 FROM follow_ups);
