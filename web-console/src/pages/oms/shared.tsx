import { Tag } from 'antd'
import type { ReactNode } from 'react'

/** OMS 中英文/配色映射：后端只存枚举码，展示层统一在这里转换。 */

export const ORDER_STATUS: Record<string, { label: string; color: string }> = {
    UNPAID: { label: '待付款', color: 'default' },
    TO_SHIP: { label: '待发货', color: 'processing' },
    SHIPPED: { label: '已发货', color: 'blue' },
    DONE: { label: '已完成', color: 'success' },
    AFTER_SALE: { label: '售后', color: 'warning' },
    CANCELLED: { label: '已取消', color: 'error' }
}

export const CHANNEL: Record<string, { label: string; color: string }> = {
    DOUYIN: { label: '抖音', color: 'volcano' },
    TAOBAO: { label: '淘宝', color: 'orange' },
    P1688: { label: '1688批发', color: 'gold' },
    OFFLINE: { label: '线下', color: 'green' },
    WECHAT: { label: '微信', color: 'lime' }
}

export const CATEGORY: Record<string, { label: string; color: string }> = {
    TOP: { label: '上衣', color: 'blue' },
    PANTS: { label: '裤装', color: 'geekblue' },
    DRESS: { label: '裙装', color: 'magenta' },
    OUTER: { label: '外套', color: 'purple' },
    KNIT: { label: '针织', color: 'cyan' }
}

export const PRODUCT_STATUS: Record<string, { label: string; color: string }> = {
    ON: { label: '上架', color: 'success' },
    OFF: { label: '下架', color: 'default' }
}

export function orderStatusMeta(status: string) {
    return ORDER_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function channelMeta(channel: string) {
    return CHANNEL[channel] ?? { label: channel || '未知', color: 'default' }
}

export function categoryMeta(category: string) {
    return CATEGORY[category] ?? { label: category || '—', color: 'default' }
}

export function productStatusMeta(status: string) {
    return PRODUCT_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function OrderStatusTag(status: string): ReactNode {
    const meta = orderStatusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function ChannelTag(channel: string): ReactNode {
    const meta = channelMeta(channel)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function CategoryTag(category: string): ReactNode {
    const meta = categoryMeta(category)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function ProductStatusTag(status: string): ReactNode {
    const meta = productStatusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

/** 金额统一千分位，保留两位小数；空值返回占位符 */
export function formatAmount(value: number | null | undefined): string {
    if (value == null || Number.isNaN(Number(value))) {
        return '—'
    }
    return Number(value).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    })
}

/** 后端返回 yyyy-MM-ddTHH:mm:ss，列表里只展示到分钟 */
export function formatTime(value: string | null | undefined): string {
    if (!value) {
        return ''
    }
    return value.replace('T', ' ').slice(0, 16)
}
