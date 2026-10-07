-- ============================================================================
-- 统一审批：花名变更 与 权限申请 合成一张表、同一套两级串行审批
--
-- 背景：这两件事原来各走各的（两张表、两套端点、两个审批权限点），但业务上它们
-- 都是"员工提一个申请 → 领导批 → 系统管理员批 → 落地"。合成一张表后：
--   - 审批人只需要判断一个队列（不必在两个页签之间来回切）
--   - 待办数只有���个口径（原来要两个接口相加，还各自 403 单独降级）
--   - 两级链路一处实现，不会出现"花名单级、权限两级"这种不一致
--
-- 本脚本**幂等**，可重复执行：
--   - CREATE TABLE IF NOT EXISTS
--   - 历史数据靠 legacy_key（'n:12' / 'p:7'）唯一键去重，重复跑不会灌第二遍
--
-- 编号 55：接在 54-intl-p2-data-model.sql 之后（43~54 是国际版那一批，别插进去）
-- ============================================================================

USE user_center;

CREATE TABLE IF NOT EXISTS approval_requests (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL COMMENT '申请人 users.id',
    type              VARCHAR(16)  NOT NULL COMMENT 'NICKNAME 花名变更 / PERMISSION 权限申请',
    prev_value        VARCHAR(64)  NOT NULL DEFAULT ''
                     COMMENT 'NICKNAME: 提交时的原花名（通过前身份不变）；PERMISSION: 空',
    target            VARCHAR(128) NOT NULL COMMENT 'NICKNAME: 期望新花名 / PERMISSION: 权限码',
    module            VARCHAR(32)  NOT NULL DEFAULT ''
                     COMMENT '决定第 2 级谁能批（取 permissions.module）；NICKNAME 固定 profile',
    note              VARCHAR(255) NOT NULL DEFAULT '' COMMENT '申请理由，选填',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING / APPROVED / REJECTED',
    stage             TINYINT      NOT NULL DEFAULT 1
                     COMMENT '当前待批级次：1 团队负责人 / 2 系统管理员（已办结的行只作留档）',
    lead_reviewer_id  BIGINT       NULL COMMENT '第 1 级审批人 users.id',
    lead_reviewed_at  DATETIME     NULL,
    reviewer_id       BIGINT       NULL COMMENT '终审人 users.id',
    review_note       VARCHAR(255) NULL COMMENT '驳回理由（驳回必填）',
    reviewed_at       DATETIME     NULL,
    legacy_key        VARCHAR(32)  NULL COMMENT '迁移幂等键：n:<旧id> / p:<旧id>，新单为 NULL',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_legacy (legacy_key),
    -- 审批中心默认「待审批 + 最早提的先审」，这个组合索引正好覆盖
    KEY idx_status_stage (status, stage, created_at),
    -- 「我是否已有在途申请」与「我的申请」都按 user_id + status 查
    KEY idx_user_status (user_id, status),
    -- 第 2 级范围按 module 圈
    KEY idx_module (module, status, stage),
    KEY idx_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统一审批申请表（花名变更 + 权限申请，两级串行）';

-- ---------------------------------------------------------------------------
-- 历史数据迁移 1/2：花名变更（原来是单级）
--
-- 原单级已办结的行 = 当时就批完了，直接落在 stage 2 并补上 lead_reviewer_id，
-- 免得"看起来停在第 1 级却已办结"这种自相矛盾的数据。
-- 仍在 PENDING 的行保留 stage 1：统一成两级后它该先走团队负责人。
-- ---------------------------------------------------------------------------
INSERT INTO approval_requests
    (user_id, type, prev_value, target, module, note, status, stage,
     lead_reviewer_id, lead_reviewed_at, reviewer_id, review_note, reviewed_at,
     legacy_key, created_at, updated_at)
SELECT n.user_id,
       'NICKNAME',
       n.old_nickname,
       n.new_nickname,
       'profile',
       '',
       n.status,
       CASE WHEN n.status = 'PENDING' THEN 1 ELSE 2 END,
       CASE WHEN n.status = 'PENDING' THEN NULL ELSE n.reviewer_id END,
       CASE WHEN n.status = 'PENDING' THEN NULL ELSE n.reviewed_at END,
       n.reviewer_id,
       n.review_note,
       n.reviewed_at,
       CONCAT('n:', n.id),
       n.created_at,
       COALESCE(n.reviewed_at, n.created_at)
  FROM nickname_change_requests n
 WHERE NOT EXISTS (
       SELECT 1 FROM approval_requests a WHERE a.legacy_key = CONCAT('n:', n.id)
 );

-- ---------------------------------------------------------------------------
-- 历史数据迁移 2/2：权限申请（本来就是两级，字段一一对应）
-- module 从 permissions 表 join 出来，和当时运行时用的口径一致（取不到就留空，
-- 留空的单子第 2 级只有 ADMIN 能批——与旧逻辑同）。
-- ---------------------------------------------------------------------------
INSERT INTO approval_requests
    (user_id, type, prev_value, target, module, note, status, stage,
     lead_reviewer_id, lead_reviewed_at, reviewer_id, review_note, reviewed_at,
     legacy_key, created_at, updated_at)
SELECT p.user_id,
       'PERMISSION',
       '',
       p.permission_code,
       COALESCE(perm.module, ''),
       COALESCE(p.note, ''),
       p.status,
       p.stage,
       p.lead_reviewer_id,
       p.lead_reviewed_at,
       p.reviewer_id,
       p.review_note,
       p.reviewed_at,
       CONCAT('p:', p.id),
       p.created_at,
       COALESCE(p.reviewed_at, p.created_at)
  FROM permission_requests p
  LEFT JOIN permissions perm ON perm.code = p.permission_code
 WHERE NOT EXISTS (
       SELECT 1 FROM approval_requests a WHERE a.legacy_key = CONCAT('p:', p.id)
 );

-- 注意：**这里刻意不 drop 旧表**。
-- 旧端点（/api/admin/nickname-requests、/api/admin/permission-requests）此刻还有
-- 客户端在用（本机两个 App + web 管理台），而 user-center 的审批域正有并行改动在途。
-- 先让新旧并存、行为可对照，等那条线收工后再单独一个脚本收尾（drop + 删端点）。
