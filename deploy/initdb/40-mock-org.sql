-- Mock 组织架构（组织架构 / 权限中心 / 我的团队 的演示数据）
--
-- 造一棵像样的树：示例集团集团 → 5 个事业群/中台 → 11 个部门，
-- 配 24 个 mock 账号（13900000xxx 号段、花名式昵称、alibaba 租户、挂 USER 角色）
-- 分布在各团队当成员/负责人，让组织架构、批量拉人、我的团队、子树权限并集
-- 这些功能都有料可看——空树看不出「谁归谁管」。
--
-- mock 账号统一密码 DEMO_PASSWORD：摘要不写死，运行时取 1 号演示账号
-- （13800000006）的 password_hash 复用，跟随主种子的密码口径。
--
-- 幂等：teams 有 uk_name、team_members 有 (team_id,user_id) 主键、users 有
-- uk_account、user_roles 有 uk_user_role，全部 IGNORE / 按名匹配，可重复执行。
-- initdb 只在 MySQL 首次初始化时自动跑，已有部署手动导入：
--   cat initdb/40-mock-org.sql | docker exec -i -e MYSQL_PWD=... stack-mysql mysql
-- 注意：本脚本只做新增，不清理线上手测留下的零碎团队（这类行按需手工删，
-- 不该进种子——别的实例上可能不是「零碎」）。

USE user_center;

-- ---------------- mock 账号 ----------------
-- 密码摘要取 1 号演示账号的（20-user-center 先跑，fresh init 下它就是 id=1）
SET @pwd := (SELECT password_hash FROM users WHERE id = 1);

-- INSERT IGNORE 对生成列上的唯一键同样生效；账号与昵称都取了全库不撞的名字
INSERT IGNORE INTO users (account, password_hash, nickname, tenant_id, status)
VALUES ('13800000028', @pwd, '逍遥子',   'alibaba', 1),
       ('13800000029', @pwd, '长庚',     'alibaba', 1),
       ('13800000030', @pwd, '天逸',     'alibaba', 1),
       ('13800000031', @pwd, '蓝河',     'alibaba', 1),
       ('13800000032', @pwd, '白露',     'alibaba', 1),
       ('13800000033', @pwd, '墨言',     'alibaba', 1),
       ('13800000034', @pwd, '赤羽',     'alibaba', 1),
       ('13800000035', @pwd, '青禾',     'alibaba', 1),
       ('13800000036', @pwd, '星野',     'alibaba', 1),
       ('13800000037', @pwd, '南栀',     'alibaba', 1),
       ('13800000038', @pwd, '云舒',     'alibaba', 1),
       ('13800000039', @pwd, '断尘',     'alibaba', 1),
       ('13800000040', @pwd, '归墟',     'alibaba', 1),
       ('13800000041', @pwd, '沧浪',     'alibaba', 1),
       ('13800000042', @pwd, '砺石',     'alibaba', 1),
       ('13800000043', @pwd, '驿舟',     'alibaba', 1),
       ('13800000044', @pwd, '仓颉',     'alibaba', 1),
       ('13800000045', @pwd, '芦雪',     'alibaba', 1),
       ('13800000046', @pwd, '长风',     'alibaba', 1),
       ('13800000047', @pwd, '若水',     'alibaba', 1),
       ('13800000048', @pwd, '沐晴',     'alibaba', 1),
       ('13800000049', @pwd, '知微',     'alibaba', 1),
       ('13800000050', @pwd, '砚秋',     'alibaba', 1),
       ('13800000051', @pwd, '安若',     'alibaba', 1);

-- mock 账号统一挂 USER（注册即获得的普通角色；权限矩阵里给这个角色勾了什么，
-- 他们就有什么——恰好能演示「团队成员权限并集」随授权矩阵变化）
INSERT IGNORE INTO user_roles (user_id, role_code)
SELECT u.id, 'USER'
FROM (SELECT '13800000028' AS account UNION ALL SELECT '13800000029' UNION ALL SELECT '13800000030'
       UNION ALL SELECT '13800000031' UNION ALL SELECT '13800000032' UNION ALL SELECT '13800000033'
       UNION ALL SELECT '13800000034' UNION ALL SELECT '13800000035' UNION ALL SELECT '13800000036'
       UNION ALL SELECT '13800000037' UNION ALL SELECT '13800000038' UNION ALL SELECT '13800000039'
       UNION ALL SELECT '13800000040' UNION ALL SELECT '13800000041' UNION ALL SELECT '13800000042'
       UNION ALL SELECT '13800000043' UNION ALL SELECT '13800000044' UNION ALL SELECT '13800000045'
       UNION ALL SELECT '13800000046' UNION ALL SELECT '13800000047' UNION ALL SELECT '13800000048'
       UNION ALL SELECT '13800000049' UNION ALL SELECT '13800000050' UNION ALL SELECT '13800000051') a
JOIN users u ON u.account = a.account;

-- ---------------- 团队树：根 ----------------
INSERT IGNORE INTO teams (name, description, parent_id)
VALUES ('示例集团集团', 'mock 组织架构根：集团整体视图，负责人自动拥有整棵树的权限并集', NULL);

-- ---------------- 第二层：事业群 / 中台 ----------------
-- 父 id 用变量接：同一层的行共享根节点，逐行写子查询太吵；
-- 变量在本会话里取自上一条刚插入的根，重复执行时取到的也是它
SET @root := (SELECT id FROM teams WHERE name = '示例集团集团');
INSERT IGNORE INTO teams (name, description, parent_id)
VALUES ('集团执行委员会', '集团最高决策与议事机构',                       @root),
       ('淘天事业群',     '淘宝/天猫核心电商与内容生态',                  @root),
       ('云计算事业群',     '阿里云与技术基础设施',                        @root),
       ('智能物流事业群',   '菜鸟仓配网络与末端配送',                      @root),
       ('职能中台',       '人力、财务、安全等公共职能',                   @root);

-- ---------------- 第三层：部门 ----------------
SET @t_tt  := (SELECT id FROM teams WHERE name = '淘天事业群');
SET @t_yun := (SELECT id FROM teams WHERE name = '云计算事业群');
SET @t_wl  := (SELECT id FROM teams WHERE name = '智能物流事业群');
SET @t_zt  := (SELECT id FROM teams WHERE name = '职能中台');
INSERT IGNORE INTO teams (name, description, parent_id)
VALUES ('淘宝事业部',       '淘宝主站与商家生态',   @t_tt),
       ('天猫事业部',       '天猫品牌零售与旗舰店', @t_tt),
       ('逛逛内容平台部',   '内容种草与社区生态',   @t_tt),
       ('阿里云平台部',     '云产品与平台服务',     @t_yun),
       ('云基础设施部',     '数据中心与网络底座',   @t_yun),
       ('仓配网络部',       '仓储与干线配送',       @t_wl),
       ('末端配送部',       '末端站点与最后一公里', @t_wl),
       ('人力资源部',       '招聘、组织发展与员工关系', @t_zt),
       ('财务部',           '预算、核算与资金管理', @t_zt),
       ('技术安全与保障部', '安全合规与技术保障',   @t_zt);

-- ---------------- 成员分布 ----------------
-- 风清扬（13800000006）= 根团队负责人 → 「我的团队」直接看到整棵子树；
-- 同时挂在执行委员会当成员，顺带演示「一个人可以属于多个团队」。
-- 每队一个 lead（is_lead=1），其余为普通成员。
INSERT IGNORE INTO team_members (team_id, user_id, is_lead)
SELECT t.id, u.id, m.is_lead
FROM (
    SELECT '示例集团集团' AS team_name, '13800000006' AS account, 1 AS is_lead UNION ALL
    SELECT '集团执行委员会', '13800000028', 1 UNION ALL
    SELECT '集团执行委员会', '13800000029', 0 UNION ALL
    SELECT '集团执行委员会', '13800000006', 0 UNION ALL
    SELECT '淘天事业群', '13800000030', 1 UNION ALL
    SELECT '淘宝事业部', '13800000031', 1 UNION ALL
    SELECT '淘宝事业部', '13800000032', 0 UNION ALL
    SELECT '淘宝事业部', '13800000033', 0 UNION ALL
    SELECT '天猫事业部', '13800000034', 1 UNION ALL
    SELECT '天猫事业部', '13800000035', 0 UNION ALL
    SELECT '逛逛内容平台部', '13800000036', 1 UNION ALL
    SELECT '逛逛内容平台部', '13800000037', 0 UNION ALL
    SELECT '云计算事业群', '13800000038', 1 UNION ALL
    SELECT '阿里云平台部', '13800000039', 1 UNION ALL
    SELECT '阿里云平台部', '13800000040', 0 UNION ALL
    SELECT '云基础设施部', '13800000041', 1 UNION ALL
    SELECT '云基础设施部', '13800000042', 0 UNION ALL
    SELECT '智能物流事业群', '13800000043', 1 UNION ALL
    SELECT '仓配网络部', '13800000044', 1 UNION ALL
    SELECT '仓配网络部', '13800000045', 0 UNION ALL
    SELECT '末端配送部', '13800000046', 1 UNION ALL
    SELECT '职能中台', '13800000047', 1 UNION ALL
    SELECT '人力资源部', '13800000048', 1 UNION ALL
    SELECT '人力资源部', '13800000051', 0 UNION ALL
    SELECT '财务部', '13800000049', 1 UNION ALL
    SELECT '技术安全与保障部', '13800000050', 1
) m
JOIN teams t ON t.name = m.team_name
JOIN users u ON u.account = m.account;
