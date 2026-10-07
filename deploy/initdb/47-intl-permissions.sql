-- =============================================================================
-- 47: 国际跨境物流权限点
--
-- 命名规则沿用 35 号：动词 + 对象。业务权限名是纯动作（37 号），
-- 分组标题由 module 给出。module 沿用已有的 tms/wms/oms/crm 四个值
-- ——**不新增 module**，这样 permission-shared.tsx 的 MODULE_LABEL /
-- MODULE_COLOR / MODULE_ORDER 一行都不用改，新权限点自动出现在
-- 权限中心的对应分组里（按 module 归堆）。
--
-- 权限点只管 Web 菜单显隐 + 路由守卫，**后端不做权限点校验**——
-- 四个业务服务的 Controller 一律「登录即可读写」，这是仓库现状（假设 A5）。
-- 也就是说这 8 个权限点的实际效果是「谁在侧边栏看得到国际物流菜单」，
-- 不是「谁能调这个接口」。这个诚实缺口记在计划 §11 R7，M1 不修。
--
-- 授权策略：只给 ADMIN。
--   为什么不给 USER：现有 USER 有 crm:read/crm:write 但没有 tms:view/wms:view/oms:view，
--   说明业务数据默认不开放。国际业务涉及报关单号、客户授信、收货人地址，
--   比国内零售敏感，取严的一边。需要放开时由管理员在「权限中心」按需勾选。
--
-- 幂等：INSERT IGNORE，可重复执行。
-- =============================================================================

USE user_center;

INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('tms:intl:view', '查看', 'tms', '国际运单/承运商/航线/船期/报关：只读列表与看板'),
    ('tms:intl:edit', '编辑', 'tms', '国际运单建单改单、状态推进、轨迹录入、报关与主数据维护'),
    ('wms:intl:view', '查看', 'wms', '备货装箱贴标任务/库存批次效期/在途库存/海外仓：只读'),
    ('wms:intl:edit', '编辑', 'wms', '备货任务创建与状态推进、批次与效期维护、在途出入库'),
    ('oms:intl:view', '查看', 'oms', '出口订单与母单：只读列表与看板'),
    ('oms:intl:edit', '编辑', 'oms', '出口订单建单改单、状态推进、组批与拆批、发起订舱'),
    ('crm:intl:view', '查看', 'crm', '客户国际属性/货代渠道商/异常工单：只读'),
    ('crm:intl:edit', '编辑', 'crm', '渠道商建档、异常工单创建与流转');

-- ADMIN 全量（沿用 30/33 号口径）
INSERT IGNORE INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', code FROM permissions WHERE code LIKE '%:intl:%';

-- 需要放开 USER 时（评审后决定）再执行下面这条，幂等：
-- INSERT IGNORE INTO role_permissions (role_code, permission_code)
-- SELECT 'USER', code FROM permissions WHERE code LIKE '%:intl:view';
