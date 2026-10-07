-- 新建用户改造：手机号 + 姓名/身份证 建档，花名由服务端生成，离职可重注册
--
-- 这一版把「谁是这个人」从管理员临场发挥的四项输入，收敛成三件客观事实：
-- 手机号（登录账号）、真实姓名、身份证号。花名不再由人填——同名撞车由服务端加序号
-- （张三 → 张三2），初始密码由服务端随机生成、只在创建响应里回一次。
--
-- 关键语义变化：**账号与花名的唯一性只约束在职（status = 1）的行**。
-- 否则一个人离职后，他的手机号和花名就被占死，新人既注册不了、后台也建不了号。
-- 做法是把唯一索引从裸列挪到「仅在职才有值」的生成列上——
-- MySQL 唯一索引里多个 NULL 互不冲突，所以离职行天然让出位置：
--
--     account_active  = CASE WHEN status = 1 THEN account  END
--     nickname_active = CASE WHEN status = 1 THEN nickname END
--     UNIQUE (account_active) / UNIQUE (nickname_active)
--
-- 随之而来的两处配套（不在本脚本里，但互相咬合）：
--   1) 登录 findByAccount 本就带 `and status = 1`，重复账号只会命中在职那条；
--   2) existsByAccount / existsByNickname 同样加 `and status = 1`，
--      否则预检会拿离职行去挡活人，把生成列好不容易让出来的位置又堵回去。
-- 另外「禁用 → 启用」这一步可能点亮一个已被接管的账号，撞唯一索引前先在应用层说人话。
--
-- 幂等：MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，沿用 23/26 号脚本的做法——
-- 先查 information_schema，再用 PREPARE 执行或空转。
-- 已有部署需手动导入（initdb 只在 MySQL 首次初始化时跑，见 deploy/DEPLOY.md §5.1）：

USE user_center;

-- ---------------- 真实姓名 / 身份证号 ----------------
-- id_card 存完整值用于建档留痕，但接口与列表一律只回脱敏值（前 6 位 + 8 个 * + 后 4 位）。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'users'
       AND COLUMN_NAME  = 'real_name'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE users ADD COLUMN real_name VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''真实姓名，注册页不采集故可为空'' AFTER nickname',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'users'
       AND COLUMN_NAME  = 'id_card'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE users ADD COLUMN id_card VARCHAR(18) NOT NULL DEFAULT '''' COMMENT ''身份证号，对外只回脱敏值'' AFTER real_name',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 账号唯一性只约束在职行 ----------------
-- 一个 ALTER 内同时完成「删旧索引 → 建生成列 → 在生成列上重建唯一索引 →
-- 给裸列补一个普通索引」：MySQL 8 是原子 DDL，要么整段生效要么整段回滚，
-- 不会留下「唯一索引没了但生成列也没建」的中间态。
--
-- idx_account / idx_nickname 是给登录与预检那两条等值查询用的——
-- uk_account 挪到 account_active 上之后，裸列就没有任何索引了。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'users'
       AND COLUMN_NAME  = 'account_active'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE users DROP INDEX uk_account, ADD COLUMN account_active VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN account END) STORED, ADD UNIQUE KEY uk_account (account_active), ADD KEY idx_account (account)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 花名唯一性只约束在职行 ----------------
-- 花名同样是身份标识，离职后必须能让给下一个同名的人，否则自动生成的
-- 「张三」会被某个离职的张三永久占住，序号只会越滚越大。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'user_center'
       AND TABLE_NAME   = 'users'
       AND COLUMN_NAME  = 'nickname_active'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE users DROP INDEX uk_nickname, ADD COLUMN nickname_active VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN nickname END) STORED, ADD UNIQUE KEY uk_nickname (nickname_active), ADD KEY idx_nickname (nickname)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
