/**
 * 本机偏好（localStorage）。
 *
 * 这些设置**只存在本机、不跟账号走**：模块开关跟 TabConfig 一个道理——它决定
 * 「这台设备上底部显示哪几个 Tab」，不是账号属性，换账号登录不该把它重置。
 */

/**
 * 业务模块开关（与原生版 `core/store/TabConfig.kt` 的四个模块一一对应）。
 *
 * 默认全关：底部只有 审批 / 我的。用户去「我的 → 功能模块」里打开哪个才出现
 * 哪个 Tab（还要有权限，见 `lib/modules.ts`）。
 *
 * 这里只管"开没开"；标题/说明/权限码都在 `lib/modules.ts`，别在两处各写一份。
 */
export const MODULES = ['tms', 'wms', 'oms', 'crm'] as const
export type ModuleName = (typeof MODULES)[number]

const KEY: Record<ModuleName, string> = {
  tms: 'wb.tab.tms',
  wms: 'wb.tab.wms',
  oms: 'wb.tab.oms',
  crm: 'wb.tab.crm',
}

export function isModuleEnabled(m: ModuleName): boolean {
  return localStorage.getItem(KEY[m]) === '1'
}

export function setModuleEnabled(m: ModuleName, on: boolean) {
  localStorage.setItem(KEY[m], on ? '1' : '0')
}

/** 「我的申请」默认展示条数（其余折叠，点「展开全部」看）。 */
const MINE_COLLAPSED = 3
export const mineCollapsedCount = () => MINE_COLLAPSED