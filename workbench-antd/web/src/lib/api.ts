import { pushToken } from './notify'
import type {
  AdminDashboard,
  CrmContact,
  OmsDashboard,
  OmsOrder,
  OmsProduct,
  TmsDashboard,
  TmsDriver,
  TmsOrder,
  TmsVehicle,
  WmsDashboard,
  WmsInventory,
  WmsStockRecord,
  WmsWarehouse,
  CrmCustomer,
  CrmFollowUp,
  CrmOpportunity,
  CrmWorkbench,
  GatewayWorkbench,
  MyPermissions,
  ApprovalRequestRow,
  CorpDirectory,
  PageResult,
  PermissionRow,
  Profile,
  TokenPair,
} from './types'

/** 线上网关。测试环境/演示环境可在「我的 → 服务地址」里改，改动只存本机。 */
export const DEFAULT_BASE_URL = 'http://workbench.example.com'

const BASE_KEY = 'wb.baseUrl'
const ACCESS_KEY = 'wb.accessToken'
const REFRESH_KEY = 'wb.refreshToken'

let baseUrl = localStorage.getItem(BASE_KEY) || DEFAULT_BASE_URL

export const getBaseUrl = () => baseUrl

export function setBaseUrl(next: string) {
  baseUrl = next.trim().replace(/\/+$/, '')
  localStorage.setItem(BASE_KEY, baseUrl)
}

/**
 * 带 HTTP 状态码的业务异常。
 *
 * 页面要靠 `httpCode` 区分「没权限（403，该模块对当前账号隐藏）」和「真出错了」——
 * 两种情况该给用户看的文案完全不同，混成「加载失败」会让人以为系统坏了。
 */
export class ApiError extends Error {
  constructor(
    readonly httpCode: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 会话失效时的回调（App 层弹提示并回登录页）。 */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}

export function getAccessToken() {
  return localStorage.getItem(ACCESS_KEY) || ''
}

export function setTokens(pair: TokenPair) {
  localStorage.setItem(ACCESS_KEY, pair.accessToken)
  localStorage.setItem(REFRESH_KEY, pair.refreshToken)
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

function buildQuery(query?: Record<string, string | number | undefined | null>) {
  if (!query) return ''
  const parts = Object.entries(query)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

interface RequestOptions {
  method?: string
  body?: unknown
  query?: Record<string, string | number | undefined | null>
  /** 内部用：标记这是 401 后的重试，避免无限刷新 */
  retrying?: boolean
  /** 登录/刷新不需要带 accessToken（refreshToken 在 body 里），带上纯是日志噪音 */
  skipAuth?: boolean
}

/**
 * 单请求超时（毫秒）。
 *
 * **必须有**：后端某个服务挂掉时网关会一直挂着（实测遇到过整个网关 504，
 * 请求几十秒不返回），没有超时的话页面就永远停在「加载中」，用户既看不到错误
 * 也走不了——只能杀进程重开。
 *
 * 用 Promise.race 而不是 AbortController：CapacitorHttp 的原生分支（POST/PUT）
 * 不接受 signal，race 对两条通路都有效。
 */
const REQUEST_TIMEOUT_MS = 20_000

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout>
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new ApiError(0, `请求超时（${ms / 1000}s），请检查网络或服务地址`)),
      ms,
    )
  })
  return Promise.race([p, timeout]).finally(() => clearTimeout(timer)) as Promise<T>
}

async function rawRequest(path: string, opts: RequestOptions = {}) {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = getAccessToken()
  if (token && !opts.skipAuth) headers.Authorization = `Bearer ${token}`
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'

  const res = await withTimeout(
    fetch(`${baseUrl}${path}${buildQuery(opts.query)}`, {
      method: opts.method || 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    }),
    REQUEST_TIMEOUT_MS,
  )

  const text = await res.text()
  let data: any = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }

  if (!res.ok) {
    const message: string =
      (data && (data.message || data.error || data.detail)) || `请求失败（${res.status}）`
    throw new ApiError(res.status, message)
  }
  return data
}

/**
 * 刷新中的请求（single-flight）。
 *
 * refreshToken 是**一次性轮换**的：刷新成功后旧的立即作废。所以两个请求同时 401
 * 时绝不能各刷一次——第二次必然失败，还会把已经换到的新 token 一起作废掉，
 * 结果是用户莫名其妙被踢回登录页（模拟器实测：审批页的 `mine` 与 `list` 两个
 * useAsync 同时 401 → 两次 refresh → 第二次 401 → 清会话）。
 *
 * 这里让并发的那批请求共用同一个 in-flight 刷新，刷新成功后各自重放。
 * 原生版靠 OkHttp 的 Authenticator 天然串行，不存在这个问题。
 */
let refreshing: Promise<TokenPair> | null = null

function refreshTokens(): Promise<TokenPair> {
  if (!refreshing) {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (!refreshToken) return Promise.reject(new ApiError(401, '登录已失效，请重新登录'))
    refreshing = rawRequest('/api/auth/refresh', { method: 'POST', body: { refreshToken }, skipAuth: true })
      .then((pair) => {
        setTokens(pair as TokenPair)
        // 同步给原生前台服务：它读不到 localStorage，还在用旧 token 轮询
        void pushToken(pair.accessToken)
        return pair as TokenPair
      })
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

/**
 * 统一请求入口。
 *
 * 401 时刷新一次 accessToken 并重放原请求；换不到就清会话并通知上层回登录页。
 * **只重试一次**——刷新令牌也失效时再刷就是死循环了。
 */
async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  try {
    return (await rawRequest(path, opts)) as T
  } catch (e) {
    const is401 = e instanceof ApiError && e.httpCode === 401
    if (!is401 || opts.retrying) {
      if (is401) {
        clearTokens()
        onUnauthorized?.()
      }
      throw e
    }

    try {
      await refreshTokens()
      return (await rawRequest(path, { ...opts, retrying: true })) as T
    } catch {
      clearTokens()
      onUnauthorized?.()
      throw e
    }
  }
}

// ---------------- 接口 ----------------

export const api = {
  login: (account: string, password: string) =>
    request<TokenPair>('/api/auth/login', {
      method: 'POST',
      body: { account, password },
      skipAuth: true,
    }),

  logout: () => request<unknown>('/api/auth/logout', { method: 'POST' }),

  me: () => request<Profile>('/api/users/me'),

  // 运营看板 / 网关工作台（都按权限点 403，页面自行降级）
  adminDashboard: (days = 14) =>
    request<AdminDashboard>('/api/admin/dashboard', { query: { days } }),

  gatewayWorkbench: () => request<GatewayWorkbench>('/api/admin/gateway/workbench'),

  workbench: () => request<CrmWorkbench>('/api/crm/workbench'),

  /** 企业通讯录组织树（登录即可看；没接入的 provider configured=false） */
  corpTree: () => request<CorpDirectory>('/api/admin/corp/tree'),

  // 统一审批（花名 + 权限申请走同一张表的统一端点，两级串行）
  approvalsRequests: (status?: string, page = 1, size = 50) =>
    request<PageResult<ApprovalRequestRow>>('/api/admin/approvals/requests', {
      query: { status, page, size },
    }),

  approvalsPendingCount: () => request<{ count: number }>('/api/admin/approvals/pending-count'),

  myApprovals: (page = 1, size = 20) =>
    request<ApprovalRequestRow[]>('/api/users/me/approvals', {
      query: { page, size },
    }),

  submitNicknameRequest: (nickname: string) =>
    request<ApprovalRequestRow>('/api/users/me/approvals/nickname', {
      method: 'POST',
      body: { nickname },
    }),

  /** 提交权限申请 → 两级串行审批（团队负责人 → 该模块系统管理员）→ 批准后写直授。 */
  submitPermissionRequest: (code: string, note?: string) =>
    request<ApprovalRequestRow>('/api/users/me/approvals/permission', {
      method: 'POST',
      body: { code, note: note?.trim() || undefined },
    }),

  approveApproval: (id: number) =>
    request<ApprovalRequestRow>(`/api/admin/approvals/requests/${id}/approve`, { method: 'POST' }),

  rejectApproval: (id: number, note: string) =>
    request<ApprovalRequestRow>(`/api/admin/approvals/requests/${id}/reject`, {
      method: 'POST',
      body: { note },
    }),

  myPermissions: () => request<MyPermissions>('/api/users/permissions/my'),

  /** 权限点目录：申请权限的候选项从这儿来（登录即可见，不需要额外权限点） */
  permissionCatalog: () => request<PermissionRow[]>('/api/users/permissions/catalog'),

  // CRM
  customers: (params: { keyword?: string; status?: string; level?: string; page?: number; size?: number }) =>
    request<PageResult<CrmCustomer>>('/api/crm/customers', { query: params }),

  customer: (id: number) => request<CrmCustomer>(`/api/crm/customers/${id}`),

  /** 跟进记录是**客户维度**的（后端 follow-ups 就挂在 customer 上），商机详情里也要用它。 */
  followUps: (customerId: number) => request<CrmFollowUp[]>(`/api/crm/customers/${customerId}/follow-ups`),

  contacts: (customerId: number) => request<CrmContact[]>(`/api/crm/customers/${customerId}/contacts`),

  opportunities: (params: {
    keyword?: string
    stage?: string
    customerId?: number
    page?: number
    size?: number
  }) => request<PageResult<CrmOpportunity>>('/api/crm/opportunities', { query: params }),

  opportunity: (id: number) => request<CrmOpportunity>(`/api/crm/opportunities/${id}`),

  /**
   * 阶段流转走专用端点。
   *
   * **别用 PUT 改阶段**：PUT 是全量更新，只传 stage 会把 name/amount 抹掉，
   * 而且不会按阶段带出默认赢率（这个端点会带）。
   */
  moveStage: (id: number, stage: string) =>
    request<CrmOpportunity>(`/api/crm/opportunities/${id}/stage`, {
      method: 'PATCH',
      body: { stage },
    }),

  // ---------------- TMS / WMS / OMS（三个业务模块，全部只读） ----------------
  //
  // 这三块后端只提供 GET（写操作都在 web 管理台），所以 App 侧没有提交按钮，
  // 页面纯看板 + 列表。

  tmsDashboard: () => request<TmsDashboard>('/api/tms/dashboard'),
  tmsOrders: () => request<PageResult<TmsOrder>>('/api/tms/orders', { query: { size: 50 } }),
  tmsVehicles: () => request<PageResult<TmsVehicle>>('/api/tms/vehicles', { query: { size: 50 } }),
  tmsDrivers: () => request<PageResult<TmsDriver>>('/api/tms/drivers', { query: { size: 50 } }),

  wmsDashboard: () => request<WmsDashboard>('/api/wms/dashboard'),
  wmsWarehouses: () => request<PageResult<WmsWarehouse>>('/api/wms/warehouses', { query: { size: 50 } }),
  wmsInventory: () => request<PageResult<WmsInventory>>('/api/wms/inventory', { query: { size: 100 } }),
  wmsStockRecords: () =>
    request<PageResult<WmsStockRecord>>('/api/wms/stock-records', { query: { size: 50 } }),

  omsDashboard: () => request<OmsDashboard>('/api/oms/dashboard'),
  omsOrders: (params: { keyword?: string; status?: string; channel?: string; size?: number }) =>
    request<PageResult<OmsOrder>>('/api/oms/orders', { query: { page: 1, size: 50, ...params } }),
  omsOrderDetail: (id: number) => request<OmsOrder>(`/api/oms/orders/${id}`),
  omsProducts: (params: { keyword?: string; status?: string }) =>
    request<PageResult<OmsProduct>>('/api/oms/products', { query: { page: 1, size: 100, ...params } }),
}
