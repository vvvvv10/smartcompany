import { Tag } from 'antd'
import type { ReactNode } from 'react'

/** WMS 中英文/配色映射：后端只存枚举码，展示层统一在这里转换。 */

export const WAREHOUSE_STATUS: Record<string, { label: string; color: string }> = {
    ACTIVE: { label: '启用', color: 'success' },
    INACTIVE: { label: '停用', color: 'default' },
    FULL: { label: '已满', color: 'warning' }
}

export const STOCK_TYPE: Record<string, { label: string; color: string }> = {
    IN: { label: '入库', color: 'success' },
    OUT: { label: '出库', color: 'processing' }
}

export function warehouseStatusMeta(status: string) {
    return WAREHOUSE_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function stockTypeMeta(type: string) {
    return STOCK_TYPE[type] ?? { label: type || '未知', color: 'default' }
}

export function WarehouseStatusTag(status: string): ReactNode {
    const meta = warehouseStatusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function StockTypeTag(type: string): ReactNode {
    const meta = stockTypeMeta(type)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

/** 后端返回 yyyy-MM-ddTHH:mm:ss，列表里只展示到分钟 */
export function formatTime(value: string | null | undefined): string {
    if (!value) {
        return ''
    }
    return value.replace('T', ' ').slice(0, 16)
}
