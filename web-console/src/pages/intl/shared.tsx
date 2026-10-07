import { Tag } from 'antd'
import type { ReactNode } from 'react'

/**
 * 国际跨境物流的枚举中文/配色映射与格式化工具。
 *
 * 全站**只此一份**（与 tms/shared.tsx、oms/shared.tsx 的分工一样），
 * 否则同一个 EXPORT_DECLARED 在两个页面上会显示两种颜色。
 *
 * 为什么不复用 tms/shared.tsx 的 ORDER_STATUS：那是国内陆运的 4 态
 * （待发车/运输中/已送达），与国际运单的 16 态没有交集，复用会逼着前端
 * 用 if 分支判断「哪个字典里有这个码」——那正是要消除的东西。
 */

export interface StatusMeta {
    label: string
    color: string
}

export const EXPORT_MODE: Record<string, StatusMeta> = {
    SEA: { label: '海运整柜', color: 'blue' },
    LCL: { label: '海运拼箱', color: 'cyan' },
    AIR: { label: '空运', color: 'geekblue' },
    EXPRESS: { label: '国际快递', color: 'purple' },
    RAIL: { label: '中欧班列', color: 'orange' },
    TRUCK: { label: '卡航', color: 'gold' }
}

export const EXPORT_ORDER_STATUS: Record<string, StatusMeta> = {
    DRAFT: { label: '草稿', color: 'default' },
    CONFIRMED: { label: '已确认', color: 'default' },
    PICKING: { label: '备货中', color: 'processing' },
    PACKED: { label: '已装箱', color: 'cyan' },
    EXPORT_DECLARED: { label: '已报关', color: 'geekblue' },
    SHIPPED: { label: '已发运', color: 'blue' },
    IN_TRANSIT: { label: '在途', color: 'processing' },
    ARRIVED: { label: '已到港', color: 'lime' },
    CUSTOMS_CLEARING: { label: '清关中', color: 'gold' },
    CUSTOMS_CLEARED: { label: '已清关', color: 'green' },
    LAST_MILE: { label: '尾程派送', color: 'cyan' },
    DELIVERED: { label: '已妥投', color: 'success' },
    CLOSED: { label: '已完结', color: 'default' },
    EXCEPTION: { label: '异常', color: 'error' },
    CANCELLED: { label: '已取消', color: 'default' }
}

export const EXPORT_BATCH_STATUS: Record<string, StatusMeta> = {
    DRAFT: { label: '草稿', color: 'default' },
    CONFIRMED: { label: '已确认', color: 'default' },
    // LCL 拼箱常态：已组批但子单还在仓库备货，凑不齐货不能订舱
    WAITING_GOODS: { label: '等货备货', color: 'gold' },
    BOOKING: { label: '订舱中', color: 'processing' },
    BOOKED: { label: '已订舱', color: 'cyan' },
    LOADING: { label: '装柜中', color: 'geekblue' },
    DEPARTED: { label: '已开航', color: 'processing' },
    IN_TRANSIT: { label: '在途', color: 'processing' },
    ARRIVED: { label: '已到港', color: 'lime' },
    CLEARING: { label: '清关中', color: 'gold' },
    CLEARED: { label: '已清关', color: 'green' },
    LAST_MILE: { label: '尾程派送', color: 'cyan' },
    DELIVERED: { label: '已妥投', color: 'success' },
    CLOSED: { label: '已完结', color: 'default' },
    EXCEPTION: { label: '异常', color: 'error' },
    CANCELLED: { label: '已取消', color: 'default' }
}

export const SHIPMENT_STATUS: Record<string, StatusMeta> = {
    DRAFT: { label: '草稿', color: 'default' },
    BOOKING: { label: '订舱中', color: 'processing' },
    BOOKED: { label: '已订舱', color: 'cyan' },
    PICKED_UP: { label: '已揽收', color: 'blue' },
    EXPORT_DECLARED: { label: '已报关', color: 'geekblue' },
    DEPARTED: { label: '已开航', color: 'processing' },
    IN_TRANSIT: { label: '在途', color: 'processing' },
    ARRIVED: { label: '已到港', color: 'lime' },
    CLEARING: { label: '清关中', color: 'gold' },
    CLEARED: { label: '已清关', color: 'green' },
    LAST_MILE: { label: '尾程派送', color: 'cyan' },
    DELIVERED: { label: '已妥投', color: 'success' },
    POD_CONFIRMED: { label: '签收已回传', color: 'success' },
    EXCEPTION: { label: '异常', color: 'error' },
    RETURNED: { label: '已退运', color: 'volcano' },
    CANCELLED: { label: '已取消', color: 'default' }
}

export const PACK_TASK_STATUS: Record<string, StatusMeta> = {
    PENDING: { label: '待处理', color: 'default' },
    PICKING: { label: '拣货中', color: 'processing' },
    PACKED: { label: '已装箱', color: 'cyan' },
    LABELLED: { label: '已贴标', color: 'geekblue' },
    HANDED_OVER: { label: '已交接', color: 'success' },
    SHORTAGE: { label: '缺料', color: 'error' },
    CANCELLED: { label: '已取消', color: 'default' }
}

export const CARRIER_TYPE: Record<string, StatusMeta> = {
    OCEAN: { label: '海运船司', color: 'blue' },
    AIR: { label: '空运航司', color: 'geekblue' },
    EXPRESS: { label: '快递', color: 'purple' },
    NVOCC: { label: '无船承运人', color: 'cyan' },
    FORWARDER: { label: '货代', color: 'orange' },
    AGENT: { label: '货代代理', color: 'gold' },
    CLEARANCE: { label: '清关服务商', color: 'green' },
    LAST_MILE: { label: '尾程派送', color: 'magenta' }
}

export const PARTNER_TYPE: Record<string, StatusMeta> = {
    FORWARDER: { label: '货代', color: 'orange' },
    NVOCC: { label: '无船承运人', color: 'cyan' },
    AGENT: { label: '货代代理', color: 'gold' },
    CLEARANCE_BROKER: { label: '报关行', color: 'green' },
    LAST_MILE: { label: '尾程派送', color: 'magenta' },
    TRUCKING: { label: '卡航', color: 'blue' }
}

export const DECLARATION_STATUS: Record<string, StatusMeta> = {
    DRAFT: { label: '草稿', color: 'default' },
    FILED: { label: '已申报', color: 'processing' },
    RELEASED: { label: '已放行', color: 'success' },
    INSPECTING: { label: '查验中', color: 'warning' },
    HELD: { label: '扣货', color: 'error' },
    REJECTED: { label: '已退单', color: 'error' }
}

export const TICKET_STATUS: Record<string, StatusMeta> = {
    OPEN: { label: '待处理', color: 'error' },
    FOLLOWING: { label: '跟进中', color: 'processing' },
    RESOLVED: { label: '已解决', color: 'success' },
    CLOSED: { label: '已关闭', color: 'default' }
}

export const TICKET_CATEGORY: Record<string, StatusMeta> = {
    DELAY: { label: '延误', color: 'orange' },
    DAMAGE: { label: '破损', color: 'volcano' },
    CUSTOMS_HOLD: { label: '清关查验', color: 'gold' },
    AMEND: { label: '改单', color: 'geekblue' },
    ABANDON: { label: '弃货', color: 'red' },
    OTHER: { label: '其他', color: 'default' }
}

export const TICKET_SEVERITY: Record<string, StatusMeta> = {
    LOW: { label: '低', color: 'default' },
    MEDIUM: { label: '中', color: 'blue' },
    HIGH: { label: '高', color: 'red' }
}

export const IN_TRANSIT_STATUS: Record<string, StatusMeta> = {
    IN_TRANSIT: { label: '在途', color: 'processing' },
    ARRIVED: { label: '已到仓', color: 'cyan' },
    RECEIVED: { label: '已入库', color: 'success' },
    LOST: { label: '丢件', color: 'error' }
}

export const BATCH_STATUS: Record<string, StatusMeta> = {
    NORMAL: { label: '正常', color: 'success' },
    NEAR_EXPIRY: { label: '临期', color: 'warning' },
    EXPIRED: { label: '已过期', color: 'error' },
    FROZEN: { label: '冻结', color: 'default' }
}

export const WAREHOUSE_TYPE: Record<string, StatusMeta> = {
    DOMESTIC: { label: '境内仓', color: 'blue' },
    OVERSEAS: { label: '海外仓', color: 'purple' },
    BONDED: { label: '保税仓', color: 'gold' },
    TRANSIT: { label: '中转仓', color: 'cyan' }
}

export const CUSTOMER_TYPE: Record<string, StatusMeta> = {
    SELLER: { label: '卖家', color: 'blue' },
    FORWARDER: { label: '货代', color: 'orange' },
    FACTORY: { label: '工厂', color: 'cyan' },
    DISTRIBUTOR: { label: '经销', color: 'purple' }
}

export const TRACKING_SOURCE: Record<string, StatusMeta> = {
    MANUAL: { label: '人工', color: 'blue' },
    CARRIER_CALLBACK: { label: '承运商回调', color: 'green' },
    SCHEDULED: { label: '系统定时', color: 'purple' }
}

export const INCOTERMS = [
    'EXW',
    'FCA',
    'FOB',
    'CFR',
    'CIF',
    'CPT',
    'CIP',
    'DAP',
    'DPU',
    'DDP'
]

export const CURRENCIES = ['USD', 'CNY', 'EUR', 'GBP', 'JPY']

export const COUNTRY_OPTIONS = [
    { value: 'US', label: '美国 US' },
    { value: 'DE', label: '德国 DE' },
    { value: 'NL', label: '荷兰 NL' },
    { value: 'GB', label: '英国 GB' },
    { value: 'JP', label: '日本 JP' },
    { value: 'AU', label: '澳大利亚 AU' },
    { value: 'CA', label: '加拿大 CA' },
    { value: 'FR', label: '法国 FR' },
    { value: 'CN', label: '中国 CN' }
]

/* ---------------- 通用取字典的小工具 ---------------- */

function meta(dict: Record<string, StatusMeta>, code?: string | null): StatusMeta {
    return (code && dict[code]) || { label: code || '—', color: 'default' }
}

export function modeMeta(code?: string | null) {
    return meta(EXPORT_MODE, code)
}

export function exportOrderStatusMeta(code?: string | null) {
    return meta(EXPORT_ORDER_STATUS, code)
}

export function exportBatchStatusMeta(code?: string | null) {
    return meta(EXPORT_BATCH_STATUS, code)
}

export function shipmentStatusMeta(code?: string | null) {
    return meta(SHIPMENT_STATUS, code)
}

export function packTaskStatusMeta(code?: string | null) {
    return meta(PACK_TASK_STATUS, code)
}

export function declarationStatusMeta(code?: string | null) {
    return meta(DECLARATION_STATUS, code)
}

export function ticketStatusMeta(code?: string | null) {
    return meta(TICKET_STATUS, code)
}

export function ticketCategoryMeta(code?: string | null) {
    return meta(TICKET_CATEGORY, code)
}

export function warehouseTypeMeta(code?: string | null) {
    return meta(WAREHOUSE_TYPE, code)
}

export function customerTypeMeta(code?: string | null) {
    return meta(CUSTOMER_TYPE, code)
}

export function partnerTypeMeta(code?: string | null) {
    return meta(PARTNER_TYPE, code)
}

export function batchStatusMeta(code?: string | null) {
    return meta(BATCH_STATUS, code)
}

export function inTransitStatusMeta(code?: string | null) {
    return meta(IN_TRANSIT_STATUS, code)
}

/* ---------------- Tag 组件 ---------------- */

/**
 * 字典 Tag 一律吃 props（`code`）而不是位置参数：
 * 位置参数在 JSX 里写起来是 `<ModeTag code={v} />`，TypeScript 会把它当成
 * 「传了一个名叫 code 的对象给第一个参数」，然后报一个和真实原因无关的错。
 * 用 props 也顺带留了以后加 tooltip/onClick 的余地。
 *
 * 空值统一显示「—」而不是空 Tag：跨库聚合列的空是**有含义的**
 * （下游不可用 / 还没建），空 Tag 会被误读成「有值但标签色没取到」。
 */
export interface CodeTagProps {
    code?: string | null
}

export function ModeTag({ code }: CodeTagProps): ReactNode {
    if (!code) {
        return <span style={{ color: '#98a2b3' }}>—</span>
    }
    const m = modeMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export function ExportOrderStatusTag({ code }: CodeTagProps): ReactNode {
    const m = exportOrderStatusMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export function ExportBatchStatusTag({ code }: CodeTagProps): ReactNode {
    const m = exportBatchStatusMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export interface ShipmentStatusTagProps extends CodeTagProps {
    /** 可选：这票货的运输方式。只影响左侧色条，不影响状态本身的颜色。 */
    mode?: string | null
}

/**
 * 运单状态 Tag。`mode` 是**第二根色轴**：状态色留给状态，运输方式只用一条左侧色条。
 *
 * 为什么不给整块 Tag 上运输方式的颜色（P2-1 提过「按 mode 区分 tag 颜色」）：
 * 状态色里最要紧的两个是「异常=红」「退运=橙」，它们比「这是海运还是快递」更紧急。
 * 一旦让运输方式覆盖状态色，一行**异常**的海运会和一行**在途**的海运长得一模一样——
 * 等于用一个新错换掉旧错。两条色轴各管一件事，这一行才读得懂。
 * 用 box-shadow 的 inset 而不是 border-left：后者会把左边框撑成 3px，
 * Tag 的圆角和描边宽度在有值/无值之间还会跳一下。
 */
export function ShipmentStatusTag({ code, mode }: ShipmentStatusTagProps): ReactNode {
    if (!code) {
        return <span style={{ color: '#98a2b3' }}>—</span>
    }
    const m = shipmentStatusMeta(code)
    const accent = modeAccent(mode)
    return (
        <Tag color={m.color} style={accent ? { boxShadow: `inset 3px 0 0 0 ${accent}` } : undefined}>
            {m.label}
        </Tag>
    )
}

export function PackTaskStatusTag({ code }: CodeTagProps): ReactNode {
    if (!code) {
        return <span style={{ color: '#98a2b3' }}>—</span>
    }
    const m = packTaskStatusMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export function DeclarationStatusTag({ code }: CodeTagProps): ReactNode {
    if (!code) {
        return <span style={{ color: '#98a2b3' }}>—</span>
    }
    const m = declarationStatusMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export function TicketStatusTag({ code }: CodeTagProps): ReactNode {
    const m = ticketStatusMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export function TicketCategoryTag({ code }: CodeTagProps): ReactNode {
    const m = ticketCategoryMeta(code)
    return <Tag color={m.color}>{m.label}</Tag>
}

export function TrackingSourceTag({ code }: CodeTagProps): ReactNode {
    const m = meta(TRACKING_SOURCE, code)
    return <Tag color={m.color}>{m.label}</Tag>
}

/**
 * 运输方式的「点缀色」（真实 CSS 色值），只有一个用途：给**已经带状态色**的 Tag
 * 加一条左侧色条，表示这一行的状态属于哪种运输方式。
 *
 * 为什么不能直接用 EXPORT_MODE 里的 'blue' / 'purple'：那些是 antd 的语义色**名**，
 * 只在 `<Tag color>` 里有效；CSS 的 border-color / box-shadow 只认真实颜色值。
 * 这是 antd 的限制而不是本项目的选择——所以同一种运输方式在「Tag 填充」与
 * 「CSS 描边」两处必须各写一份，两份都收在这个文件里，不许散到各个页面去。
 * 色值取 antd v5 对应语义色的**实色档**：浅色档（blue 的 #e6f4ff 那一类）在白底上
 * 3px 宽的色条几乎看不见，那这根色条就等于没画。
 */
export const MODE_ACCENT: Record<string, string> = {
    SEA: '#1677ff', // 对应 EXPORT_MODE 的 blue
    LCL: '#13c2c2', // cyan
    AIR: '#2f54eb', // geekblue
    EXPRESS: '#722ed1', // purple
    RAIL: '#fa8c16', // orange
    TRUCK: '#d48806' // gold 的深一档，金黄太浅时色条会糊在白底上
}

/**
 * 取运输方式的点缀色；未知 / 空值返回 undefined，调用方据此**不画色条**。
 * 返回 undefined 而不是灰色：灰条会被读成「这是第三种运输方式」。
 */
export function modeAccent(code?: string | null): string | undefined {
    return (code && MODE_ACCENT[code]) || undefined
}

/** 跨库聚合列的空值统一显示「—」，与「有值但为空串」区分开。 */
export function dash(value?: string | number | null) {
    return value === null || value === undefined || value === '' ? (
        <span style={{ color: '#98a2b3' }}>—</span>
    ) : (
        String(value)
    )
}

/* ---------------- 格式化 ---------------- */

/**
 * 金额统一千分位 + 两位小数 + 币种；空值返回「—」。
 * 第二个参数（币种）在 TS 签名上是必填的——国际业务是多币种，
 * 漏了币种的金额等于没有意义。
 */
export function formatMoney(value: number | null | undefined, currency?: string | null): string {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return '—'
    }
    const amount = Number(value).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    })
    return currency ? `${currency} ${amount}` : amount
}

/**
 * UTC 存储 → 北京时间展示。
 *
 * 为什么必须补 Z 再解析：后端把 LocalDateTime 序列化成 `2026-10-14T01:40:00`，
 * 这个字符串**没有时区信息**。直接 new Date() 会按浏览器本地时区解析，
 * 开发机（Asia/Shanghai）恰好与「我以为的口径」一致，看不出问题；
 * 换一台 UTC 的机器就差 8 小时。统一补 Z 是最小改动的确定口径。
 */
export function formatUtc(value: string | null | undefined): string {
    if (!value) {
        return '—'
    }
    const d = new Date(value.endsWith('Z') ? value : `${value}Z`)
    if (Number.isNaN(d.getTime())) {
        return value
    }
    return d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

/** 只到日的 UTC 展示（ETD/ETA 当天用）。 */
export function formatUtcDate(value: string | null | undefined): string {
    if (!value) {
        return '—'
    }
    return formatUtc(value).slice(0, 10)
}

/** DATE 列（当地日期）只做截断，不做时区换算。 */
export function formatDate(value: string | null | undefined): string {
    if (!value) {
        return '—'
    }
    return value.replace('T', ' ').slice(0, 10)
}

/** 重量（KG）。 */
export function formatKg(value: number | null | undefined): string {
    if (value === null || value === undefined) {
        return '—'
    }
    return `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 3 })} kg`
}

/** 体积（CBM）。 */
export function formatCbm(value: number | null | undefined): string {
    if (value === null || value === undefined) {
        return '—'
    }
    return `${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 3, maximumFractionDigits: 4 })} CBM`
}

/** ETD/ETA 是否已过期（用于标红）。与后端的「逾期」口径一致：ETD 已过且状态未推进到开航。 */
export function isOverdue(etd: string | null | undefined, status?: string | null): boolean {
    if (!etd) {
        return false
    }
    const past = ['DEPARTED', 'IN_TRANSIT', 'ARRIVED', 'CLEARING', 'CLEARED', 'LAST_MILE', 'DELIVERED', 'POD_CONFIRMED', 'CANCELLED']
    if (status && past.includes(status)) {
        return false
    }
    return new Date(`${etd.replace('T', ' ').slice(0, 19)}Z`).getTime() < Date.now()
}
