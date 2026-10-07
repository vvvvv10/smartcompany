import { Tag } from 'antd'
import type { ReactNode } from 'react'

/** TMS 中英文/配色映射：后端只存枚举码，展示层统一在这里转换。 */

export const ORDER_STATUS: Record<string, { label: string; color: string }> = {
    PENDING: { label: '待发车', color: 'default' },
    IN_TRANSIT: { label: '运输中', color: 'processing' },
    DELIVERED: { label: '已送达', color: 'success' },
    CANCELLED: { label: '已取消', color: 'error' }
}

export const VEHICLE_STATUS: Record<string, { label: string; color: string }> = {
    IDLE: { label: '空闲', color: 'success' },
    BUSY: { label: '运输中', color: 'processing' },
    MAINTENANCE: { label: '维修中', color: 'warning' }
}

export const DRIVER_STATUS: Record<string, { label: string; color: string }> = {
    ACTIVE: { label: '在职', color: 'success' },
    INACTIVE: { label: '停用', color: 'default' }
}

export function orderStatusMeta(status: string) {
    return ORDER_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function vehicleStatusMeta(status: string) {
    return VEHICLE_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function driverStatusMeta(status: string) {
    return DRIVER_STATUS[status] ?? { label: status || '未知', color: 'default' }
}

export function OrderStatusTag(status: string): ReactNode {
    const meta = orderStatusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function VehicleStatusTag(status: string): ReactNode {
    const meta = vehicleStatusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

export function DriverStatusTag(status: string): ReactNode {
    const meta = driverStatusMeta(status)
    return <Tag color={meta.color}>{meta.label}</Tag>
}

/** 后端返回 yyyy-MM-ddTHH:mm:ss，列表里只展示到分钟 */
export function formatTime(value: string | null | undefined): string {
    if (!value) {
        return ''
    }
    return value.replace('T', ' ').slice(0, 16)
}
