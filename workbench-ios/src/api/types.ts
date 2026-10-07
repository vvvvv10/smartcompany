/**
 * 全部 DTO 类型——从 workbench-android 的 Model.kt 逐字段移植。
 *
 * 几条由线上实测决定、不能想当然的点（与安卓端注释一致）：
 *  - 写操作（POST/PUT/PATCH）返回 **200 + 完整对象**，不是 201
 *  - 时间格式两个服务不统一：crm 侧是 ISO `"2026-09-29T20:53:32"`，
 *    user-center 侧是空格分隔 `"2026-10-01 00:24:42"`。两种都要能展示
 *  - 金额是 `60000.00`，用 number 收
 *  - `expectedCloseDate` 是 `"2026-10-31"`，可为 null
 */

// ---------------- 通用 ----------------

export interface ApiErrorBody {
    code: string
    message: string
}

export interface TokenPair {
    accessToken: string
    refreshToken: string
    tokenType?: string
    expiresIn?: number
}

export interface LoginRequest {
    account: string
    password: string
}

export interface RefreshRequest {
    refreshToken: string
}

export interface PageResult<T> {
    list: T[]
    total: number
    page: number
    size: number
}

/**
 * 当前登录人资料（`GET /api/users/me`）。
 *
 * `permissionCodes` 是权限守卫的**唯一依据**——菜单显隐与页面守卫用的是同一份数据，
 * 保证「看得见就能进」。
 */
export interface Profile {
    id: number
    account: string
    nickname: string
    dbTenantId: string
    /** 租户显示名（tenants 字典）；老后端不给该字段时回退 dbTenantId */
    tenantName?: string
    gatewayRoles: string[]
    permissionCodes: string[]
    gatewayTenantId: string
    traceId: string
}

// ---------------- 个人工作台（CRM）----------------

/**
 * 个人工作台（`GET /api/crm/workbench`）：全部为「我的」维度。
 *
 * 所有字段都有默认值——后端加字段（比如再来一个指标）不该让 App 白屏。
 */
export interface WorkbenchStats {
    customers: number
    following: number
    deal: number
    weekNew: number
    oppActive: number
    oppAmount: number
    winAmount: number
    today: number
    overdue: number
    upcoming: number
}

/** 待办。`due` 由后端按当前时间算好：OVERDUE=已过点 / TODAY=今天 / UPCOMING=未来 7 天。 */
export interface WorkbenchTodo {
    id: number
    customerId: number
    customerName: string
    type: string
    content: string
    nextFollowAt: string
    due: string
}

export interface WorkbenchOpportunity {
    id: number
    name: string
    amount: number
    stage: string
    probability: number
    /** `"2026-10-31"`，无预计成交日时为 null */
    expectedCloseDate: string | null
    customerName: string
}

export interface WorkbenchCustomer {
    id: number
    name: string
    level: string
    status: string
    createdAt: string
}

export interface WorkbenchFollowUp {
    customerName: string
    type: string
    content: string
    createdAt: string
}

export interface CrmWorkbench {
    stats: WorkbenchStats
    todos: WorkbenchTodo[]
    opportunities: WorkbenchOpportunity[]
    customers: WorkbenchCustomer[]
    followUps: WorkbenchFollowUp[]
}

// ---------------- 审批中心 ----------------

/**
 * 花名变更申请（`GET /api/admin/nickname-requests`）。
 * `reviewerId` / `reviewNote` / `reviewedAt` 在未审批时为 null。
 */
export interface NicknameRequestRow {
    id: number
    userId: number
    account: string
    /** 申请人当前花名（审批通过前一直显示它） */
    nickname: string
    oldNickname: string
    newNickname: string
    status: string
    reviewerId: number | null
    reviewNote: string | null
    createdAt: string
    reviewedAt: string | null
}

/**
 * 我的最新一条申请（`GET /api/users/me/nickname/request`）。
 *
 * **没有记录时后端返回 200 + `{code,message}` 而不是 404**——
 * 所以这里所有字段都要可缺省，缺齐了就等于「暂无申请」。
 */
export interface MyNicknameRequest {
    id?: number
    userId?: number
    account?: string
    nickname?: string
    oldNickname?: string
    newNickname?: string
    status?: string
    reviewerId?: number | null
    reviewNote?: string | null
    createdAt?: string
    reviewedAt?: string | null
}

// ---------------- 运营看板 ----------------

export interface Totals {
    totalUsers: number
    activeUsers: number
    disabledUsers: number
    todayRegistrations: number
    weekRegistrations: number
    monthActiveUsers: number
}

export interface RoleCount {
    roleCode: string
    count: number
}

export interface TrendPoint {
    date: string
    registrations: number
}

/** `GET /api/admin/dashboard?days=14` */
export interface AdminDashboard {
    totals: Totals
    roleDistribution: RoleCount[]
    trend: TrendPoint[]
}

// ---------------- 请求体 ----------------

/** 提交花名变更申请。后端校验 2-32 字、不得与当前花名相同、不得与他人重复。 */
export interface NicknameCreate {
    nickname: string
}

/** 驳回理由。后端校验 2-255 字，空理由会 400。 */
export interface RejectNote {
    note: string
}

/** `GET /api/admin/nickname-requests/pending-count` 的 `{count: n}` */
export interface PendingCount {
    count: number
}
