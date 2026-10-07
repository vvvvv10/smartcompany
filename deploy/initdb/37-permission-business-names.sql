-- =============================================================================
-- 37: 业务权限点名改为纯动作（配合界面按业务分组展示）
--
-- 业务系统（CRM/TMS/WMS/OMS）的权限名不再重复业务词：分组标题给出业务，
-- 组内只留动作——界面上呈现为「CRM：查看、修改」「TMS 运输：查看」。
-- 管理台权限（控制台/用户管理/权限配置/个人）不是业务线，保留描述性名字。
--
-- 展示前提：权限中心概览、授权矩阵、团队/组织面板本就按模块分组渲染；
-- 「我的权限点」与「我的身份」两处平铺列表已改为按业务分组（web-console
-- groupPermissionCodes）。description 保留业务上下文，此处只动 name。
-- =============================================================================

UPDATE permissions SET name = '查看' WHERE code = 'crm:read';
UPDATE permissions SET name = '修改' WHERE code = 'crm:write';
UPDATE permissions SET name = '查看' WHERE code = 'tms:view';
UPDATE permissions SET name = '查看' WHERE code = 'wms:view';
UPDATE permissions SET name = '查看' WHERE code = 'oms:view';
