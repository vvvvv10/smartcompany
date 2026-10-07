USE user_center;

-- 幂等加列：看板需要最后登录时间做月活统计，MySQL 不支持 ADD COLUMN IF NOT EXISTS
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'last_login_at'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE users ADD COLUMN last_login_at DATETIME NULL COMMENT ''最后登录时间'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 给测试账号加管理员角色，重复执行靠 uk_user_role 唯一索引兜住
INSERT IGNORE INTO user_roles (user_id, role_code)
SELECT id, 'ADMIN' FROM users WHERE account = '13800000006';
