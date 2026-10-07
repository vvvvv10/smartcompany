-- 花名变更审批流
-- 花名是全局唯一身份标识（通讯录、看板、CRM 归属都认它），改一次就等于换一个对外身份，
-- 因此「我的身份」里不再直接改，改为提交申请 → 管理员在「审批中心」批准后才生效。
--
-- 幂等：可在已有实例上重复执行（initdb 只在 MySQL 首次初始化时自动跑，
-- 已有部署用 cat 24-nickname-approval.sql | docker exec -i stack-mysql mysql 手动导入）

USE user_center;

-- ---------------- 申请表 ----------------
CREATE TABLE IF NOT EXISTS nickname_change_requests (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL COMMENT '申请人 users.id',
    old_nickname VARCHAR(32)  NOT NULL COMMENT '提交申请时的花名（审批通过前的身份）',
    new_nickname VARCHAR(32)  NOT NULL COMMENT '期望的新花名',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING 待审批 / APPROVED 已通过 / REJECTED 已驳回',
    reviewer_id  BIGINT       NULL COMMENT '审批人 users.id，NULL=尚未审批',
    review_note  VARCHAR(255) NULL COMMENT '驳回理由；批准时为 NULL',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at  DATETIME     NULL COMMENT '审批时间',
    PRIMARY KEY (id),
    -- 审批中心默认按「待审批 + 最早提的先审」排序，这个组合索引正好覆盖
    KEY idx_status_created (status, created_at),
    -- 「我是否已有在途申请」按 user_id + status 查
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '花名变更申请（审批流）';

-- ---------------- 权限点 ----------------
-- 申请：普通用户自己发起，授给 USER；审批：授给 ADMIN（代码里 ADMIN 已硬兜底为全权限，
-- 这里显式插入是为了让「权限中心」的矩阵表看得见它，自定义角色也能勾）
INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('nickname:request', '申请花名变更', 'profile', '在「我的身份」提交花名变更申请'),
    ('nickname:review',  '审批花名变更', 'profile', '在「审批中心」批准或驳回花名变更');

INSERT IGNORE INTO role_permissions (role_code, permission_code) VALUES
    ('USER', 'nickname:request'),
    ('ADMIN', 'nickname:request'),
    ('ADMIN', 'nickname:review');

-- profile:edit 的说明已不准确（花名改走审批了），补一条幂等刷新
UPDATE permissions SET description = '维护个人资料（花名变更请走 nickname:request 审批）'
 WHERE code = 'profile:edit';
