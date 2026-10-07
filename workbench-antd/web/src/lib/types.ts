/**
 * 接口 DTO。
 *
 * 字段名/类型与后端**线上实测**一致（对齐 `workbench-android` 的 `Model.kt`，
 * 那边的注释标了每个字段是从哪个响应确认的）。三条实测结论在这里沿用：
 *  - 时间格式两个服务不统一：`crm` 侧 ISO `"2026-09-29T20:53:32"`，`user-center`
 *    侧空格分隔 `"2026-10-01 00:24:42"`，展示前统一走 `lib/format.ts` 收敛
 *  - 金额是 `BigDecimal` 序列化的 `60000.00`，用 number 收
 *  - `expectedCloseDate` 是 LocalDate（`"2026-10-31"`），可为 null
 *
 * 所有字段都给默认值：后端加字段不该让页面崩，缺字段按空数组/0 处理，
 * 页面上表现为「这一块暂时没有数据」。
 */

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface TokenPair {
  accessToken: string
  refreshToken: string
  tokenType?: string
  expiresIn?: number
}

export interface Profile {
  id: number
  account: string
  nickname: string
  dbTenantId: string
  tenantName?: string
  gatewayRoles: string[]
  permissionCodes: string[]
  gatewayTenantId?: string
  traceId?: string
}

// ---------------- 个人工作台（CRM）----------------

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

/** 待办。`due` 由后端算好：OVERDUE 已过点 / TODAY 今天 / UPCOMING 未来 7 天。 */
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
  expectedCloseDate?: string | null
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

// ---------------- CRM 客户 / 商机 / 跟进 ----------------

/** status: POTENTIAL 潜在 / FOLLOWING 跟进中 / DEAL 成交 / LOST 流失；level: KEY / NORMAL / LOW。 */
export interface CrmCustomer {
  id: number
  name: string
  industry?: string | null
  level?: string | null
  source?: string | null
  status: string
  phone?: string | null
  email?: string | null
  address?: string | null
  remark?: string | null
  ownerId?: number | null
  ownerName?: string | null
  createdAt: string
  updatedAt?: string | null
  contactCount: number
  opportunityCount: number
}

/** stage: LEAD / PROPOSAL / NEGOTIATION / WON / LOST */
export interface CrmOpportunity {
  id: number
  customerId: number
  customerName: string
  name: string
  amount?: number | null
  stage: string
  probability?: number | null
  expectedCloseDate?: string | null
  remark?: string | null
  ownerId?: number | null
  ownerName?: string | null
  createdAt: string
  updatedAt?: string | null
}

/** 跟进记录。type: CALL / VISIT / WECHAT / MAIL / OTHER */
export interface CrmFollowUp {
  id: number
  customerId: number
  customerName: string
  type: string
  content: string
  nextFollowAt?: string | null
  creatorId?: number | null
  creatorName?: string | null
  createdAt: string
}

export interface CrmContact {
  id: number
  customerId: number
  name: string
  position?: string | null
  phone?: string | null
  email?: string | null
  isPrimary?: number | null
  createdAt: string
}

// ---------------- 审批中心 ----------------

export interface NicknameRequestRow {
  id: number
  userId: number
  account: string
  nickname: string
  oldNickname: string
  newNickname: string
  status: string
  reviewerId?: number | null
  reviewNote?: string | null
  createdAt: string
  reviewedAt?: string | null
}

/**
 * 权限申请单（两级串行审批：1 组织上一级 → 2 系统管理员）。
 * **谁能看/谁能批由后端按查看者算好**（`canAct`），前端只按它渲染按钮，不自算权限。
 */
export interface PermissionRequestRow {
  id: number
  userId: number
  account: string
  nickname: string
  permissionCode: string
  permissionName: string
  module: string
  note?: string | null
  status: string
  reviewNote?: string | null
  reviewerId?: number | null
  createdAt: string
  reviewedAt?: string | null
  stage: number
  leadReviewerId?: number | null
  leadReviewedAt?: string | null
  leadNames: string
  sysAdminNames: string
  canAct: boolean
}

/** 权限点目录的一行（`GET /api/users/permissions/catalog`，登录即可见） */
export interface ApprovalRequestRow {
  id: number
  userId: number
  account: string
  nickname: string
  type: 'NICKNAME' | 'PERMISSION'
  prevValue: string
  target: string
  module: string
  note: string
  status: string
  stage: number
  leadReviewerId: number | null
  leadReviewedAt: string | null
  reviewerId: number | null
  reviewNote: string | null
  reviewedAt: string | null
  createdAt: string
  leadNames: string
  sysAdminNames: string
  canAct: boolean
}

export interface PermissionRow {
  code: string
  name: string
  module: string
  description?: string
}

export interface MyPermissions {
  effective: string[]
  grants: string[]
  revokes: string[]
  /** 我的申请单，字段与审批视角同构（canAct 对自己恒 false） */
  requests: PermissionRequestRow[]
}

// ---------------- 网关工作台 / 运营看板 ----------------

export interface GatewayComponent {
  name: string
  status: string
}

export interface GatewayHealth {
  reachable: boolean
  status: string
  diskFree?: number | null
  diskTotal?: number | null
  redisStatus?: string | null
  redisVersion?: string | null
  components: GatewayComponent[]
}

export interface GatewayRoute {
  id: string
  uri: string
  order: number
  path?: string | null
  predicates: string[]
  target?: string | null
  lb: boolean
  registered: boolean
  instances: number
  replenishRate?: number | null
  burstCapacity?: number | null
  keyResolver?: string | null
}

export interface GatewayService {
  name: string
  instances: number
  registered: boolean
  endpoints: string[]
}

export interface GatewayWorkbench {
  gateway: GatewayHealth
  routes: GatewayRoute[]
  services: GatewayService[]
  whitelist: string[]
  generatedAt: number
}

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

export interface AdminDashboard {
  totals: Totals
  roleDistribution: RoleCount[]
  trend: TrendPoint[]
}
// ---------------- TMS（运输管理）----------------

/**
 * 运输看板（`GET /api/tms/dashboard`）：orders / vehicles / drivers 三段嵌套。
 * 各段自带 statusDistribution，页面直接用上面的汇总字段就够，不消费分布。
 */
export interface TmsDashboard {
  orders: { total: number; pending: number; inTransit: number; delivered: number }
  vehicles: { total: number; idle: number; busy: number }
  drivers: { total: number; active: number }
}

/** 运单 status：PENDING / IN_TRANSIT / DELIVERED / CANCELLED */
export interface TmsOrder {
  id: number
  orderNo: string
  customerName: string
  origin: string
  destination: string
  status: string
  createdAt: string
}

/** 车辆 status：IDLE 空闲 / BUSY 运输中 / MAINTENANCE 维修中 */
export interface TmsVehicle {
  id: number
  plateNo: string
  vehicleType: string
  status: string
  driverName: string
}

/** 司机 status：ACTIVE 在职 / INACTIVE 停用 */
export interface TmsDriver {
  id: number
  name: string
  phone: string
  status: string
}

// ---------------- WMS（仓储管理）----------------

/**
 * 仓储看板（`GET /api/wms/dashboard`）：只有 stockRecords + inventory 两段，
 * **仓库数不在看板里**（页面用仓库列表长度补位）——照线上真实响应写，别加字段。
 */
export interface WmsDashboard {
  stockRecords: { todayIn: number; todayOut: number; totalIn: number; totalOut: number }
  inventory: { totalQuantity: number; totalProducts: number; lowStock: number }
}

export interface WmsWarehouse {
  id: number
  name: string
  location: string
  capacity: number
  status: string
}

export interface WmsInventory {
  id: number
  warehouseName: string
  productName: string
  sku: string
  quantity: number
}

/** 出入库记录 type：IN 入库 / OUT 出库；orderNo 是 OMS 发货联动写入的关联单号 */
export interface WmsStockRecord {
  id: number
  warehouseName: string
  productName: string
  type: string
  quantity: number
  orderNo: string
  createdAt: string
}

// ---------------- OMS（订单管理 · 服装行业）----------------

/**
 * 销售订单（`GET /api/oms/orders`）。
 * channel：DOUYIN/TAOBAO/P1688/OFFLINE/WECHAT；
 * status：UNPAID/TO_SHIP/SHIPPED/DONE/AFTER_SALE/CANCELLED。
 * `items` 只有详情接口带，列表接口不返回（所以可空）。
 */
export interface OmsOrder {
  id: number
  orderNo: string
  channel: string
  customerName: string
  phone: string
  address: string
  status: string
  totalQty: number
  totalAmount: number
  remark: string
  createdAt: string
  updatedAt: string
  /** 关联 TMS 运单状态，无运单则 null */
  tmsStatus?: string | null
  tmsOrigin?: string | null
  tmsDestination?: string | null
  /** WMS 已出库件数，无出库记录则 null */
  outQty?: number | null
  items?: OmsOrderItem[] | null
}

/** 订单明细：一行 = 一个款号 + 颜色 + 尺码的成交记录 */
export interface OmsOrderItem {
  styleNo: string
  productName: string
  color: string
  size: string
  quantity: number
  price: number
}

/** 商品 SKU：category TOP/PANTS/DRESS/OUTER/KNIT；status ON 上架 / OFF 下架 */
export interface OmsProduct {
  id: number
  styleNo: string
  name: string
  category: string
  season: string
  color: string
  size: string
  sku: string
  price: number
  status: string
}

export interface OmsStatusCount {
  status: string
  count: number
}

export interface OmsChannelCount {
  channel: string
  count: number
  amount: number
}

/** 热销款排名（按销量倒序，后端算好） */
export interface OmsTopStyle {
  styleNo: string
  name: string
  qty: number
  amount: number
}

export interface OmsOrderStats {
  total: number
  unpaid: number
  toShip: number
  shipped: number
  done: number
  afterSale: number
  cancelled: number
  statusDistribution: OmsStatusCount[]
  channelDistribution: OmsChannelCount[]
}

export interface OmsSalesStats {
  totalAmount: number
  todayAmount: number
  monthAmount: number
  totalQty: number
  todayQty: number
  todayOrderCount: number
}

export interface OmsDashboard {
  orders: OmsOrderStats
  sales: OmsSalesStats
  topStyles: OmsTopStyle[]
}

/** 企业通讯录（钉钉/企业微信）——与 web 管理台同构，由 /api/admin/corp/tree 下发 */
export interface CorpMember {
  userid: string
  name: string
  title: string
}

export interface CorpDept {
  id: number
  name: string
  children: CorpDept[]
  members: CorpMember[]
}

export interface CorpProvider {
  code: string
  name: string
  /** false = 该平台没配凭据，端上只提示去管理台「集成配置」页接入，不发请求 */
  configured: boolean
  depts: CorpDept[]
}

export interface CorpDirectory {
  providers: CorpProvider[]
}
