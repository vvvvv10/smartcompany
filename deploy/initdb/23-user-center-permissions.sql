-- 用户中心增强：角色/权限点/角色权限矩阵 + 钉钉/企微组织成员表 + 花名唯一化
-- 幂等：可在已有实例上重复执行（initdb 只在 MySQL 首次初始化时自动跑，
-- 已有部署用 cat 23-user-center-permissions.sql | docker exec -i stack-mysql mysql 手动导入）

USE user_center;

-- ---------------- 角色定义 ----------------
-- user_roles 仍然只存 code，这里补上角色的元信息（名称/描述），供权限配置页展示
CREATE TABLE IF NOT EXISTS roles (
    code        VARCHAR(32)  NOT NULL COMMENT '角色编码，如 ADMIN / USER',
    name        VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '描述',
    builtin     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=内置角色不可删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色定义表';

-- ---------------- 权限点 ----------------
-- 权限点是功能级开关，前端菜单显隐 + 后端接口校验都认它
CREATE TABLE IF NOT EXISTS permissions (
    code        VARCHAR(64)  NOT NULL COMMENT '权限点，如 user:manage',
    name        VARCHAR(64)  NOT NULL COMMENT '权限名称',
    module      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '所属模块（前端按模块分组展示）',
    description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '说明',
    PRIMARY KEY (code),
    KEY idx_module (module)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限点表';

-- ---------------- 角色-权限矩阵 ----------------
CREATE TABLE IF NOT EXISTS role_permissions (
    role_code       VARCHAR(32) NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_code, permission_code),
    KEY idx_permission (permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限关联表';

-- ---------------- 钉钉/企微组织成员 ----------------
-- 注册成功后用户可选加入企业组织，一个用户在每个渠道最多一条记录
CREATE TABLE IF NOT EXISTS organization_members (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL COMMENT 'users.id',
    channel     VARCHAR(16) NOT NULL COMMENT 'DINGTALK/WECOM',
    external_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '开放平台返回的 userid',
    status      VARCHAR(16) NOT NULL DEFAULT 'JOINED' COMMENT 'JOINED/FAILED',
    detail      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '结果说明（mock 模式/错误信息）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_channel (user_id, channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '钉钉/企微组织成员表';

-- ---------------- 种子：角色 ----------------
INSERT IGNORE INTO roles (code, name, description, builtin) VALUES
    ('USER',  '普通用户', '注册即获得，可查看看板与 CRM，维护自己的资料', 1),
    ('ADMIN', '管理员',   '拥有全部权限点，可管理用户与角色权限', 1);

-- ---------------- 种子：权限点 ----------------
INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('dashboard:view', '查看数据看板', 'console',   '用户中心运行概览'),
    ('profile:edit',   '编辑个人资料', 'profile',   '修改花名等个人资料'),
    ('user:list',      '查看用户',     'user',      '用户中心：检索用户'),
    ('user:manage',    '管理用户',     'user',      '用户中心：新增用户、启停账号、分配角色'),
    ('role:manage',    '配置角色权限', 'rbac',      '角色权限矩阵的增删改'),
    ('crm:read',       '查看',         'crm',       'CRM 看板/客户/商机只读'),
    ('crm:write',      '修改',         'crm',       'CRM 客户/商机的增删改');

-- 上面是 INSERT IGNORE，已初始化过的库里改文案不会生效；这里补一条幂等刷新
UPDATE permissions SET description = '用户中心：新增用户、启停账号、分配角色'
 WHERE code = 'user:manage';

-- ---------------- 种子：角色权限 ----------------
-- USER：自助功能 + CRM 读写（登录即可用 CRM，与既有约定一致）
INSERT IGNORE INTO role_permissions (role_code, permission_code)
SELECT 'USER', code FROM permissions
 WHERE code IN ('dashboard:view', 'profile:edit', 'crm:read', 'crm:write');

-- ADMIN：全部权限点
INSERT IGNORE INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', code FROM permissions;

-- ---------------- 花名（nickname）唯一化 ----------------
-- 1) 空值与历史默认值刷成唯一占位
UPDATE users SET nickname = CONCAT('用户', id)
 WHERE nickname IS NULL OR nickname = '' OR nickname = 'user';

-- 2) 重复昵称保留最早一条，其余让位（执行两轮，处理让位后的新冲突）
UPDATE users u
  JOIN (SELECT nickname, MIN(id) AS keep_id FROM users GROUP BY nickname HAVING COUNT(*) > 1) d
    ON u.nickname = d.nickname AND u.id <> d.keep_id
   SET u.nickname = CONCAT('用户', u.id);

UPDATE users u
  JOIN (SELECT nickname, MIN(id) AS keep_id FROM users GROUP BY nickname HAVING COUNT(*) > 1) d
    ON u.nickname = d.nickname AND u.id <> d.keep_id
   SET u.nickname = CONCAT('用户', u.id);

-- 3) 加唯一索引（幂等）
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = 'user_center' AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_nickname'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE users ADD UNIQUE KEY uk_nickname (nickname)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
