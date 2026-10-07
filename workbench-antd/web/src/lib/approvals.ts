import type { ApprovalRequestRow } from './types'

/**
 * 统一审批的渲染字段（纯函数，单测覆盖）。
 *
 * 后端已经把花名申请和权限申请合成一张表、同一套两级审批（`/api/users/me/approvals`
 * + `/api/admin/approvals/*`），前端不再合并两套列表：一条接口、一个待办数。
 * 这张表里两类单子的字段只有 `type` / `prevValue` / `target` 不同，渲染时按 type 分发即可。
 */

/** 单子类型文案。 */
export function typeText(row: Pick<ApprovalRequestRow, 'type'>): string {
  return row.type === 'NICKNAME' ? '花名变更' : '权限申请'
}

/** 这张单「申请的东西」：花名 = 新花名，权限 = 权限码。 */
export function targetText(row: Pick<ApprovalRequestRow, 'type' | 'target'>): string {
  return row.target
}

/** 列表节点唯一键：两类单子自增 id 各自增长会撞号，type 前缀区分。 */
export function itemKey(row: Pick<ApprovalRequestRow, 'type' | 'id'>): string {
  return `${row.type}:${row.id}`
}

/** 两级链路的阶段文案：供审批列表/我的申请展示「批到哪儿了」。 */
export function stageText(row: Pick<ApprovalRequestRow, 'status' | 'stage'>): string {
  if (row.status !== 'PENDING') return row.status === 'APPROVED' ? '已批准' : '已驳回'
  return row.stage === 1 ? '第 1 级（团队负责人）待批' : '第 2 级（系统管理员）待批'
}

export interface Me {
  account: string
  id: number
  isAdmin: boolean
}

/** 「审批」页一张单的节点。花名和权限全程同型，只在渲染时按 type 分发。 */
export type ApprovalItem = ApprovalRequestRow
