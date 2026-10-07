-- OMS 权限点
INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('oms:view', '查看', 'oms', 'OMS 订单管理：商品SKU/订单/统计看板');

-- ADMIN 拥有全部权限点
INSERT IGNORE INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', code FROM permissions
 WHERE code = 'oms:view';
