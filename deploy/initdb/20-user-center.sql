USE user_center;

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    account       VARCHAR(32)  NOT NULL COMMENT '登录账号（手机号/邮箱/用户名）',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码摘要',
    nickname      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    last_login_at DATETIME     NULL     DEFAULT NULL COMMENT '最后登录时间，看板月活统计用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户主表';

CREATE TABLE IF NOT EXISTS user_roles (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    user_id   BIGINT      NOT NULL COMMENT 'users.id',
    role_code VARCHAR(32) NOT NULL COMMENT '角色编码，如 ADMIN / USER',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户角色表';

-- 测试账号：密码 Test@123456（BCrypt），注册接口正常使用时不需要这条数据
INSERT INTO users (account, password_hash, nickname, tenant_id, status)
VALUES ('13800000006', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '测试用户', 'alibaba', 1)
ON DUPLICATE KEY UPDATE updated_at = NOW();

INSERT INTO user_roles (user_id, role_code)
SELECT id, 'USER' FROM users WHERE account = '13800000006'
AND NOT EXISTS (SELECT 1 FROM user_roles WHERE user_id = users.id AND role_code = 'USER');

-- 第一个账号同时是管理员，控制台登录后才能看到管理菜单
INSERT IGNORE INTO user_roles (user_id, role_code)
SELECT id, 'ADMIN' FROM users WHERE account = '13800000006';
