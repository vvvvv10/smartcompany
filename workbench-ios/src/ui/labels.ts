/**
 * 枚举中文/配色映射与格式化——从 workbench-android 的 Labels.kt 移植。
 *
 * 与 web 端 `pages/crm/shared.tsx` 的文案保持一字不差：后端只存枚举码，
 * 展示层统一在这一处转换，两个端才不会出现「这边叫谈判、那边叫 NEGOTIATION」的分裂。
 */

import {
    AdminOrange,
    BrandAmber,
    BrandEmerald,
    BrandIndigo,
    BrandSky,
    ErrorRed,
    OverdueRed,
    TextMuted,
    TextSecondary
} from './theme'

export interface Label {
    text: string
    color: string
}

const Unknown: Label = { text: '未知', color: TextMuted }

const CUSTOMER_STATUS: Record<string, Label> = {
    POTENTIAL: { text: '潜在', color: TextSecondary },
    FOLLOWING: { text: '跟进中', color: BrandSky },
    DEAL: { text: '成交', color: BrandEmerald },
    LOST: { text: '流失', color: ErrorRed }
}

const CUSTOMER_LEVEL: Record<string, Label> = {
    KEY: { text: '重点', color: AdminOrange },
    NORMAL: { text: '普通', color: BrandSky },
    LOW: { text: '低优先', color: TextSecondary }
}

const OPPORTUNITY_STAGE: Record<string, Label> = {
    LEAD: { text: '线索', color: TextSecondary },
    PROPOSAL: { text: '方案', color: BrandIndigo },
    NEGOTIATION: { text: '谈判', color: BrandSky },
    WON: { text: '赢单', color: BrandEmerald },
    LOST: { text: '输单', color: ErrorRed }
}

const FOLLOW_UP_TYPE: Record<string, Label> = {
    CALL: { text: '电话', color: BrandSky },
    VISIT: { text: '拜访', color: BrandEmerald },
    WECHAT: { text: '微信', color: '#14B8A6' },
    MAIL: { text: '邮件', color: '#8B5CF6' },
    OTHER: { text: '其他', color: TextSecondary }
}

/** 花名申请状态。PENDING 是唯一需要管理员动作的状态，用琥珀色标出来。 */
const REQUEST_STATUS: Record<string, Label> = {
    PENDING: { text: '待审批', color: BrandAmber },
    APPROVED: { text: '已通过', color: BrandEmerald },
    REJECTED: { text: '已驳回', color: ErrorRed }
}

/** 待办时间窗。OVERDUE 必须是最扎眼的那个红色。 */
const DUE: Record<string, Label> = {
    OVERDUE: { text: '已逾期', color: OverdueRed },
    TODAY: { text: '今天', color: BrandAmber },
    UPCOMING: { text: '未来 7 天', color: BrandSky }
}

export function statusLabel(code: string): Label {
    return CUSTOMER_STATUS[code] ?? { ...Unknown, text: code || '未知' }
}

export function levelLabel(code: string): Label {
    return CUSTOMER_LEVEL[code] ?? { ...Unknown, text: code || '未知' }
}

export function stageLabel(code: string): Label {
    return OPPORTUNITY_STAGE[code] ?? { ...Unknown, text: code || '未知' }
}

export function typeLabel(code: string): Label {
    return FOLLOW_UP_TYPE[code] ?? Unknown
}

export function requestStatusLabel(code: string | null | undefined): Label {
    return REQUEST_STATUS[code ?? ''] ?? { ...Unknown, text: '暂无申请' }
}

export function dueLabel(code: string): Label {
    return DUE[code] ?? Unknown
}

/** "2026-09-29T20:53:32" → "09-29 20:53"。列表里不需要年份，留着反而占宽。 */
export function formatTime(iso: string | null | undefined): string {
    if (!iso) return '—'
    const clean = iso.replace('T', ' ')
    return clean.length >= 16 ? clean.substring(5, 16) : clean
}

/**
 * "2026-10-03T15:30:00" → "2026-10-03 15:30"。
 *
 * 与 formatTime 的区别是**保留年份**：那是"记录发生在何时"，通常是最近的事；
 * 而"下次跟进"是**未来排期**，跨年时不带年份就分不清是今年还是明年。
 *
 * 后端两个服务的时间格式并不统一（线上实测）：crm 侧是 ISO，user-center 侧是空格分隔。
 * 统一 replace 之后两者都落到 `yyyy-MM-dd HH:mm:ss`，下面的下标截取才对两边都成立。
 */
export function formatDateTime(iso: string | null | undefined): string {
    if (!iso) return '—'
    const clean = iso.replace('T', ' ')
    return clean.length >= 16 ? clean.substring(0, 16) : clean
}

/** "2026-09-29" → "2026-09-29" */
export function formatDate(iso: string | null | undefined): string {
    if (!iso) return '—'
    const clean = iso.replace('T', ' ')
    return clean.length >= 10 ? clean.substring(0, 10) : clean
}

/**
 * 金额：`1430000.00` → `143万`，小于 1 万才显示到元。
 *
 * 指标卡的宽度只够放 4~5 个字符，`1,430,000.00` 会被截断，
 * 而「143万」正是这类卡片的通行写法。整万时去掉小数位（143.0万 → 143万）。
 */
export function formatMoney(v: number): string {
    const amount = Math.abs(v)
    const sign = v < 0 ? '-' : ''
    if (amount >= 100_000_000) return sign + trimZero((amount / 100_000_000).toFixed(1) + '亿')
    if (amount >= 10_000) return sign + trimZero((amount / 10_000).toFixed(1) + '万')
    return sign + '¥' + Math.round(amount).toLocaleString('zh-CN')
}

function trimZero(s: string): string {
    return s.replace('.0亿', '亿').replace('.0万', '万')
}

/** 赢率 70 → "70%" */
export function formatPercent(v: number): string {
    return `${v}%`
}

/** 问候语，与 web 端同一套时段划分。 */
export function greeting(): string {
    const h = new Date().getHours()
    if (h >= 0 && h <= 5) return '夜深了'
    if (h >= 6 && h <= 11) return '早上好'
    if (h >= 12 && h <= 13) return '中午好'
    if (h >= 14 && h <= 17) return '下午好'
    return '晚上好'
}

/** "2026-09-29" → "9月29日"（概览页展示当天日期用） */
export function todayText(): string {
    const c = new Date()
    return `${c.getMonth() + 1}月${c.getDate()}日`
}
