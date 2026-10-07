export interface TokenPair {
    accessToken: string
    refreshToken: string
    tokenType: string
    expiresIn: number
}

export interface Profile {
    id: number
    account: string
    nickname: string
    dbTenantId: string
    /** 租户显示名（tenants 字典）；dbTenantId 只有 id，给人看的是它 */
    tenantName: string
    gatewayRoles: string[]
    /** 权限点集合，由后端按角色矩阵解析（ADMIN 角色代码兜底为全量） */
    permissionCodes: string[]
    gatewayTenantId: string
    traceId: string
}

/** 租户字典行。id 与 users.tenant_id、JWT tid 对齐。 */
export interface TenantRow {
    id: string
    name: string
    status: number
}

/** 我的钉钉/企微组织绑定 */
export interface OrganizationMembership {
    channel: string
    externalId: string
    status: string
    detail: string
    updatedAt: string
}

export interface JoinResult {
    channel: string
    status: string
    externalId: string
    detail: string
    /** true=未配置企业凭证，仅本地记录 */
    mock: boolean
}

export interface PermissionRow {
    code: string
    name: string
    module: string
    description: string
}

export interface RoleRow {
    code: string
    name: string
    description: string
    builtin: boolean
    userCount: number
}

export interface RoleMatrix {
    permissions: PermissionRow[]
    roles: RoleRow[]
    /** 角色编码 → 已授权权限点 */
    granted: Record<string, string[]>
}

export interface RoleOverview {
    roleCount: number
    permissionCount: number
    grantCount: number
    customRoleCount: number
    moduleCount: number
}

export interface RoleMember {
    id: number
    account: string
    nickname: string
    status: number
    createdAt: string
}

export interface UserRow {
    id: number
    account: string
    nickname: string
    /** 真实姓名。注册页不采集，所以自助注册进来的行是空串 */
    realName: string
    /** 身份证脱敏值（前 6 + 8 个 * + 后 4），服务端不回完整值 */
    idCardMasked: string
    tenantId: string
    /** 租户显示名；字典缺行时服务端回退成 id */
    tenantName: string
    status: number
    roles: string[]
    createdAt: string
    lastLoginAt: string
}

/**
 * 后台建号的返回。initialPassword 是系统生成的初始密码，
 * 明文只出现在这一次响应里，刷新、重查、翻接口都拿不到第二遍。
 */
export interface CreateUserResult {
    user: UserRow
    initialPassword: string
    /** 企业通讯录（钉钉/企业微信）同步结果文案；未勾选或未配置为 null */
    corpSyncResult?: string | null
}

/**
 * 花名变更申请。花名是全局唯一身份标识，改名要走审批：
 * 提交后 status=PENDING，管理员批准才写进 users.nickname（期间 nickname 仍是旧花名）。
 */
export interface NicknameRequestRow {
    id: number
    userId: number
    /** 申请人账号 */
    account: string
    /** 申请人当前花名（审批通过前一直显示它） */
    nickname: string
    oldNickname: string
    newNickname: string
    status: 'PENDING' | 'APPROVED' | 'REJECTED'
    reviewerId: number | null
    reviewNote: string | null
    createdAt: string
    reviewedAt: string | null
}

export interface PageResult<T> {
    list: T[]
    total: number
    page: number
    size: number
}

/**
 * 团队成员。团队不参与授权本身（权限仍只勾给角色），
 * ownPermissions 是该成员由角色带来的权限，不含任何团队继承。
 */
export interface TeamMemberRow {
    userId: number
    account: string
    nickname: string
    /** 1=在职；禁用账号不给上级团队供权限 */
    status: number
    /** 是否**本团队**负责人。负责人一定是成员，且同一团队最多一个 */
    lead: boolean
    joinedAt: string
    /** 挂的角色（只读展示） */
    roles: string[]
    /** 成员自身（角色带来的）权限 */
    ownPermissions: string[]
    /**
     * 成员的有效权限 = 自身权限 ∪ 其所带队节点的**子树**并集。
     * 不带队时与 ownPermissions 相同；两者之差就是「继承来的」部分。
     */
    effectivePermissions: string[]
}

/**
 * 团队（可嵌套）。parentId=null 是根节点。
 *
 * effectivePermissions = **整棵子树**在职成员自身权限的并集，
 * 因此父团队自动涵盖下面所有小队；它也正是该团队负责人的有效权限
 * （负责人是本队成员之一，本队 ⊆ 子树）。
 * members 只列直属成员，下级团队的人不算进来。
 */
export interface TeamRow {
    id: number
    name: string
    description: string
    parentId: number | null
    createdAt: string
    members: TeamMemberRow[]
    effectivePermissions: string[]
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

export interface Dashboard {
    totals: Totals
    roleDistribution: RoleCount[]
    trend: TrendPoint[]
}

// ---------------- 网关工作台 ----------------

/**
 * 网关工作台：路由、服务注册与运行健康的只读聚合。
 * 数据由 user-center 从 Nacos 配置（路由/白名单）+ 网关内网 actuator 拼出来。
 */
export interface GatewayComponent {
    name: string
    status: string
}

export interface GatewayRoute {
    id: string
    uri: string
    order: number
    /** Path 谓词的匹配模式，多条用逗号连接；非 Path 路由为 null */
    path: string | null
    /** 谓词的可读原文，如 `Path=/api/crm/**` */
    predicates: string[]
    /** lb:// 拿到的是服务名，http:// 拿到的是主机名 */
    target: string | null
    /** 是否走注册中心负载均衡（lb://）；false 表示直连地址 */
    lb: boolean
    /** 目标在注册中心是否有健康实例；直连地址恒为 true */
    registered: boolean
    instances: number
    replenishRate: number | null
    burstCapacity: number | null
    keyResolver: string | null
}

export interface GatewayService {
    name: string
    instances: number
    registered: boolean
    endpoints: string[]
}

export interface GatewayHealth {
    reachable: boolean
    status: string
    diskFree: number | null
    diskTotal: number | null
    redisStatus: string | null
    redisVersion: string | null
    components: GatewayComponent[]
}

export interface GatewayWorkbench {
    gateway: GatewayHealth
    routes: GatewayRoute[]
    services: GatewayService[]
    whitelist: string[]
    generatedAt: number
}

export interface ApiErrorBody {
    code: string
    message: string
}

// ---------------- CRM ----------------

export interface Customer {
    id: number
    name: string
    industry: string
    level: string
    source: string
    status: string
    phone: string
    email: string
    address: string
    remark: string
    ownerId: number
    ownerName: string
    createdAt: string
    updatedAt: string
    contactCount: number
    opportunityCount: number
}

export interface CustomerPayload {
    id?: number
    name: string
    industry?: string
    level?: string
    source?: string
    status?: string
    phone?: string
    email?: string
    address?: string
    remark?: string
}

export interface Contact {
    id: number
    customerId: number
    name: string
    position: string
    phone: string
    email: string
    isPrimary: number
    createdAt: string
}

export interface ContactPayload {
    name: string
    position?: string
    phone?: string
    email?: string
    isPrimary?: boolean
}

export interface Opportunity {
    id: number
    customerId: number
    customerName: string
    name: string
    amount: number
    stage: string
    probability: number
    expectedCloseDate: string | null
    remark: string
    ownerId: number
    ownerName: string
    createdAt: string
    updatedAt: string
}

export interface OpportunityPayload {
    id?: number
    customerId: number
    name: string
    amount?: number
    stage?: string
    probability?: number
    expectedCloseDate?: string | null
    remark?: string
}

export interface FollowUp {
    id: number
    customerId: number
    customerName: string
    type: string
    content: string
    nextFollowAt: string | null
    creatorId: number
    creatorName: string
    createdAt: string
}

export interface FollowUpPayload {
    content: string
    type?: string
    nextFollowAt?: string | null
}

export interface CrmStageCount {
    stage: string
    count: number
    amount: number
}

export interface CrmNameCount {
    name: string
    count: number
}

export interface CrmRecentItem {
    name: string
    type: string
    createdAt: string
}

export interface CrmDashboard {
    customers: { total: number; monthNew: number; deal: number; following: number }
    opportunities: { active: number; amount: number; winAmount: number; stages: CrmStageCount[] }
    followUps: { total: number; overdue: number }
}

/** 个人工作台：全部为"我的"维度（owner_id/creator_id 过滤） */
export interface CrmWorkbench {
    stats: {
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
    todos: {
        id: number
        customerId: number
        customerName: string
        type: string
        content: string
        nextFollowAt: string
        due: 'OVERDUE' | 'TODAY' | 'UPCOMING'
    }[]
    opportunities: {
        id: number
        name: string
        amount: number
        stage: string
        probability: number
        expectedCloseDate: string | null
        customerName: string
    }[]
    customers: { id: number; name: string; level: string; status: string; createdAt: string }[]
    followUps: { customerName: string; type: string; content: string; createdAt: string }[]
}

// ---------------- TMS ----------------

export interface TmsOrder {
    id: number
    orderNo: string
    customerName: string
    origin: string
    destination: string
    status: string
    driverId?: number | null
    vehicleId?: number | null
    remark?: string
    distanceKm?: number | null
    cost?: number | null
    createdAt: string
}

export interface TmsVehicle {
    id: number
    plateNo: string
    type: string
    status: string
    driverName: string
    remark?: string
    costPerKm?: number | null
    dailyFixedCost?: number | null
    isExternal?: boolean
    createdAt?: string
}

export interface TmsDriver {
    id: number
    name: string
    phone: string
    status: string
}

export interface TmsStatusCount {
    status: string
    count: number
}

export interface TmsDashboard {
    orders: { total: number; pending: number; inTransit: number; delivered: number; statusDistribution: TmsStatusCount[] }
    vehicles: { total: number; idle: number; busy: number; statusDistribution: TmsStatusCount[] }
    drivers: { total: number; active: number; statusDistribution: TmsStatusCount[] }
}

/** 自有 vs 外部车辆成本对比 */
export interface TmsVehicleTypeSummary {
    count: number
    avgCostPerKm: number
    totalFixedCost: number
    totalOrderCost: number
    orderCount: number
    avgOrderCost: number
}

export interface TmsCostComparison {
    selfOwned: TmsVehicleTypeSummary
    external: TmsVehicleTypeSummary
    costPerKmDiff: number
    avgOrderCostDiff: number
}

/** 智能派车推荐 */
export interface TmsDispatchRecommendation {
    vehicleId: number
    plateNo: string
    type: string
    driverName: string
    isExternal: boolean
    costPerKm: number
    estimatedCost: number
    reason: string
}

// ---------------- WMS ----------------

export interface WmsWarehouse {
    id: number
    name: string
    location: string
    capacity: number
    status: string
    remark?: string
    /** 45 号迁移新增的国际仓属性 */
    warehouseType?: string
    country?: string
    timezone?: string
    overseaOperator?: string
    isBonded?: number
}

export interface WmsInventory {
    id: number
    warehouseName: string
    productName: string
    sku: string
    quantity: number
}

export interface WmsStockRecord {
    id: number
    warehouseName: string
    productName: string
    type: string
    quantity: number
    /** 关联的 OMS 订单号（OMS 发货联动自动写入，手工记录为空） */
    orderNo?: string | null
    createdAt: string
}

export interface WmsDashboard {
    inventory: { totalProducts: number; totalQuantity: number; lowStock: number }
    stockRecords: { totalIn: number; totalOut: number; todayIn: number; todayOut: number }
}

// ---------------- OMS ----------------

/** 服装商品款号：颜色/尺码组合成 SKU，按类目与季节归档 */
export interface OmsProduct {
    id: number
    /** 款号，如 SH202601 */
    styleNo: string
    name: string
    /** TOP上衣/PANTS裤装/DRESS裙装/OUTER外套/KNIT针织 */
    category: string
    season?: string
    color?: string
    size?: string
    sku?: string
    /** 吊牌价（元） */
    price?: number | null
    /** ON上架/OFF下架 */
    status: string
    remark?: string
    createdAt?: string
}

/** 订单明细行：提交时由商品下拉组装，小计=quantity*price */
export interface OmsOrderItem {
    id?: number
    productId: number
    styleNo: string
    productName: string
    color?: string
    size?: string
    quantity: number
    price: number
}

/** 多渠道销售订单（抖音/淘宝/1688批发/线下/微信） */
export interface OmsOrder {
    id: number
    orderNo: string
    channel: string
    customerName: string
    phone?: string
    address?: string
    status: string
    /** 后端按明细自动汇总 */
    totalQty: number
    totalAmount: number
    remark?: string
    createdAt: string
    updatedAt?: string
    /** 关联 TMS 运单状态（PENDING/IN_TRANSIT/DELIVERED/CANCELLED），后端按订单号聚合，无则 null */
    tmsStatus?: string | null
    /** 运单起点（发货仓位置） */
    tmsOrigin?: string | null
    /** 运单终点（收货地址） */
    tmsDestination?: string | null
    /** WMS 已出库件数（OUT 记录合计），无出库记录则 null */
    outQty?: number | null
    /** 仅 GET /oms/orders/{id} 返回 */
    items?: OmsOrderItem[]
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

/** 热销款排名 */
export interface OmsTopStyle {
    styleNo: string
    name: string
    qty: number
    amount: number
}

export interface OmsDashboard {
    orders: {
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
    sales: {
        totalAmount: number
        todayAmount: number
        monthAmount: number
        totalQty: number
        todayQty: number
        todayOrderCount: number
    }
    topStyles: OmsTopStyle[]
}

/* =============================================================================
 * 国际跨境物流（M1 纵向切片）
 *
 * 时间口径：字段名以 At 结尾的都是 **UTC** 时刻（后端 LocalDateTime，无时区后缀），
 * 展示必须走 intl/shared.tsx 的 formatUtc()（补 Z 再按 Asia/Shanghai 转）；
 * 以 Date 结尾的是「当地日期」（ETD 当地是 10-14 就直接是 2026-10-14，不换算）。
 * 前端不允许直接 new Date(x).toLocaleString()——那会按浏览器本地时区解析，
 * 开发机（UTC+8）看着对，换一台机器就差 8 小时。
 * ========================================================================== */

export interface IntlCarrier {
    id: number
    code: string
    nameZh: string
    nameEn?: string
    carrierType: string
    country?: string
    contactName?: string
    contactPhone?: string
    contactEmail?: string
    serviceLevel?: string
    transitDays?: number
    cutOffHours?: number
    freeTimeDays?: number
    status: string
    remark?: string
}

export interface IntlRoute {
    id: number
    routeCode: string
    carrierId: number
    /** LEFT JOIN 出来的展示列；承运商不可见时为 null → 显示「—」 */
    carrierName?: string | null
    mode: string
    polCode: string
    podCode: string
    transitDays?: number
    volumetricDivisor?: number
    viaPorts?: string
    status: string
}

export interface IntlSailing {
    id: number
    routeId: number
    routeCode?: string | null
    polCode?: string | null
    podCode?: string | null
    vesselName?: string
    voyageNo?: string
    etdAt: string
    etaAt: string
    cutoffAt?: string | null
    freeTimeDays?: number
    spaceLeft?: number
    status: string
}

export interface IntlShipmentItem {
    id: number
    shipmentId: number
    shipmentNo: string
    orderNo?: string
    houseNo?: string
    containerNo?: string
    containerType?: string
    sealNo?: string
    marks?: string
    pieces?: number
    grossWeight?: number
    volume?: number
    volumetricWeight?: number
    isDangerous?: number
    unNumber?: string
    dgClass?: string
}

export interface IntlTrackingNode {
    id: number
    shipmentNo: string
    nodeCode: string
    nodeName?: string
    nodeTime: string
    location?: string
    /** MANUAL 人工 / CARRIER_CALLBACK 承运商回调 / SCHEDULED 定时任务 */
    source: string
    operator?: string
    remark?: string
}

export interface IntlShipment {
    id: number
    shipmentNo: string
    batchNo?: string
    awbNo?: string
    blNo?: string
    /** BL 类型：MASTER 主单（船司签发）/ HOUSE 分单（货代签发）。P1-4 */
    blType?: string
    /** BL 签发方（船司代码或货代 partner_code）。P1-4 */
    blIssuer?: string
    /** 集装箱自重（吨）。SOLAS VGM Method 2 = Σ(货物+包装+绑扎) + 此值。P1-2 */
    containerTareWeight?: number
    bookingNo?: string
    sailingId?: number
    mode: string
    carrierId: number
    carrierName?: string | null
    polCode?: string
    podCode?: string
    containerType?: string
    containerQty?: number
    incoterm?: string
    currency?: string
    totalPieces?: number
    totalGrossWeight?: number
    totalVolume?: number
    volumetricWeight?: number
    chargeableWeight?: number
    vgmWeight?: number
    marks?: string
    etdAt?: string | null
    etaAt?: string | null
    cutoffAt?: string | null
    actualEtdAt?: string | null
    actualEtaAt?: string | null
    podReceivedAt?: string | null
    isDangerous?: number
    unNumber?: string
    dgClass?: string
    sanctionFlag?: string
    iossNo?: string
    status: string
    exceptionReason?: string
    remark?: string
    createdAt?: string
}

export interface IntlCustomsDeclaration {
    id: number
    declarationNo: string
    shipmentId: number
    shipmentNo: string
    batchNo?: string
    customsBroker?: string
    brokerContact?: string
    hsCodeSummary?: string
    declaredValue?: number
    currency?: string
    dutyAmount?: number
    vatAmount?: number
    consumptionTax?: number
    depositAmount?: number
    status: string
    filedAt?: string | null
    releasedAt?: string | null
    remark?: string
}

export interface IntlShipmentDetail {
    shipment: IntlShipment
    items: IntlShipmentItem[]
    trackingNodes: IntlTrackingNode[]
    declaration: IntlCustomsDeclaration | null
}

export interface IntlTmsDashboard {
    shipment: {
        total: number
        draft: number
        booking: number
        booked: number
        pickedUp: number
        declared: number
        departed: number
        inTransit: number
        arrived: number
        clearing: number
        cleared: number
        lastMile: number
        delivered: number
        exception: number
        statusDistribution: { status: string; count: number }[]
    }
    etd: { todayDepart: number; next7Days: number; overdue: number }
    containers: Record<string, number>
    weight: { totalGross: number; totalVolume: number; totalChargeable: number }
}

export interface IntlExportOrderItem {
    id: number
    orderId: number
    orderNo: string
    lineNo: number
    productId?: number
    sku?: string
    productName?: string
    customsName?: string
    customsNameEn?: string
    hsCode?: string
    originCountry?: string
    quantity: number
    unitPrice?: number
    declaredValue?: number
    netWeight?: number
    grossWeight?: number
    volume?: number
    isDangerous?: number
    unNumber?: string
    dgClass?: string
    packingInstruction?: string
    batteryWattHours?: number
    remark?: string
}

/** TMS 运单的列表快照（由 OMS 跨库聚合回填）。 */
export interface IntlShipmentBrief {
    id: number
    shipmentNo: string
    batchNo?: string
    awbNo?: string
    blNo?: string
    bookingNo?: string
    mode?: string
    status?: string
    etdAt?: string | null
    etaAt?: string | null
}

/** WMS 备货任务的列表快照（由 OMS 跨库聚合回填）。 */
export interface IntlPackTaskBrief {
    id: number
    taskNo: string
    orderNo: string
    boxNo?: string
    status?: string
}

export interface IntlExportOrder {
    id: number
    orderNo: string
    batchNo?: string
    isBatchLocked?: number
    customerId: number
    customerName?: string
    consigneeName: string
    consigneeCompany?: string
    consigneeCountry?: string
    consigneeAddress?: string
    consigneePhone?: string
    consigneeEmail?: string
    incoterm?: string
    currency?: string
    totalAmount?: number
    freightAmount?: number
    totalQty?: number
    totalWeight?: number
    totalVolume?: number
    volumetricWeight?: number
    etdDate?: string | null
    etaDate?: string | null
    readyDate?: string | null
    status: string
    remark?: string
    createdAt?: string
    /* ---- 以下为跨库聚合列，下游不可用时为 null → 显示「—」 ---- */
    batchStatus?: string | null
    shipmentNo?: string | null
    shipmentStatus?: string | null
    awbNo?: string | null
    bookingNo?: string | null
    etdAt?: string | null
    etaAt?: string | null
    packStatus?: string | null
    boxNo?: string | null
}

export interface IntlExportOrderDetail {
    order: IntlExportOrder
    items: IntlExportOrderItem[]
    batch: IntlExportBatch | null
    shipment: IntlShipmentBrief | null
    packTask: IntlPackTaskBrief | null
}

export interface IntlExportBatch {
    id: number
    batchNo: string
    mode: string
    polCode?: string
    polName?: string
    podCode?: string
    podName?: string
    routeId?: number
    carrierId?: number
    containerType?: string
    containerQty?: number
    incoterm?: string
    currency?: string
    totalQty?: number
    totalWeight?: number
    totalVolume?: number
    volumetricWeight?: number
    etdDate?: string | null
    etaDate?: string | null
    cutoffAt?: string | null
    status: string
    remark?: string
    /* ---- 跨库聚合列 ---- */
    shipmentNo?: string | null
    shipmentStatus?: string | null
}

export interface IntlExportBatchDetail {
    batch: IntlExportBatch
    orders: IntlExportOrder[]
    shipment: IntlShipmentBrief | null
}

export interface IntlOmsDashboard {
    order: {
        total: number
        draft: number
        confirmed: number
        picking: number
        packed: number
        declared: number
        shipped: number
        inTransit: number
        arrived: number
        clearing: number
        cleared: number
        lastMile: number
        delivered: number
        exception: number
        statusDistribution: { status: string; count: number }[]
        topCountries: { country: string; count: number }[]
    }
    /** 只有 USD 的口径；其它币种见 monthAmountByCurrency（禁止跨币种相加） */
    monthAmountUsd: number
    monthQty: number
    inTransit: number
    exceptionCount: number
    monthAmountByCurrency: { currency: string; amount: number }[]
}

export interface IntlPackTask {
    id: number
    taskNo: string
    orderNo: string
    warehouseId: number
    warehouseName?: string | null
    locationCode?: string
    taskType: string
    status: string
    boxNo?: string
    containerType?: string
    pieces?: number
    grossWeight?: number
    volume?: number
    volumetricWeight?: number
    marks?: string
    isDangerous?: number
    labelPrintedAt?: string | null
    handedOverAt?: string | null
    assigneeId?: number
    remark?: string
}

export interface IntlPackTaskItem {
    id: number
    taskId: number
    sku: string
    productName?: string
    batchNo?: string
    productionDate?: string | null
    expiryDate?: string | null
    quantity: number
    pickedQuantity: number
    hsCode?: string
}

export interface IntlPackTaskDetail {
    task: IntlPackTask
    items: IntlPackTaskItem[]
    /** FEFO 推荐批次（只推荐不强制，页面上一键回填） */
    batchCandidates: IntlInventoryBatch[]
}

export interface IntlInventoryBatch {
    id: number
    warehouseId: number
    warehouseName?: string | null
    sku: string
    productName?: string
    batchNo: string
    productionDate?: string | null
    expiryDate?: string | null
    quantity: number
    reservedQty: number
    /** 可用量 = 在库 − 占用，服务端 SELECT 时现算，不落库 */
    availableQty: number
    unitCost?: number
    locationCode?: string
    status: string
}

export interface IntlInTransit {
    id: number
    shipmentNo: string
    orderNo?: string
    sku?: string
    productName?: string
    batchNo?: string
    quantity: number
    fromWarehouseId?: number
    fromWarehouseName?: string | null
    toWarehouseId?: number
    toWarehouseName?: string | null
    shippedAt: string
    arrivedAt?: string | null
    status: string
}

export interface IntlWmsDashboard {
    packTask: {
        total: number
        pending: number
        picking: number
        packed: number
        labelled: number
        handedOver: number
        shortage: number
        statusDistribution: { status: string; count: number }[]
    }
    batch: { total: number; nearExpiring: number; expired: number; frozen: number }
    inTransit: {
        total: number
        inTransit: number
        arrived: number
        lost: number
        byWarehouse: { warehouseName: string; qty: number }[]
    }
    warehouse: { total: number; oversea: number; bonded: number; domestic: number }
}

export interface IntlPartner {
    id: number
    partnerCode: string
    partnerName: string
    partnerType: string
    contactName?: string
    contactPhone?: string
    contactEmail?: string
    country?: string
    currency?: string
    paymentTermDays?: number
    creditLimit?: number
    creditUsed?: number
    rating?: number
    status: string
    remark?: string
}

export interface IntlTicket {
    id: number
    ticketNo: string
    customerId?: number
    customerName?: string
    orderNo?: string
    shipmentNo?: string
    category: string
    severity: string
    status: string
    title: string
    detail?: string
    resolution?: string
    ownerId?: number
    ownerName?: string
    dueAt?: string | null
    /** 服务端 SELECT 时现算：SLA 到了且未解决 */
    overdue?: boolean
    resolvedAt?: string | null
    createdAt?: string
}

export interface IntlCustomer extends Customer {
    customerType?: string
    country?: string
    taxNo?: string
    paymentTermDays?: number
    creditLimit?: number
    creditUsed?: number
    defaultCurrency?: string
    iossNo?: string
}

export interface IntlCrmDashboard {
    customerTotal: number
    overseaCustomer: number
    partnerTotal: number
    ticketOpen: number
    ticketOverdue: number
    ticketDistribution: { status: string; count: number }[]
    ticketByCategory: { category: string; count: number }[]
    /** 授信按币种分组，禁止跨币种相加 */
    creditUsedByCurrency: { currency: string; amount: number }[]
    creditLimitByCurrency: { currency: string; amount: number }[]
    partnerByType: { partnerType: string; count: number }[]
}

export interface PluginMenu {
    path: string
    label: string
    /** 该菜单要有人能看见，必须持有的权限点 */
    permission: string
    metaTitle: string
    metaSub: string
}

export interface PluginManifest {
    serviceCode: string
    serviceName: string
    group: { key: string; label: string; icon: string }
    menus: PluginMenu[]
}

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

/** 企业通讯录的一个 provider（钉钉 / 企业微信），未配置时 depts 为空数组 */
export interface CorpProvider {
    code: 'dingding' | 'wecom'
    name: string
    configured: boolean
    depts: CorpDept[]
}

export interface CorpDirectory {
    providers: CorpProvider[]
}

/** 集成配置（钉钉/企业微信凭据）——GET /api/admin/integrations，role:manage 可见 */
export interface IntegrationProvider {
    code: string
    name: string
    configured: boolean
    /** db=页面配置（数据库） none=未配置（凭据只走本页，没有 env 口径） */
    source: 'db' | 'none'
    keyLabel: string
    secretLabel: string
    maskedKey: string
    maskedSecret: string
}

export interface IntegrationTestResult {
    ok: boolean
    message: string
}
