-- 团队（team）
-- 权限中心以团队为单元看授权：授权仍走「角色」，团队是只读的派生视图——
-- 团队有效权限 = 全体成员「自身权限」的并集；
-- 负责人（lead）的有效权限 = 自身权限 ∪ 所带团队的有效权限。
-- 也就是说 lead 不用被单独勾权限，队里有人有的他就自动有。
--
-- 幂等：可在已有实例上重复执行（initdb 只在 MySQL 首次初始化时自动跑，
-- 已有部署用 cat 25-teams.sql | docker exec -i stack-mysql mysql 手动导入）

USE user_center;

-- ---------------- 团队 ----------------
CREATE TABLE IF NOT EXISTS teams (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL COMMENT '团队名称',
    description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '描述',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队';

-- ---------------- 团队成员 ----------------
-- 一个人可以属于多个团队、也可以在 A 团队是成员、B 团队是负责人，
-- 所以主键是 (team_id, user_id) 而不是 user_id。
-- is_lead 只为「谁带这个队」服务，同一团队内最多一个为 1（由应用层保证，避免
-- 用唯一索引后连“暂时没有负责人”都表达不了）。
CREATE TABLE IF NOT EXISTS team_members (
    team_id   BIGINT   NOT NULL,
    user_id   BIGINT   NOT NULL COMMENT 'users.id',
    is_lead   TINYINT  NOT NULL DEFAULT 0 COMMENT '1=团队负责人，同一团队最多一个',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (team_id, user_id),
    -- 「这个人的权限要不要算上团队继承」按 user_id 反查他带哪些队
    KEY idx_user (user_id),
    KEY idx_team (team_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队成员关系';

-- 权限点不新增：团队页就在「权限中心」里，沿用该页的门槛 role:manage。
-- 这样不会出现「有 role:manage 进得来却看不见团队」的割裂。
