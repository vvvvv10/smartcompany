-- =============================================================================
-- 35: 权限点命名统一（配套界面模块标签 web-console/src/components/permission-shared.tsx）
--
-- 命名规则：动词 + 对象，动词收敛为「查看 / 编辑 / 管理 / 配置 / 申请 / 审批」，
-- 对象对齐菜单与模块，模块内两两成对（查看用户↔管理用户、查看 CRM↔管理 CRM、
-- 申请花名变更↔审批花名变更）。
--
-- 另将 profile:edit 从 console 模块移到 profile：个人资料与花名申请同属「个人」，
-- 在权限中心/组织架构的按模块分组里不再分家。
--
-- 23/24 的种子值已同步改过（新库直接是新名字）；本脚本给已初始化的库幂等刷新。
-- =============================================================================

UPDATE permissions SET name = '编辑个人资料', module = 'profile' WHERE code = 'profile:edit';
UPDATE permissions SET name = '查看用户'                        WHERE code = 'user:list';
UPDATE permissions SET name = '管理 CRM'                        WHERE code = 'crm:write';
UPDATE permissions SET name = '申请花名变更'                    WHERE code = 'nickname:request';
UPDATE permissions SET name = '审批花名变更'                    WHERE code = 'nickname:review';
