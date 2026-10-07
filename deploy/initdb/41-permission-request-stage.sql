-- =============================================================================
-- 41: 权限申请两级审批（组织上一级 → 系统管理员）+ 系统管理员配置表
--
-- 审批模型从「ADMIN 单级批」改为两级串行：
--   第 1 级 组织上一级 = 申请人所在团队的负责人（直属团队负责人）。
--        申请人没加入任何团队、或所在团队的负责人就是本人 → 提交时直接落到第 2 级。
--   第 2 级 系统管理员 = system_admins 里该权限所属模块（系统）的管理员；
--        模块没配管理员 → 只能等 ADMIN。
--   ADMIN 角色不按级走：任意一级点批准 = 整单直接通过（硬兜底，与 resolve 里
--   「ADMIN = 全量权限点」同一哲学，避免管理员还要点两次）。
--
-- 「系统」= permissions.module（console/crm/oms/profile/rbac/tms/user/wms 八个），
-- 系统管理员在权限中心「系统管理员」页签维护（role:manage）。
--
-- permission_requests 三个新列：
--   stage            提交时算好的起始级（1 或 2），审批推进到 2；终态保留最后所在级；
--   lead_reviewer_id 第一级审批人（第二级复用原有 reviewer_id/reviewed_at 终审留痕）；
--   lead_reviewed_at 第一级审批时间。
--
-- 权限入口：PermissionService.resolve() 读取时给「团队负责人 / 系统管理员」贴
-- permission:review（照 team:view 的先例），菜单、路由守卫、Controller firstMissing
-- 三处同源；单子级别「这一级你能不能批」由 PermissionRequestService 按 stage 判。
--
-- 幂等：建表 IF NOT EXISTS、ALTER 走 23 号 information_schema + PREPARE 模式、
-- 种子 INSERT IGNORE，可重复执行。已有部署需手动导入（initdb 只在 MySQL 首次
-- 初始化时跑，见 deploy/DEPLOY.md §5.1）。
-- =============================================================================

USE user_center;

-- ---------------- 系统（模块）管理员 ----------------
CREATE TABLE IF NOT EXISTS system_admins (
    module     VARCHAR(32) NOT NULL COMMENT 'permissions.module（系统）',
    user_id    BIGINT      NOT NULL COMMENT 'users.id',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (module, user_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统（模块）管理员——权限申请第 2 级审批人';

-- ---------------- 申请单：审批级次 + 第一级留痕 ----------------
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，沿用 23 号脚本：查 information_schema 再 PREPARE。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'permission_requests'
       AND COLUMN_NAME   = 'stage'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE permission_requests ADD COLUMN stage TINYINT NOT NULL DEFAULT 1 COMMENT ''1=待组织上一级 2=待系统管理员；终态时保留最后所在级'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'permission_requests'
       AND COLUMN_NAME   = 'lead_reviewer_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE permission_requests ADD COLUMN lead_reviewer_id BIGINT NULL COMMENT ''第 1 级审批人（团队负责人）；第 2 级走 reviewer_id'' AFTER reviewer_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'permission_requests'
       AND COLUMN_NAME   = 'lead_reviewed_at'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE permission_requests ADD COLUMN lead_reviewed_at DATETIME NULL COMMENT ''第 1 级审批时间'' AFTER lead_reviewer_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 种子：每个模块的管理员先都给 1 号演示账号 ----------------
-- 模块清单从 permissions 目录取，新模块进目录时手动补一行即可
INSERT IGNORE INTO system_admins (module, user_id)
SELECT DISTINCT module, 1 FROM permissions WHERE module <> '';

-- ---------------- 存量在途单校准 ----------------
-- 老数据都是按单级批写入的（stage 默认 1）；申请人当前没有「别人的负责人」
-- （没团队 / 负责人就是本人）的在途单要挪到第 2 级，否则会卡在没人能批的第 1 级。
UPDATE permission_requests r
  LEFT JOIN (
        SELECT DISTINCT tm.user_id AS uid
          FROM team_members tm
          JOIN team_members ld ON ld.team_id = tm.team_id
                               AND ld.is_lead = 1 AND ld.user_id <> tm.user_id
  ) has_lead ON has_lead.uid = r.user_id
   SET r.stage = 2
 WHERE r.status = 'PENDING' AND has_lead.uid IS NULL;
