import { Tag } from 'antd'
import type { ReactNode } from 'react'

/** CRM 中英文/配色映射：后端只存枚举码，展示层统一在这里转换。 */

export const CUSTOMER_STATUS: Record<string, { label: string; color: string }> = {
    POTENTIAL: { label: '潜在', color: 'default' },
    FOLLOWING: { label: '跟进中', color: 'processing' },
    DEAL: { label: '成交', color: 'success' },
    LOST: { label: '流失', color: 'error' }
}

export const CUSTOMER_LEVEL: Record<string, { label: string; color: string }> = {
    KEY: { label: '重点', color: 'volcano' },
    NORMAL: { label: '普通', color: 'blue' },
    LOW: { label: '低优先', color: 'default' }
}

export const OPPORTUNITY_STAGE: Record<string, { label: string; color: string }> = {
    LEAD: { label: '线索', color: 'default' },
    PROPOSAL: { label: '方案', color: 'geekblue' },
    NEGOTIATION: { label: '谈判', color: 'processing' },
    WON: { label: '赢单', color: 'success' },
    LOST: { label: '输单', color: 'error' }
}

/** 阶段推进顺序，用于「推进到下一阶段」按钮 */
export const STAGE_FLOW = ['LEAD', 'PROPOSAL', 'NEGOTIATION', 'WON']

export const FOLLOW_UP_TYPE: Record<string, { label: string; color: string }> = {
    CALL: { label: '电话', color: 'blue' },
    VISIT: { label: '拜访', color: 'green' },
    WECHAT: { label: '微信', color: 'cyan' },
    MAIL: { label: '邮件', color: 'purple' },
    OTHER: { label: '其他', color: 'default' }
}

export const SOURCE_OPTIONS = ['线上咨询', '展会', '转介绍', '电话开发', '广告投放', '其他']

export function statusMeta(status: string) {
    return CUSTOMER_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function levelMeta(level: string) {
    return CUSTOMER_LEVEL[level] ?? { label: level || '未知', color: 'default' }
}

export function stageMeta(stage: string) {
    return OPPORTUNITY_STAGE[stage] ?? { label: stage || '未知', color: 'default' }
}

export function typeMeta(type: string) {
    return FOLLOW_UP_TYPE[type] ?? { label: type || '其他', color: 'default' }
}

export function StatusTag(status: string): ReactNode {
    const meta = statusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function LevelTag(level: string): ReactNode {
    const meta = levelMeta(level)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function StageTag(stage: string): ReactNode {
    const meta = stageMeta(stage)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function TypeTag(type: string): ReactNode {
    const meta = typeMeta(type)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

/** 金额统一按「¥ 120,000」展示 */
export function formatMoney(value: number | null | undefined): string {
    if (value === null || value === undefined) {
        return '¥ 0'
    }
    return `¥ ${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}

/** 后端返回 yyyy-MM-ddTHH:mm:ss，列表里只展示到分钟 */
export function formatTime(value: string | null | undefined): string {
    if (!value) {
        return ''
    }
    return value.replace('T', ' ').slice(0, 16)
}
