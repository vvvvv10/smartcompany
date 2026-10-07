-- =============================================================================
-- 37: 权限自助申请与移除（权限中心「权限点」页签 + 审批中心「权限申请」页签）
--
-- 模型：权限仍由角色发放（role_permissions 是主干），用户级覆盖是叠加层——
--   user_permissions.effect = 1  直授：申请被批准后写入，在角色授权之上再加一份
--   user_permissions.effect = 0  拉黑：本人在权限中心点「删除」即时写入，压掉
--                                角色/团队继承给的同名权限（自己放弃权限无需审批）
--   有效权限 = (角色权限 ∪ 团队继承 ∪ 直授) − 拉黑   （PermissionService.resolve 末尾应用）
--
-- permission_requests 是申请单（与 nickname_change_requests 同一套审批语义：
-- 应用层预检 PENDING 去重、批准走「先抢占状态再写覆盖」的同事务）。
--
-- 权限点命名对齐 35 号规则（动词+对象，对象对齐菜单）：
--   permission:request 申请权限 / permission:review 审批权限，模块归 rbac（权限中心）。
--   申请授 USER+ADMIN（人人可申请），审批只授 ADMIN。
--
-- 幂等：建表 IF NOT EXISTS + INSERT IGNORE，可重复执行。
-- =============================================================================

USE user_center;

-- ---------------- 用户级权限覆盖 ----------------
CREATE TABLE IF NOT EXISTS user_permissions (
    user_id         BIGINT       NOT NULL COMMENT 'users.id',
    permission_code VARCHAR(64)  NOT NULL COMMENT 'permissions.code',
    effect          TINYINT      NOT NULL COMMENT '1=直授（申请批准） 0=拉黑（本人删除）',
    reviewer_id     BIGINT       NULL COMMENT '审批人 users.id；本人直接删除时为 NULL',
    note            VARCHAR(255) NULL COMMENT '申请理由/备注',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户级权限覆盖（直授/拉黑）';

-- ---------------- 权限申请单 ----------------
CREATE TABLE IF NOT EXISTS permission_requests (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL COMMENT '申请人 users.id',
    permission_code VARCHAR(64)  NOT NULL COMMENT '申请的权限点',
    note            VARCHAR(255) NULL COMMENT '申请理由（可空）',
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    review_note     VARCHAR(255) NULL COMMENT '驳回理由',
    reviewer_id     BIGINT       NULL COMMENT '审批人 users.id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at     DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_status_id (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限申请单';

-- ---------------- 权限点与角色授权 ----------------
INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('permission:request', '申请权限', 'rbac', '在「权限中心」的权限点页签提交权限申请'),
    ('permission:review',  '审批权限', 'rbac', '在「审批中心」批准或驳回权限申请');

-- 申请：人人可有；审批：只给 ADMIN（与 nickname:* 的 USER/ADMIN 授法对齐）
INSERT IGNORE INTO role_permissions (role_code, permission_code) VALUES
    ('USER',  'permission:request'),
    ('ADMIN', 'permission:request'),
    ('ADMIN', 'permission:review');
