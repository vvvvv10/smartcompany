-- TMS/WMS 权限点
INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('tms:view', '查看', 'tms', 'TMS 运输管理：运输订单/车辆/司机'),
    ('wms:view', '查看', 'wms', 'WMS 仓储管理：仓库/库存/出入库');

-- ADMIN 拥有全部权限点
INSERT IGNORE INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', code FROM permissions
 WHERE code IN ('tms:view', 'wms:view');
