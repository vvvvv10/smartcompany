import type { ModuleName } from './prefs'
import type { PermissionRow } from './types'

/**
 * 模块 ↔ 权限点的对应关系（**Tab 显隐与申请入口的唯一依据**）。
 *
 * 为什么要有这张表：底部 Tab 之前只看本机开关，普通用户打开 CRM 开关后会看到
 * 一个 Tab，进去才发现整页 403——"看得见却进不去"是最差的体验。现在改成
 * **开关 ∧ 权限** 才显示 Tab；没权限的模块在「我的 → 功能模块」里显示
 * 「无权限 + 申请权限」，把路走通而不是把门堵上。
 *
 * 权限码取自线上 `/api/users/permissions/catalog`（22 个码）：
 * 每个业务模块都有主码 `xxx:view`，另有并行会话新加的 `xxx:intl:view` /
 * `xxx:intl:edit`（国际版），申请时可一并勾选。
 */
export interface ModuleDef {
  /** 标题（与「我的」里显示的一致） */
  title: string
  /** 一句话说明 */
  desc: string
  /**
   * 可见所需的权限码，**任一命中即可见**。
   * 用"任一"而不是"全部"：TMS 只给了 `tms:view` 的人也该看得见 TMS 页，
   * 不该因为没有 `tms:intl:view` 就整页藏起来。
   */
  viewCodes: string[]
  /** 申请时默认选中的权限码（该模块最基础的查看权） */
  applyCode: string
  /** 权限目录里的 module 名，用于筛出该模块可申请的全部权限点 */
  catalogModule: string
  /**
   * 是否对管理员无条件可见（只给「系统管理」开：网关段按角色放行，权限点里没有）。
   * 其余模块一律只看权限点——管理员少授一个权限点也应该看不见，不开特例。
   */
  adminBypass?: boolean
}

export const MODULE_DEFS: Record<ModuleName, ModuleDef> = {
  tms: {
    title: 'TMS 运输',
    desc: '运单、车辆与司机',
    viewCodes: ['tms:view', 'tms:intl:view'],
    applyCode: 'tms:view',
    catalogModule: 'tms',
  },
  wms: {
    title: 'WMS 仓储',
    desc: '仓库、库存与出入库',
    viewCodes: ['wms:view', 'wms:intl:view'],
    applyCode: 'wms:view',
    catalogModule: 'wms',
  },
  oms: {
    title: 'OMS 订单',
    desc: '销售订单、商品与热销款',
    viewCodes: ['oms:view', 'oms:intl:view'],
    applyCode: 'oms:view',
    catalogModule: 'oms',
  },
  crm: {
    title: 'CRM 客户',
    desc: '客户、商机与跟进记录',
    viewCodes: ['crm:read', 'crm:intl:view'],
    applyCode: 'crm:read',
    catalogModule: 'crm',
  },
}

/**
 * 固定的「系统管理」页（不是可开关的模块），但同样按权限显隐。
 *
 * `adminBypass` 是为网关那段留的：**网关工作台按 ADMIN 角色放行，不走权限点**
 * ——线上实测管理员的 permissionCodes 里没有任何 `gateway:*` 码，但
 * `GET /api/admin/gateway/workbench` 返回 200；普通账号则是 403。所以这一页
 * 对管理员无条件可见，否则管理员自己都看不到网关。
 */
export const SYSTEM_MANAGE: ModuleDef = {
  title: '系统管理',
  desc: '运营数据、网关与个人待办',
  viewCodes: ['user:list', 'dashboard:view', 'crm:read'],
  applyCode: 'dashboard:view',
  catalogModule: 'console',
  adminBypass: true,
}

/** 有任一 viewCodes 命中即可见；`adminBypass` 的页面对管理员永远可见。 */
export function canSee(def: ModuleDef, permissionCodes: string[], isAdmin: boolean): boolean {
  if (isAdmin && def.adminBypass) return true
  return def.viewCodes.some((c) => permissionCodes.includes(c))
}

/** 该模块可申请的权限点（取目录里该 module 的全部码）。 */
export function applyableCodes(catalog: PermissionRow[], def: ModuleDef): PermissionRow[] {
  const fromCatalog = catalog.filter((p) => p.module === def.catalogModule)
  if (fromCatalog.length > 0) return fromCatalog
  // 目录拿不到时兜底只给主码，别让申请入口整个消失
  return def.viewCodes.map((code) => ({ code, name: code, module: def.catalogModule, description: '' }))
}