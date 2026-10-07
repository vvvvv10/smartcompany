import type { CrmCustomer, CrmOpportunity } from './types'

/**
 * 「只看我的」过滤（纯函数）。
 *
 * CRM 的客户/商机是**全员共享池**（后端返回租户全量），而「系统管理」页是个人维度
 * ——两个数字对不上是正常的，所以列表页必须把口径标清楚，并提供这个开关。
 *
 * 规则：**owner 是我的留下；owner 为空的也留下**。空 owner 是历史数据/未分配的
 * 单子，直接藏起来会让人以为客户丢了；真要收敛得后端补数据，不是前端该做的事。
 */
export function keepByOwner<T extends { ownerName?: string | null }>(rows: T[], myName?: string | null): T[] {
  const me = (myName || '').trim()
  if (!me) return rows
  return rows.filter((r) => !r.ownerName || r.ownerName === me)
}

/** 客户状态对应的标签色，页面里统一取这里，别各自 if。 */
export function customerStatusColor(status: string): 'primary' | 'success' | 'warning' | 'danger' | undefined {
  switch (status) {
    case 'POTENTIAL':
      return 'warning'
    case 'FOLLOWING':
      return 'primary'
    case 'DEAL':
      return 'success'
    case 'LOST':
      return 'danger'
    default:
      return undefined
  }
}

/** 商机阶段对应的标签色：赢单绿、输单红、其余蓝。 */
export function stageColor(stage: string): 'primary' | 'success' | 'danger' | undefined {
  switch (stage) {
    case 'WON':
      return 'success'
    case 'LOST':
      return 'danger'
    default:
      return 'primary'
  }
}

/** 客户类型：列表与详情共用的展示字段顺序。 */
export const customerSort = (a: CrmCustomer, b: CrmCustomer) => a.id - b.id
export const opportunitySort = (a: CrmOpportunity, b: CrmOpportunity) => a.id - b.id