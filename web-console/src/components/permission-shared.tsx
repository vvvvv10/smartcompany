import { Tag, Typography } from 'antd'
import type { ReactNode } from 'react'
import type { PermissionRow } from '../api/types'

/**
 * 权限点的模块名与配色——**全站唯一一份**。
 *
 * <p>权限中心、组织架构、我的团队三处都在用它。抽出来是因为「console = 控制台」
 * 这种叫法但凡有两份，改名时必然漏掉一份，界面上就会同时出现两种叫法。</p>
 */
export const MODULE_LABEL: Record<string, string> = {
    console: '控制台',
    profile: '个人',
    user: '用户管理',
    rbac: '权限配置',
    crm: 'CRM',
    tms: 'TMS 运输',
    wms: 'WMS 仓储',
    oms: 'OMS 订单'
}

export const MODULE_COLOR: Record<string, string> = {
    console: '#6366f1',
    profile: '#f43f5e',
    user: '#10b981',
    rbac: '#f59e0b',
    crm: '#ec4899',
    tms: '#0ea5e9',
    wms: '#14b8a6',
    oms: '#f97316'
}

/**
 * 不在 {@code permissions} 表里的派生权限码的显示名——后端按团队负责人身份注入，
 * 目录接口查不到；不补这里，界面上就会漏出裸编码。
 */
const DERIVED_LABEL: Record<string, string> = {
    'team:view': '查看我的团队'
}

/**
 * 权限码 → 中文名：先查目录，再查派生码表，最后回退原码（不掉码）。
 *
 * <p>「我的身份」「我的权限点」「我的团队」三处都走它，裸编码只作为兜底出现。</p>
 */
export function permissionLabel(code: string, rows: PermissionRow[]): string {
    return rows.find((p) => p.code === code)?.name ?? DERIVED_LABEL[code] ?? code
}

/** 分组展示顺序：业务系统在前，管理台在后；未知模块统一排最后按字母序 */
const MODULE_ORDER = ['crm', 'tms', 'wms', 'oms', 'console', 'user', 'rbac', 'profile']

export interface PermissionGroup {
    module: string
    /** 分组标题：业务名，如 CRM / TMS 运输；派生码归「团队」 */
    label: string
    items: { code: string; label: string }[]
}

/**
 * 权限码列表 → 按业务（模块）分组。
 *
 * <p>分组是业务权限名能写成「查看 / 修改」这种纯动作的前提：标题给出业务
 * （CRM → 查看、修改），组内才不必重复业务名。未登录管理员拿不到目录时
 * 回退原码，分组退化为按模块归堆，不掉码。</p>
 */
export function groupPermissionCodes(codes: string[], rows: PermissionRow[]): PermissionGroup[] {
    const groups = new Map<string, { code: string; label: string }[]>()
    for (const code of codes) {
        const module = rows.find((p) => p.code === code)?.module ?? ''
        if (!groups.has(module)) groups.set(module, [])
        groups.get(module)!.push({ code, label: permissionLabel(code, rows) })
    }
    const rank = (module: string) => {
        const i = MODULE_ORDER.indexOf(module)
        return i === -1 ? MODULE_ORDER.length : i
    }
    return Array.from(groups.entries())
        .sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))
        .map(([module, items]) => ({
            module,
            label: MODULE_LABEL[module] ?? (module || '团队'),
            items
        }))
}

/**
 * 权限码按模块分组渲染成彩色 Tag。
 *
 * <p>团队抽屉、组织架构面板、我的团队面板三处共用。模块在目录里查不到时按空串归组
 * （比如不在 {@code permissions} 表里的派生码），模块名兜底：有值显示原始值、空串显示
 * 「团队」，不会掉码。</p>
 */
export function PermissionGroups({
    codes,
    permRows,
    emptyText = '暂无权限'
}: {
    codes: string[]
    permRows: PermissionRow[]
    emptyText?: ReactNode
}) {
    const nameOf = (code: string) => permissionLabel(code, permRows)
    const groups = new Map<string, string[]>()
    for (const code of codes) {
        const module = permRows.find((p) => p.code === code)?.module ?? ''
        if (!groups.has(module)) groups.set(module, [])
        groups.get(module)!.push(code)
    }
    if (groups.size === 0) {
        return <Typography.Text type="secondary">{emptyText}</Typography.Text>
    }
    return (
        <>
            {Array.from(groups.entries()).map(([module, list]) => (
                <div key={module} style={{ marginBottom: 10 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {MODULE_LABEL[module] ?? (module || '团队')}
                        <span style={{ marginLeft: 6 }}>{list.length} 项</span>
                    </Typography.Text>
                    <div style={{ marginTop: 4 }}>
                        {list.map((code) => (
                            <Tag
                                key={code}
                                color={MODULE_COLOR[module] ?? '#4f46e5'}
                                style={{ marginBottom: 4 }}
                            >
                                {nameOf(code)}
                            </Tag>
                        ))}
                    </div>
                </div>
            ))}
        </>
    )
}
