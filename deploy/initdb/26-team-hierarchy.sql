-- 团队上下级（组织架构）
--
-- 25-teams.sql 建的是**扁平**团队，够表达「谁和谁一组」，但表达不了「中心 → 组 → 小组」。
-- 这一版给 teams 加 parent_id，让团队能嵌套，权限中心因此可以按组织架构呈现权限。
--
-- 随之而来的是两条语义变化（原先只到单个团队这一层）：
--   1) 团队有效权限 = 该节点 + **整棵子树**的在职成员「自身」权限的并集；
--   2) 负责人有效权限 = 自身权限 ∪ 所带队节点整棵子树的并集。
-- 换句话说，父团队的负责人自动拥有下面所有小队的权限——这正是
-- 「team 下面的人有权限，teamlead 也自动有」在层级结构下的自然延伸，
-- 而不是给层级开的新口子。
--
-- 幂等：MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，沿用 23 号脚本的做法——
-- 先查 information_schema，再用 PREPARE 执行或空转。
-- 已有部署需手动导入（initdb 只在 MySQL 首次初始化时跑，见 deploy/DEPLOY.md §5.1）：

USE user_center;

-- ---------------- 上级团队 ----------------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'teams'
       AND COLUMN_NAME  = 'parent_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE teams ADD COLUMN parent_id BIGINT NULL DEFAULT NULL COMMENT ''上级团队 id，NULL=根节点'' AFTER id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 按父节点取子节点是建树的主查询，单独建索引
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'teams'
       AND INDEX_NAME   = 'idx_parent'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE teams ADD KEY idx_parent (parent_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
