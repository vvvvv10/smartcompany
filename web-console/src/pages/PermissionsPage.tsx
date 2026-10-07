import {
    Alert,
    Button,
    Card,
    Checkbox,
    Drawer,
    Form,
    Input,
    Modal,
    Popconfirm,
    Select,
    Space,
    Spin,
    Switch,
    Table,
    Tabs,
    Tag,
    Tooltip,
    TreeSelect,
    Typography,
    message
} from 'antd'
import {
    ApartmentOutlined,
    AppstoreOutlined,
    CrownOutlined,
    SafetyCertificateOutlined,
    TeamOutlined,
    UsergroupAddOutlined
} from '@ant-design/icons'
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { api, errorMessage } from '../api/client'
import {
    PageResult,
    PermissionRow,
    RoleMember,
    RoleMatrix,
    RoleOverview,
    RoleRow,
    TeamMemberRow,
    TeamRow,
    UserRow
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
// 模块名/配色与权限分组渲染是三处共用的，放在 components 里免得改名改漏
import { MODULE_COLOR, MODULE_LABEL, PermissionGroups, groupPermissionCodes } from '../components/permission-shared'
import { OrgExplorer } from '../components/OrgExplorer'

const ROLE_TAG_COLOR: Record<string, string> = {
    ADMIN: 'purple',
    USER: 'blue'
}

/**
 * 权限中心：「概览」与「团队」两个页签对所有登录用户开放——概览是我的权限点 +
 * 全目录（已有的可删除、没有的可申请），团队按身份分两套口径（管理者看全量可增删改，
 * 普通成员只读、只见自己所在的团队，42 号）；其后是角色管理（成员+编辑）/
 * 角色-权限授权矩阵 / 系统管理员（权限申请第 2 级审批人，41 号）/ 组织架构，
 * 这四个管理页签需 role:manage。
 *
 * 页面不由 App.tsx 按 role:manage 一刀切守卫——「申请权限」恰恰是还没有权限的人
 * 要做的事；管理页签在页内按 role:manage 过滤，管理类写操作仍要求该权限点。
 */
export default function PermissionsPage() {
    const { hasPermission } = useAuth()
    // 「概览」是唯一人人可见的页签（我的权限点 + 全目录自助），默认落在它上面
    const canManage = hasPermission('role:manage')
    const [tab, setTab] = useState('overview')
    const [overview, setOverview] = useState<RoleOverview | null>(null)

    const loadOverview = useCallback(() => {
        api.get<RoleOverview>('/admin/overview')
            .then((res) => setOverview(res.data))
            .catch((err) => message.error(errorMessage(err, '加载概览失败')))
    }, [])

    useEffect(() => {
        // 概览接口在 role:manage 后面（AdminPermissionController），没有该权限的用户
        // 拉它只会吃一个 403 弹错——他的页面上根本不渲染这些数据，不拉
        if (canManage) loadOverview()
    }, [loadOverview, canManage])

    // 「概览」「团队」人人可见；角色管理/授权矩阵/系统管理员/组织架构四个管理页签
    // 按 role:manage 过滤。团队页签的分权在 TeamsTab 里做：管理者全量可编辑，
    // 普通成员只读且只见自己所在的团队（数据源分别是 /admin/teams 与
    // /users/me/joined-teams，行级边界都在服务端）
    const tabItems = [
        {
            key: 'overview',
            label: <span><AppstoreOutlined />概览</span>,
            children: (
                <OverviewTab
                    overview={overview}
                    canManage={canManage}
                    onGotoRole={() => setTab('roles')}
                />
            )
        },
        ...(canManage
            ? [
                {
                    key: 'roles',
                    label: <span><TeamOutlined />角色管理</span>,
                    children: <RolesTab onGotoMatrix={() => setTab('matrix')} />
                },
                {
                    key: 'matrix',
                    label: <span><SafetyCertificateOutlined />授权矩阵</span>,
                    children: <MatrixTab onSaved={loadOverview} />
                },
                {
                    key: 'sysadmins',
                    label: <span><CrownOutlined />系统管理员</span>,
                    children: <SystemAdminsTab />
                }
            ]
            : []),
        {
            key: 'teams',
            label: <span><UsergroupAddOutlined />团队</span>,
            children: <TeamsTab canManage={canManage} />
        },
        ...(canManage
            ? [
                {
                    key: 'org',
                    label: <span><ApartmentOutlined />组织架构</span>,
                    children: <OrgTab />
                }
            ]
            : [])
    ]
    // 权限在别处被收回时，当前页签可能已不在可见列表里——回落到第一个可见页签
    const activeKey = tabItems.some((item) => item.key === tab) ? tab : tabItems[0].key

    return (
        <div className="fade-in">
            {/* 头图讲的是「角色 × 权限点」的管理口径、三个数字全部来自 role:manage 的
                概览接口；无该权限的用户既看不到管理页签也拿不到这些数，藏起来免得一排「–」 */}
            {canManage && (
                <div className="perm-hero">
                    <div>
                        <h2 className="perm-hero-title">角色 × 权限点</h2>
                        <p className="perm-hero-desc">
                            角色是权限点的集合：在「授权矩阵」勾选后，持有该角色的用户立即获得对应菜单与接口权限。
                            ADMIN 角色由后端代码兜底，永远持有全部权限点，不会被锁死。
                        </p>
                    </div>
                    <div className="perm-hero-meta">
                        <div className="perm-hero-item">
                            <div className="perm-hero-value">{overview?.roleCount ?? '–'}</div>
                            <div className="perm-hero-label">角色</div>
                        </div>
                        <div className="perm-hero-item">
                            <div className="perm-hero-value">{overview?.permissionCount ?? '–'}</div>
                            <div className="perm-hero-label">权限点</div>
                        </div>
                        <div className="perm-hero-item">
                            <div className="perm-hero-value">{overview?.grantCount ?? '–'}</div>
                            <div className="perm-hero-label">授权关系</div>
                        </div>
                    </div>
                </div>
            )}

            <Tabs activeKey={activeKey} onChange={setTab} items={tabItems} />
        </div>
    )
}

/* ---------------- 概览（我的权限点 + 全目录自助申请/删除） ---------------- */

function OverviewTab({
    overview,
    canManage,
    onGotoRole
}: {
    overview: RoleOverview | null
    canManage: boolean
    onGotoRole: () => void
}) {
    const { profile } = useAuth()
    const [permissions, setPermissions] = useState<PermissionRow[]>([])

    useEffect(() => {
        // 标签库走人人可读的 catalog：概览页签对无 role:manage 的用户也开放，
        // 原来的 /admin/permissions 在他这儿 403，卡片分组会静默退化成裸 code
        api.get<PermissionRow[]>('users/permissions/catalog')
            .then((res) => setPermissions(res.data))
            .catch(() => {})
    }, [])

    return (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card
                className="table-card"
                title="我的权限点"
                extra={canManage ? <Button size="small" onClick={onGotoRole}>去管理角色</Button> : undefined}
                styles={{ body: { padding: '16px 20px' } }}
            >
                {profile && profile.permissionCodes.length > 0 ? (
                    <div>
                        {/* 按业务分组：标题给业务（CRM → 查看、修改），组内动作短名不重复业务 */}
                        {groupPermissionCodes(profile.permissionCodes, permissions).map((group) => (
                            <div key={group.module} className="perm-mine-group">
                                <span className="perm-mine-group-label">{group.label}</span>
                                {group.items.map(({ code, label }) => (
                                    <span key={code} className="perm-mine-tag" title={code}>
                                        {label}
                                        {label !== code && <span className="perm-mine-code">{code}</span>}
                                    </span>
                                ))}
                            </div>
                        ))}
                    </div>
                ) : (
                    <Typography.Text type="secondary">当前账号没有任何权限点</Typography.Text>
                )}
                <Typography.Text
                    type="secondary"
                    style={{ fontSize: 12, display: 'block', marginTop: 6 }}
                >
                    来自 /api/users/me 的 permissionCodes，按你持有的角色解析；ADMIN 直接返回全量。
                </Typography.Text>
            </Card>

            {/* 全目录 + 自助申请/删除：原独立「权限点」页签并进概览，少一层导航 */}
            <PermsTab />

            {overview && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    共 {overview.moduleCount} 个模块、{overview.permissionCount} 个权限点、
                    {overview.customRoleCount} 个自定义角色。
                </Typography.Text>
            )}
        </Space>
    )
}

/* ---------------- 全目录自助（申请 / 删除，随概览页签渲染） ---------------- */

/** 我的权限申请单（users/permissions/my 的 requests 元素，最新在前） */
interface PermissionRequestRow {
    id: number
    permissionCode: string
    note: string | null
    status: 'PENDING' | 'APPROVED' | 'REJECTED'
    reviewNote: string | null
    createdAt: string
    reviewedAt: string | null
    // 两级审批（41 号）：当前级次与链路展示信息（审批中按钮的 tooltip 用）
    stage: number
    leadNames: string
    sysAdminNames: string
}

/** users/permissions/my 响应：有效权限 + 直授/拉黑覆盖行 + 我的申请单 */
interface MyPerms {
    effective: string[]
    grants: string[]
    revokes: string[]
    requests: PermissionRequestRow[]
}

/**
 * 全目录自助区（渲染在「概览」页签内）：权限全目录，没有的可申请、已有的可删除。
 *
 * <p>「是否已有」只看 useAuth().profile.permissionCodes——它与后端 resolve 同源
 * （角色授权 + 直授 - 拉黑），拿 my.grants 判断会把角色带来的权限误判成「没有」，
 * 因为那些权限并没有直授行。</p>
 *
 * <p>按钮状态机：已有 → 删除（写 effect=0 覆盖，即时生效）；有 PENDING 申请 → 审批中
 * （悬停可见卡在哪一级）；否则 → 申请（提交申请单，在「审批中心」经两级审批：
 * 组织上一级 → 系统管理员）。</p>
 */
function PermsTab() {
    const { profile, reload } = useAuth()
    const [catalog, setCatalog] = useState<PermissionRow[]>([])
    const [my, setMy] = useState<MyPerms | null>(null)
    const [loading, setLoading] = useState(true)
    const [loadErr, setLoadErr] = useState<string | null>(null)
    const [applyTarget, setApplyTarget] = useState<PermissionRow | null>(null)
    const [applying, setApplying] = useState(false)
    // 正在移除的行：防重复点击，也让按钮有反馈
    const [busyCode, setBusyCode] = useState<string | null>(null)
    const [applyForm] = Form.useForm<{ note?: string }>()

    const load = useCallback(async () => {
        setLoading(true)
        setLoadErr(null)
        try {
            // 目录与我的申请单互不依赖，并发拉，省一个来回
            const [cat, mine] = await Promise.all([
                api.get<PermissionRow[]>('users/permissions/catalog'),
                api.get<MyPerms>('users/permissions/my')
            ])
            setCatalog(cat.data)
            setMy(mine.data)
        } catch (err) {
            setLoadErr(errorMessage(err, '加载权限点失败'))
        } finally {
            setLoading(false)
        }
    }, [])

    // 写操作后只刷「我的」这一半：目录不会变，重拉它只会让整块网格闪一下
    const loadMine = useCallback(async () => {
        try {
            const res = await api.get<MyPerms>('users/permissions/my')
            setMy(res.data)
        } catch (err) {
            message.error(errorMessage(err, '刷新我的权限失败'))
        }
    }, [])

    // 申请/移除都会改变本人权限口径：本页状态与全局 profile 都要刷——
    // 侧栏菜单、按钮状态读的都是 profile.permissionCodes，不刷就像「没生效」
    const afterChange = useCallback(async () => {
        await Promise.all([loadMine(), reload()])
    }, [loadMine, reload])

    useEffect(() => {
        load()
    }, [load])

    const revoke = async (row: PermissionRow) => {
        setBusyCode(row.code)
        try {
            await api.post('users/permissions/revoke', { code: row.code })
            message.success(`已移除「${row.name}」，即时生效`)
            await afterChange()
        } catch (err) {
            // 409 not_granted / unknown_permission：后端文案直接透出
            message.error(errorMessage(err, '移除失败'))
        } finally {
            setBusyCode(null)
        }
    }

    const submitApply = async (values: { note?: string }) => {
        if (!applyTarget) return
        setApplying(true)
        try {
            const res = await api.post<PermissionRequestRow>('users/permissions/requests', {
                code: applyTarget.code,
                note: values.note?.trim() || undefined
            })
            // 起始级由后端按「有没有非本人的直属负责人」定：1 = 先经组织上一级，2 = 直接等系统管理员
            message.success(res.data.stage === 1
                ? `已提交「${applyTarget.name}」的申请，先经直属上级、再由系统管理员在「审批中心」审批`
                : `已提交「${applyTarget.name}」的申请，待该系统的管理员在「审批中心」审批`)
            setApplyTarget(null)
            await afterChange()
        } catch (err) {
            // 409 already_pending / already_has / unknown_permission：后端文案直接透出
            message.error(errorMessage(err, '提交申请失败'))
        } finally {
            setApplying(false)
        }
    }

    const owned = new Set(profile?.permissionCodes ?? [])
    const pendingCodes = new Set(
        (my?.requests ?? [])
            .filter((r) => r.status === 'PENDING')
            .map((r) => r.permissionCode)
    )
    const pendingCount = pendingCodes.size

    // 按模块分组，与「概览」页签同一套排布（perm-module 卡片 + MODULE_LABEL/配色）
    const groups = useMemo(() => {
        const map = new Map<string, PermissionRow[]>()
        for (const row of catalog) {
            if (!map.has(row.module)) map.set(row.module, [])
            map.get(row.module)!.push(row)
        }
        return Array.from(map.entries())
    }, [catalog])

    const renderAction = (row: PermissionRow) => {
        if (owned.has(row.code)) {
            return (
                <Popconfirm
                    title="移除我的这条权限？"
                    description="即时生效；如来自角色授权，下次获得仍可能恢复"
                    okText="移除"
                    okButtonProps={{ danger: true }}
                    cancelText="取消"
                    onConfirm={() => revoke(row)}
                >
                    <Button size="small" danger loading={busyCode === row.code}>
                        删除
                    </Button>
                </Popconfirm>
            )
        }
        if (pendingCodes.has(row.code)) {
            // 悬停说明卡在哪一级、该谁批——比一个光秃秃的「审批中」有用得多
            const pending = (my?.requests ?? []).find(
                (r) => r.status === 'PENDING' && r.permissionCode === row.code
            )
            return (
                <Tooltip
                    title={
                        pending?.stage === 2
                            ? `待系统管理员审批${pending.sysAdminNames
                                ? `（${pending.sysAdminNames}）`
                                : '；该模块未配置系统管理员，需 ADMIN 审批'}`
                            : `待${pending?.leadNames || '直属上级'}审批，通过后转系统管理员`
                    }
                >
                    <Button size="small" disabled>审批中</Button>
                </Tooltip>
            )
        }
        // 不设 permission:request 门槛：申请恰恰是「还没有权限的人」要做的事，
        // 后端同口径不设卡（见 PermissionRequestController 类注释），按钮侧再挡一道等于把门焊死
        return (
            <Button
                size="small"
                type="primary"
                onClick={() => {
                    applyForm.resetFields()
                    setApplyTarget(row)
                }}
            >
                申请
            </Button>
        )
    }

    return (
        <>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 14 }}>
                这里是全部权限点。没有的可提交申请，在「审批中心」经两级审批
                （组织上一级 → 系统管理员）；已有的可移除（即时生效）。
                当前已有 {owned.size} 项
                {pendingCount > 0 ? `、${pendingCount} 项审批中` : ''}。
            </Typography.Paragraph>

            {loading ? (
                <div style={{ textAlign: 'center', padding: '60px 0' }}>
                    <Spin size="large" />
                </div>
            ) : loadErr ? (
                <Alert
                    type="error"
                    showIcon
                    message={loadErr}
                    action={<Button size="small" onClick={load}>重试</Button>}
                />
            ) : catalog.length === 0 ? (
                <Alert type="info" showIcon message="目录里还没有权限点数据，请联系管理员" />
            ) : (
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))',
                        gap: 14,
                        alignItems: 'start'
                    }}
                >
                    {groups.map(([module, list]) => (
                        <div key={module} className="perm-module">
                            <div className="perm-module-title">
                                <span
                                    className="perm-module-dot"
                                    style={{ background: MODULE_COLOR[module] ?? '#4f46e5' }}
                                />
                                {MODULE_LABEL[module] ?? module}
                                <span className="perm-module-count">{list.length} 项</span>
                            </div>
                            {list.map((row) => (
                                <div key={row.code} className="perm-item">
                                    <div className="perm-item-main">
                                        <div style={{ minWidth: 0 }}>
                                            <span className="perm-item-name">{row.name}</span>
                                            <span
                                                className="perm-item-code"
                                                style={{ marginLeft: 6, display: 'inline-block' }}
                                            >
                                                {row.code}
                                            </span>
                                        </div>
                                        <div style={{ flex: 'none' }}>{renderAction(row)}</div>
                                    </div>
                                    {row.description && (
                                        <span className="perm-item-desc">{row.description}</span>
                                    )}
                                </div>
                            ))}
                        </div>
                    ))}
                </div>
            )}

            <Modal
                title={applyTarget ? `申请权限：${applyTarget.name}` : '申请权限'}
                open={applyTarget !== null}
                onCancel={() => setApplyTarget(null)}
                onOk={() => applyForm.submit()}
                okText="提交申请"
                cancelText="取消"
                confirmLoading={applying}
                width={440}
                destroyOnClose
            >
                <Form
                    form={applyForm}
                    layout="vertical"
                    onFinish={submitApply}
                    style={{ marginTop: 16 }}
                    requiredMark={false}
                >
                    <Form.Item label="权限点">
                        {/* 只读回显申请对象：同名权限不少，得让人确认点的是哪一个 */}
                        <Input
                            value={applyTarget ? `${applyTarget.name}（${applyTarget.code}）` : ''}
                            disabled
                        />
                    </Form.Item>
                    <Form.Item name="note" label="申请理由" extra="选填；管理员在「审批中心」能看到">
                        <Input.TextArea
                            rows={3}
                            maxLength={255}
                            showCount
                            placeholder="说明为什么需要这个权限（选填）"
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </>
    )
}

/* ---------------- 角色管理 ---------------- */

function RolesTab({ onGotoMatrix }: { onGotoMatrix: () => void }) {
    const [roles, setRoles] = useState<RoleRow[]>([])
    const [loading, setLoading] = useState(true)
    const [createOpen, setCreateOpen] = useState(false)
    const [creating, setCreating] = useState(false)
    const [createForm] = Form.useForm<{ code: string; name: string; description: string }>()

    const [editTarget, setEditTarget] = useState<RoleRow | null>(null)
    const [editSaving, setEditSaving] = useState(false)
    const [editForm] = Form.useForm<{ name: string; description: string }>()

    const [membersRole, setMembersRole] = useState<RoleRow | null>(null)
    const [members, setMembers] = useState<RoleMember[]>([])
    const [membersLoading, setMembersLoading] = useState(false)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<RoleRow[]>('/admin/role-list')
            setRoles(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载角色失败'))
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        load()
    }, [load])

    const createRole = async (values: { code: string; name: string; description: string }) => {
        setCreating(true)
        try {
            await api.post('/admin/roles', values)
            message.success('角色已创建，去「授权矩阵」勾选权限')
            setCreateOpen(false)
            createForm.resetFields()
            await load()
        } catch (err) {
            message.error(errorMessage(err, '创建角色失败'))
        } finally {
            setCreating(false)
        }
    }

    const saveRole = async () => {
        if (!editTarget) return
        const values = editForm.getFieldsValue()
        setEditSaving(true)
        try {
            await api.put(`/admin/roles/${editTarget.code}`, values)
            message.success('角色已更新')
            setEditTarget(null)
            await load()
        } catch (err) {
            message.error(errorMessage(err, '更新角色失败'))
        } finally {
            setEditSaving(false)
        }
    }

    const deleteRole = async (code: string) => {
        try {
            await api.delete(`/admin/roles/${code}`)
            message.success(`角色 ${code} 已删除`)
            await load()
        } catch (err) {
            message.error(errorMessage(err, '删除角色失败'))
        }
    }

    const showMembers = async (role: RoleRow) => {
        setMembersRole(role)
        setMembersLoading(true)
        try {
            const res = await api.get<RoleMember[]>(`/admin/roles/${role.code}/members`)
            setMembers(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载成员失败'))
        } finally {
            setMembersLoading(false)
        }
    }

    const columns = [
        {
            title: '角色',
            dataIndex: 'code',
            key: 'code',
            width: 160,
            render: (code: string, row: RoleRow) => (
                <Space>
                    <Tag color={ROLE_TAG_COLOR[code] ?? 'geekblue'} style={{ marginInlineEnd: 0 }}>
                        {code}
                    </Tag>
                    {row.builtin && <Tag color="purple">内置</Tag>}
                </Space>
            )
        },
        {
            title: '名称',
            dataIndex: 'name',
            key: 'name',
            width: 120,
            render: (name: string) => <Typography.Text strong>{name}</Typography.Text>
        },
        {
            title: '描述',
            dataIndex: 'description',
            key: 'description',
            render: (value: string) =>
                value ? (
                    <Typography.Text type="secondary" ellipsis style={{ maxWidth: 360 }}>
                        {value}
                    </Typography.Text>
                ) : (
                    <Typography.Text type="secondary">-</Typography.Text>
                )
        },
        {
            title: '成员',
            dataIndex: 'userCount',
            key: 'userCount',
            width: 90,
            align: 'center' as const,
            render: (count: number) => (
                <Tag color={count > 0 ? 'blue' : 'default'}>{count} 人</Tag>
            )
        },
        {
            title: '操作',
            key: 'actions',
            width: 270,
            render: (_: unknown, row: RoleRow) => (
                <Space size={4}>
                    <Button size="small" onClick={() => showMembers(row)}>成员</Button>
                    <Button
                        size="small"
                        onClick={() => {
                            setEditTarget(row)
                            editForm.setFieldsValue({ name: row.name, description: row.description })
                        }}
                    >
                        编辑
                    </Button>
                    <Button size="small" onClick={onGotoMatrix}>授权</Button>
                    {!row.builtin && (
                        <Popconfirm
                            title={`删除角色 ${row.code}？`}
                            okText="删除"
                            okButtonProps={{ danger: true }}
                            cancelText="取消"
                            onConfirm={() => deleteRole(row.code)}
                        >
                            <Button size="small" danger>删除</Button>
                        </Popconfirm>
                    )}
                </Space>
            )
        }
    ]

    return (
        <>
            <Card
                className="table-card"
                title={`角色列表 · 共 ${roles.length} 个角色`}
                extra={
                    <Space>
                        <Button type="primary" onClick={() => setCreateOpen(true)}>新建角色</Button>
                        <Button onClick={load}>刷新</Button>
                    </Space>
                }
            >
                <Table
                    rowKey="code"
                    columns={columns}
                    dataSource={roles}
                    loading={loading}
                    pagination={false}
                    size="middle"
                />
            </Card>
            <Typography.Text
                type="secondary"
                style={{ fontSize: 12, display: 'block', marginTop: 10 }}
            >
                内置角色不可删除；仍有成员的角色删除会被后端拒绝（先去用户管理移除成员）。
            </Typography.Text>

            {/* 新建 */}
            <Modal
                title="新建角色"
                open={createOpen}
                onCancel={() => setCreateOpen(false)}
                onOk={() => createForm.submit()}
                okText="创建"
                cancelText="取消"
                confirmLoading={creating}
                width={440}
                destroyOnClose
            >
                <Form
                    form={createForm}
                    layout="vertical"
                    onFinish={createRole}
                    style={{ marginTop: 16 }}
                    requiredMark={false}
                >
                    <Form.Item
                        name="code"
                        label="角色编码"
                        rules={[
                            { required: true, message: '编码必填' },
                            {
                                pattern: /^[A-Z][A-Z0-9_]{1,31}$/,
                                message: '2-32 位大写字母/数字/下划线，字母开头'
                            }
                        ]}
                        extra="创建后到「授权矩阵」勾选权限点"
                    >
                        <Input placeholder="如：OPS" allowClear />
                    </Form.Item>
                    <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '名称必填' }]}>
                        <Input placeholder="如：运营" allowClear maxLength={32} />
                    </Form.Item>
                    <Form.Item name="description" label="描述">
                        <Input placeholder="这个角色负责什么" allowClear maxLength={120} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 编辑 */}
            <Modal
                title={editTarget ? `编辑角色 ${editTarget.code}` : '编辑角色'}
                open={!!editTarget}
                onCancel={() => setEditTarget(null)}
                onOk={saveRole}
                okText="保存"
                cancelText="取消"
                confirmLoading={editSaving}
                width={440}
                destroyOnClose
            >
                <Form form={editForm} layout="vertical" style={{ marginTop: 16 }} requiredMark={false}>
                    <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '名称必填' }]}>
                        <Input allowClear maxLength={32} />
                    </Form.Item>
                    <Form.Item name="description" label="描述">
                        <Input allowClear maxLength={120} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 成员 */}
            <Drawer
                title={membersRole ? `${membersRole.code} 的成员（${members.length}）` : '成员'}
                open={!!membersRole}
                onClose={() => setMembersRole(null)}
                width={420}
            >
                <Spin spinning={membersLoading}>
                    {members.length === 0 && !membersLoading ? (
                        <Typography.Text type="secondary">该角色暂无成员</Typography.Text>
                    ) : (
                        <Space direction="vertical" size={10} style={{ width: '100%' }}>
                            {members.map((member) => (
                                <div key={member.id} className="user-cell" style={{
                                    padding: '10px 14px',
                                    borderRadius: 10,
                                    background: '#f9fafb',
                                    border: '1px solid #eaecf0'
                                }}>
                                    <div className="user-cell-info">
                                        <div className="user-cell-name">
                                            {member.nickname || member.account}
                                        </div>
                                        <div className="user-cell-sub">
                                            {member.account} · 注册于 {member.createdAt}
                                        </div>
                                    </div>
                                    <Tag color={member.status === 1 ? 'success' : 'error'}>
                                        {member.status === 1 ? '正常' : '停用'}
                                    </Tag>
                                </div>
                            ))}
                        </Space>
                    )}
                </Spin>
            </Drawer>
        </>
    )
}

/* ---------------- 团队树（「上级团队」下拉与组织架构页共用） ---------------- */

/** 上级团队下拉用的树节点；value 即 teams.id，null 语义由「不选」表达 */
interface TeamNode {
    value: number
    title: string
    disabled?: boolean
    children?: TeamNode[]
}

/**
 * 把**扁平**的团队列表装配成树。
 *
 * <p>只负责装配，不排序、不算权限——那些后端已经给好，前端再算一遍只会和
 * 后端口径漂移。成环的脏数据在这里不会死循环：递归从 `parentId === null`
 * 的根往下走，环里的节点永远走不到，它们只是不显示；后端 `requireValidParent`
 * 才是防线。</p>
 *
 * <p>{@code disableSubtreeOf} 用于编辑某团队时把「它自己 + 全部下级」标灰：
 * 后端虽然会拦成 409 team_cycle，但下拉里直接点不动比弹一个错误更省事。</p>
 */
function buildTeamTree(teams: TeamRow[], disableSubtreeOf?: number): TeamNode[] {
    const childrenOf = new Map<number | null, TeamRow[]>()
    for (const team of teams) {
        const key = team.parentId ?? null
        if (!childrenOf.has(key)) childrenOf.set(key, [])
        childrenOf.get(key)!.push(team)
    }
    const disabled = new Set<number>()
    if (disableSubtreeOf != null) {
        const walk = (id: number) => {
            disabled.add(id)
            for (const child of childrenOf.get(id) ?? []) walk(child.id)
        }
        walk(disableSubtreeOf)
    }
    const toNode = (team: TeamRow): TeamNode => ({
        value: team.id,
        title: team.name,
        disabled: disabled.has(team.id),
        children: (childrenOf.get(team.id) ?? []).map(toNode)
    })
    return (childrenOf.get(null) ?? []).map(toNode)
}

/* ---------------- 团队 ---------------- */

/**
 * 团队：授权仍只在「授权矩阵」一处，这里不提供任何勾权限的入口——
 * 只回答三个问题：谁和谁一组、谁带队、这一组实际拥有多少权限。
 *
 * 负责人一定是团队成员，所以「团队有效权限」与「负责人有效权限」是同一份：
 * 都等于在职成员<b>自身</b>权限的并集。成员与权限因此放在同一个抽屉里看，
 * 而不是拆成两个页面让人自己在脑子里求并集。
 *
 * <p><b>分两套口径（42 号）</b>：canManage（role:manage）看全量、可增删改；
 * 普通成员只读——数据走 {@code /users/me/joined-teams}，只有自己所在的团队，
 * 没有新建/编辑/删除/加人，抽屉里也只剩成员名单（有效权限的明细随
 * {@code /admin/permissions} 一起锁在 role:manage 后面，不拉也不渲染）。</p>
 */
function TeamsTab({ canManage }: { canManage: boolean }) {
    const { profile } = useAuth()
    const [teams, setTeams] = useState<TeamRow[]>([])
    const [loading, setLoading] = useState(true)
    const [permRows, setPermRows] = useState<PermissionRow[]>([])
    const [users, setUsers] = useState<UserRow[]>([])

    const [createOpen, setCreateOpen] = useState(false)
    const [creating, setCreating] = useState(false)
    const [createForm] = Form.useForm<{ name: string; description: string; parentId: number | null }>()

    const [editTarget, setEditTarget] = useState<TeamRow | null>(null)
    const [editSaving, setEditSaving] = useState(false)
    const [editForm] = Form.useForm<{ name: string; description: string; parentId: number | null }>()

    // 存 id 而不是整行对象：每次操作后列表会刷新，对象引用会变成旧数据，
    // id 则能自动重新匹配到刷新后的那一行，抽屉里不会残留过期成员
    const [detailId, setDetailId] = useState<number | null>(null)
    const detail = teams.find((t) => t.id === detailId) ?? null

    const [addSaving, setAddSaving] = useState(false)
    const [addForm] = Form.useForm<{ userId: number; lead: boolean }>()

    const load = useCallback(async () => {
        setLoading(true)
        try {
            // 管理者：全量，接口在 role:manage 后面；普通成员：只读口径 = 我加入的
            // 团队，行级边界在服务端（userId 取自网关身份头，不收入参）
            const res = await api.get<TeamRow[]>(
                canManage ? '/admin/teams' : '/users/me/joined-teams'
            )
            setTeams(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载团队失败'))
        } finally {
            setLoading(false)
        }
    }, [canManage])

    useEffect(() => {
        load()
        if (!canManage) {
            // 这两个接口都在 role:manage 后面：普通成员拉了只会白吃 403，
            // 且他的界面上没有用到它们的地方（添加成员的下拉、权限码翻译）
            return
        }
        // 权限点名称：把 code 渲染成人话；用户列表：「添加成员」的下拉数据源
        api.get<PermissionRow[]>('/admin/permissions')
            .then((r) => setPermRows(r.data))
            .catch(() => {})
        api.get<PageResult<UserRow>>('/admin/users', { params: { page: 1, size: 100 } })
            .then((r) => setUsers(r.data.list))
            .catch(() => {})
    }, [load, canManage])

    const createTeam = async (values: { name: string; description: string; parentId: number | null }) => {
        setCreating(true)
        try {
            await api.post('/admin/teams', values)
            message.success('团队已创建，去添加成员')
            setCreateOpen(false)
            createForm.resetFields()
            await load()
        } catch (err) {
            message.error(errorMessage(err, '创建团队失败'))
        } finally {
            setCreating(false)
        }
    }

    const saveTeam = async () => {
        if (!editTarget) return
        const values = editForm.getFieldsValue()
        setEditSaving(true)
        try {
            await api.put(`/admin/teams/${editTarget.id}`, values)
            message.success('团队已更新')
            setEditTarget(null)
            await load()
        } catch (err) {
            message.error(errorMessage(err, '更新团队失败'))
        } finally {
            setEditSaving(false)
        }
    }

    const removeTeam = async (row: TeamRow) => {
        try {
            await api.delete(`/admin/teams/${row.id}`)
            message.success(`团队「${row.name}」已删除`)
            if (detailId === row.id) setDetailId(null)
            await load()
        } catch (err) {
            message.error(errorMessage(err, '删除团队失败'))
        }
    }

    const addMember = async (values: { userId: number; lead: boolean }) => {
        if (!detail) return
        setAddSaving(true)
        try {
            await api.post(`/admin/teams/${detail.id}/members`, values)
            message.success(values.lead ? '已加入并设为负责人' : '成员已加入')
            addForm.resetFields()
            await load()
        } catch (err) {
            // 重名团队 / 账号已禁用这类后端文案直接透出
            message.error(errorMessage(err, '添加成员失败'))
        } finally {
            setAddSaving(false)
        }
    }

    const markLead = async (row: TeamRow, userId: number) => {
        try {
            // 走专用的 lead 接口而不是复用「添加成员」：此人已在队里，
            // 后者会走 upsert 语义，读代码的人无从判断它会不会顺带改别的字段
            await api.put(`/admin/teams/${row.id}/lead`, { userId })
            message.success('负责人已更新，其有效权限即该队子树的并集')
            await load()
        } catch (err) {
            message.error(errorMessage(err, '设置负责人失败'))
        }
    }

    const dropMember = async (row: TeamRow, userId: number) => {
        try {
            await api.delete(`/admin/teams/${row.id}/members/${userId}`)
            message.success('已移出团队')
            await load()
        } catch (err) {
            message.error(errorMessage(err, '移除成员失败'))
        }
    }

    const columns = [
        {
            title: '团队',
            dataIndex: 'name',
            key: 'name',
            width: 170,
            render: (name: string, row: TeamRow) => (
                <Space size={6}>
                    <Typography.Text strong>{name}</Typography.Text>
                    <Typography.Text type="secondary">#{row.id}</Typography.Text>
                </Space>
            )
        },
        // 上级链是管理视角的信息：普通成员的列表里只有自己所在的团队，父团队
        // 多半不在其中，渲染出来只是一串 #id，索性不给这一列
        ...(canManage
            ? [
                {
                    title: '上级',
                    key: 'parent',
                    width: 150,
                    render: (_: unknown, row: TeamRow) => {
                        if (row.parentId === null) {
                            return <Tag>最顶层</Tag>
                        }
                        const parent = teams.find((t) => t.id === row.parentId)
                        return <Tag color="blue">{parent?.name ?? `#${row.parentId}`}</Tag>
                    }
                }
            ]
            : []),
        {
            title: '负责人',
            key: 'lead',
            width: 200,
            render: (_: unknown, row: TeamRow) => {
                const lead = row.members.find((m) => m.lead)
                return lead ? (
                    <Space size={5}>
                        <CrownOutlined style={{ color: '#f59e0b' }} />
                        <Typography.Text>{lead.nickname}</Typography.Text>
                        <Typography.Text type="secondary">{lead.account}</Typography.Text>
                    </Space>
                ) : (
                    <Tag>未指定</Tag>
                )
            }
        },
        {
            title: '成员',
            key: 'members',
            width: 80,
            render: (_: unknown, row: TeamRow) => `${row.members.length} 人`
        },
        ...(canManage
            ? [
                {
                    title: '有效权限',
                    key: 'effective',
                    width: 110,
                    // 整棵子树在职成员的并集，也正是负责人的有效权限
                    render: (_: unknown, row: TeamRow) => (
                        <Tag color="green">{row.effectivePermissions.length} 项</Tag>
                    )
                }
            ]
            : [
                {
                    // 只读视图里最相关的一列：我在这个队是负责人还是成员
                    title: '我在该队',
                    key: 'mine',
                    width: 100,
                    render: (_: unknown, row: TeamRow) =>
                        row.members.find((m) => m.userId === profile?.id)?.lead ? (
                            <Tag color="gold">负责人</Tag>
                        ) : (
                            <Tag>成员</Tag>
                        )
                }
            ]),
        {
            title: '说明',
            dataIndex: 'description',
            key: 'description',
            render: (value: string) =>
                value ? (
                    <Typography.Text type="secondary" ellipsis style={{ maxWidth: 300 }}>
                        {value}
                    </Typography.Text>
                ) : (
                    <Typography.Text type="secondary">-</Typography.Text>
                )
        },
        {
            title: '操作',
            key: 'action',
            width: canManage ? 240 : 90,
            render: (_: unknown, row: TeamRow) =>
                canManage ? (
                    <Space size={8}>
                        <Button size="small" onClick={() => setDetailId(row.id)}>
                            成员与权限
                        </Button>
                        <Button
                            size="small"
                            onClick={() => {
                                editForm.setFieldsValue({
                                    name: row.name,
                                    description: row.description,
                                    parentId: row.parentId
                                })
                                setEditTarget(row)
                            }}
                        >
                            编辑
                        </Button>
                        <Popconfirm
                            title="删除这个团队？"
                            description="只会解除成员关系，不影响任何人的角色与权限。"
                            okText="删除"
                            cancelText="取消"
                            onConfirm={() => removeTeam(row)}
                        >
                            <Button size="small" danger>
                                删除
                            </Button>
                        </Popconfirm>
                    </Space>
                ) : (
                    // 只读：唯一入口是看成员名单，抽屉里的管理控件随 canManage 隐藏
                    <Button size="small" onClick={() => setDetailId(row.id)}>
                        成员
                    </Button>
                )
        }
    ]

    const memberColumns = [
        {
            title: '成员',
            key: 'member',
            render: (_: unknown, m: TeamMemberRow) => (
                <Space size={5}>
                    {m.lead && <CrownOutlined style={{ color: '#f59e0b' }} />}
                    <Typography.Text strong>{m.nickname}</Typography.Text>
                    <Typography.Text type="secondary">{m.account}</Typography.Text>
                    {m.lead && <Tag color="gold">负责人</Tag>}
                    {m.status !== 1 && <Tag color="red">已禁用</Tag>}
                </Space>
            )
        },
        // 自身权限的计数与成员的增减都是管理视角：普通成员看名单就够了，
        // 权限明细连同 /admin/permissions 一起锁在 role:manage 后面
        ...(canManage
            ? [
                {
                    title: '自身权限',
                    key: 'own',
                    width: 110,
                    render: (_: unknown, m: TeamMemberRow) => `${m.ownPermissions.length} 项`
                },
                {
                    title: '操作',
                    key: 'action',
                    width: 180,
                    render: (_: unknown, m: TeamMemberRow) =>
                        detail ? (
                            <Space size={8}>
                                {!m.lead && (
                                    <Button size="small" onClick={() => markLead(detail, m.userId)}>
                                        设为负责人
                                    </Button>
                                )}
                                <Popconfirm
                                    title="移出团队？"
                                    okText="移出"
                                    cancelText="取消"
                                    onConfirm={() => dropMember(detail, m.userId)}
                                >
                                    <Button size="small" danger>
                                        移出
                                    </Button>
                                </Popconfirm>
                            </Space>
                        ) : null
                }
            ]
            : [])
    ]

    // 有效权限按模块分组展示，与「权限点」页签同一套叫法，避免两处术语对不上
    const memberOptions = users
        .filter((u) => !(detail?.members ?? []).some((m) => m.userId === u.id))
        .map((u) => ({
            value: u.id,
            label: `${u.nickname} · ${u.account}`
        }))

    return (
        <>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
                <Typography.Text type="secondary">
                    {canManage ? (
                        <>
                            团队不参与授权本身——权限点仍只勾给角色。团队有效权限 = <b>整棵子树</b>
                            在职成员的并集（含下级团队），负责人自动拥有这一份。建好层级后去「组织架构」看全貌。
                        </>
                    ) : (
                        <>
                            这里只显示<b>你所在的团队</b>（只读）。团队的增删改、加人减人
                            由管理员在权限中心完成。
                        </>
                    )}
                </Typography.Text>
                {canManage && (
                    <Button type="primary" onClick={() => setCreateOpen(true)}>
                        新建团队
                    </Button>
                )}
            </div>

            <Table<TeamRow>
                rowKey="id"
                loading={loading}
                columns={columns}
                dataSource={teams}
                pagination={false}
                locale={{
                    emptyText: (
                        <Typography.Text type="secondary">
                            {canManage
                                ? '还没有团队，点右上角「新建团队」开始'
                                : '你还没有加入任何团队'}
                        </Typography.Text>
                    )
                }}
            />

            {/* 新建 / 编辑 */}
            <Modal
                title="新建团队"
                open={createOpen}
                onCancel={() => setCreateOpen(false)}
                onOk={() => createForm.submit()}
                okText="创建"
                cancelText="取消"
                confirmLoading={creating}
                width={440}
                destroyOnClose
            >
                <Form
                    form={createForm}
                    layout="vertical"
                    onFinish={createTeam}
                    style={{ marginTop: 16 }}
                >
                    <Form.Item
                        name="name"
                        label="团队名称"
                        rules={[
                            { required: true, message: '团队名称不能为空' },
                            { max: 64, message: '团队名称最长 64 字' }
                        ]}
                    >
                        <Input maxLength={64} placeholder="如：华东交付组" />
                    </Form.Item>
                    <Form.Item name="description" label="说明">
                        <Input maxLength={255} placeholder="这个团队负责什么" />
                    </Form.Item>
                    <Form.Item
                        name="parentId"
                        label="上级团队"
                        initialValue={null}
                        tooltip="留空即挂在最顶层。父团队的有效权限 = 整棵子树在职成员权限的并集"
                    >
                        <TreeSelect
                            allowClear
                            showSearch
                            treeNodeFilterProp="title"
                            treeDefaultExpandAll
                            treeData={buildTeamTree(teams)}
                            placeholder="最顶层（不设上级）"
                        />
                    </Form.Item>
                </Form>
            </Modal>

            <Modal
                title="编辑团队"
                open={editTarget !== null}
                onCancel={() => setEditTarget(null)}
                onOk={() => saveTeam()}
                okText="保存"
                cancelText="取消"
                confirmLoading={editSaving}
                width={440}
                destroyOnClose
            >
                <Form form={editForm} layout="vertical" style={{ marginTop: 16 }}>
                    <Form.Item
                        name="name"
                        label="团队名称"
                        rules={[
                            { required: true, message: '团队名称不能为空' },
                            { max: 64, message: '团队名称最长 64 字' }
                        ]}
                    >
                        <Input maxLength={64} />
                    </Form.Item>
                    <Form.Item name="description" label="说明">
                        <Input maxLength={255} />
                    </Form.Item>
                    <Form.Item
                        name="parentId"
                        label="上级团队"
                        tooltip="把自己和自己的下级标灰了——挂到下级下面会成环，后端也会拦"
                    >
                        <TreeSelect
                            allowClear
                            showSearch
                            treeNodeFilterProp="title"
                            treeDefaultExpandAll
                            treeData={buildTeamTree(teams, editTarget?.id)}
                            placeholder="最顶层（不设上级）"
                        />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 成员 + 有效权限：放在同一个抽屉里，避免把「并集」这件事丢给读者。
                普通成员的只读形态只到成员名单——添加成员与权限明细都随 canManage 收起 */}
            <Drawer
                title={
                    detail
                        ? canManage
                            ? `「${detail.name}」的成员与有效权限`
                            : `「${detail.name}」的成员`
                        : '成员与有效权限'
                }
                open={detail !== null}
                onClose={() => setDetailId(null)}
                width={560}
            >
                {detail && (
                    <>
                        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
                            {detail.description || '（无说明）'}
                        </Typography.Paragraph>

                        <Typography.Title level={5}>成员（{detail.members.length}）</Typography.Title>
                        <Table
                            rowKey="userId"
                            columns={memberColumns}
                            dataSource={detail.members}
                            pagination={false}
                            size="small"
                            locale={{
                                emptyText: canManage ? '还没有成员，先在下方添加' : '暂无成员'
                            }}
                        />

                        {canManage && (
                            <>
                                <Form
                                    form={addForm}
                                    layout="inline"
                                    onFinish={addMember}
                                    style={{ marginTop: 16, rowGap: 12 }}
                                >
                                    <Form.Item
                                        name="userId"
                                        rules={[{ required: true, message: '请选择用户' }]}
                                    >
                                        <Select
                                            showSearch
                                            optionFilterProp="label"
                                            style={{ width: 260 }}
                                            placeholder="搜索用户（账号/花名）"
                                            options={memberOptions}
                                        />
                                    </Form.Item>
                                    <Form.Item name="lead" valuePropName="checked" initialValue={false}>
                                        <Switch checkedChildren="负责人" unCheckedChildren="成员" />
                                    </Form.Item>
                                    <Form.Item>
                                        <Button type="primary" htmlType="submit" loading={addSaving}>
                                            添加
                                        </Button>
                                    </Form.Item>
                                </Form>

                                <Typography.Title level={5} style={{ marginTop: 24 }}>
                                    有效权限（{detail.effectivePermissions.length}）
                                </Typography.Title>
                                <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                                    <b>整棵子树</b>在职成员自身权限的并集（含下级团队）；
                                    负责人自动拥有这一份，不需要单独勾选。
                                </Typography.Paragraph>
                                <PermissionGroups
                                    codes={detail.effectivePermissions}
                                    permRows={permRows}
                                    emptyText="这棵子树的成员目前没有任何权限，去「授权矩阵」给他们的角色勾上。"
                                />
                            </>
                        )}
                    </>
                )}
            </Drawer>
        </>
    )
}

/* ---------------- 组织架构 ---------------- */

/**
 * 组织架构页签：这里只取数，渲染整个交给 OrgExplorer。
 *
 * <p>抽出去是因为「我的团队」页要用同一棵树——两处各写一遍，节点口径迟早会漂移，
 * 然后就出现「权限中心看到 5 项、我的团队看到 4 项」这种对不上账的问题。</p>
 */
function OrgTab() {
    const [teams, setTeams] = useState<TeamRow[]>([])
    const [loading, setLoading] = useState(true)
    const [permRows, setPermRows] = useState<PermissionRow[]>([])

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<TeamRow[]>('/admin/teams')
            setTeams(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载组织架构失败'))
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        load()
        api.get<PermissionRow[]>('/admin/permissions')
            .then((r) => setPermRows(r.data))
            .catch(() => {})
    }, [load])

    return (
        <OrgExplorer
            teams={teams}
            loading={loading}
            permRows={permRows}
            editable
            onChanged={load}
            rootHint="未加入任何团队的成员不在这棵树上（他们只有角色权限，去「授权矩阵」看）。"
        />
    )
}

/* ---------------- 授权矩阵 ---------------- */

function MatrixTab({ onSaved }: { onSaved?: () => void }) {
    const [matrix, setMatrix] = useState<RoleMatrix | null>(null)
    const [loading, setLoading] = useState(true)
    const [savingRole, setSavingRole] = useState<string | null>(null)
    const [draft, setDraft] = useState<Record<string, string[]>>({})

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<RoleMatrix>('/admin/role-matrix')
            setMatrix(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载权限矩阵失败'))
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        load()
    }, [load])

    // 矩阵拉到后初始化草稿（已有本地未保存的勾选不覆盖）
    useEffect(() => {
        if (!matrix) return
        setDraft((prev) => {
            const next = { ...prev }
            for (const role of matrix.roles) {
                if (!next[role.code]) {
                    next[role.code] = [...(matrix.granted[role.code] ?? [])]
                }
            }
            return next
        })
    }, [matrix])

    const grouped = useMemo(() => {
        if (!matrix) return []
        const map = new Map<string, PermissionRow[]>()
        for (const permission of matrix.permissions) {
            if (!map.has(permission.module)) map.set(permission.module, [])
            map.get(permission.module)!.push(permission)
        }
        return Array.from(map.entries()).map(([module, permissions]) => ({ module, permissions }))
    }, [matrix])

    const isGranted = (role: string, permission: string) =>
        (draft[role] ?? matrix?.granted[role] ?? []).includes(permission)

    const toggle = (role: string, permission: string, checked: boolean) => {
        setDraft((prev) => {
            const current = new Set(prev[role] ?? matrix?.granted[role] ?? [])
            if (checked) current.add(permission)
            else current.delete(permission)
            return { ...prev, [role]: Array.from(current) }
        })
    }

    const isDirty = (role: string) => {
        const current = draft[role] ?? []
        const saved = matrix?.granted[role] ?? []
        return current.length !== saved.length || current.some((code) => !saved.includes(code))
    }

    const saveRole = async (role: string) => {
        setSavingRole(role)
        try {
            await api.put(`/admin/roles/${role}/permissions`, { permissions: draft[role] ?? [] })
            message.success(`角色 ${role} 的权限已保存`)
            await load()
            onSaved?.()
            setDraft((prev) => {
                const next = { ...prev }
                delete next[role]
                return next
            })
        } catch (err) {
            message.error(errorMessage(err, '保存失败'))
        } finally {
            setSavingRole(null)
        }
    }

    if (loading && !matrix) {
        return (
            <div style={{ textAlign: 'center', padding: '80px 0' }}>
                <Spin size="large" />
            </div>
        )
    }
    if (!matrix) return null

    return (
        <Card
            className="table-card"
            title="角色 × 权限点授权矩阵"
            extra={<Button onClick={load}>刷新</Button>}
            styles={{ body: { padding: 20 } }}
        >
            <div className="perm-matrix-wrap">
                <table className="perm-matrix">
                    <thead>
                        <tr>
                            <th>
                                权限点
                                <span className="perm-role-name">
                                    按模块分组，共 {matrix.permissions.length} 项
                                </span>
                            </th>
                            {matrix.roles.map((role) => (
                                <th key={role.code} className={role.code === 'ADMIN' ? 'perm-col-admin' : undefined}>
                                    <Tag color={ROLE_TAG_COLOR[role.code] ?? 'geekblue'} style={{ marginInlineEnd: 0 }}>
                                        {role.code}
                                    </Tag>
                                    <span className="perm-role-name">
                                        {role.name} · {role.userCount} 人
                                    </span>
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {grouped.map(({ module, permissions }) => (
                            <Fragment key={module}>
                                <tr className="perm-group-row">
                                    <td colSpan={matrix.roles.length + 1}>
                                        {MODULE_LABEL[module] ?? module}
                                    </td>
                                </tr>
                                {permissions.map((permission) => (
                                    <tr key={permission.code}>
                                        <td>
                                            <div className="perm-cell-name">{permission.name}</div>
                                            <div className="perm-cell-code">{permission.code}</div>
                                        </td>
                                        {matrix.roles.map((role) => (
                                            <td
                                                key={role.code}
                                                className={role.code === 'ADMIN' ? 'perm-col-admin' : undefined}
                                            >
                                                <Checkbox
                                                    disabled={role.code === 'ADMIN'}
                                                    checked={isGranted(role.code, permission.code)}
                                                    onChange={(e) =>
                                                        toggle(role.code, permission.code, e.target.checked)
                                                    }
                                                />
                                            </td>
                                        ))}
                                    </tr>
                                ))}
                            </Fragment>
                        ))}
                    </tbody>
                    <tfoot>
                        <tr>
                            <td>勾选后点右侧按钮保存，用户刷新页面即生效</td>
                            {matrix.roles.map((role) => (
                                <td key={role.code} className={role.code === 'ADMIN' ? 'perm-col-admin' : undefined}>
                                    {role.code === 'ADMIN' ? (
                                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                            固定全量
                                        </Typography.Text>
                                    ) : (
                                        <Button
                                            size="small"
                                            type={isDirty(role.code) ? 'primary' : 'default'}
                                            loading={savingRole === role.code}
                                            disabled={!draft[role.code]}
                                            onClick={() => saveRole(role.code)}
                                        >
                                            {isDirty(role.code) ? '保存*' : '保存'}
                                        </Button>
                                    )}
                                </td>
                            ))}
                        </tr>
                    </tfoot>
                </table>
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
                ADMIN 列固定勾满且不可编辑（后端代码兜底）；改动只影响对应角色的成员。
            </Typography.Text>
        </Card>
    )
}

/* ---------------- 系统管理员（41 号：权限申请第 2 级审批人名册） ---------------- */

/** 一个系统（权限目录的模块）及其管理员；接口响应是全量模块列表（管理员可为空） */
interface ModuleAdmins {
    module: string
    admins: { userId: number; account: string; nickname: string }[]
}

/**
 * 系统管理员页签：配置「谁批某个系统（模块）的权限申请」——权限申请第 2 级的
 * 审批人。第 1 级是申请人所在团队的负责人（组织链路，从 team_members.is_lead
 * 来，不在这配）；模块没配管理员时该级只能等 ADMIN 终审，列表空态会写明这点。
 */
function SystemAdminsTab() {
    const [data, setData] = useState<ModuleAdmins[]>([])
    const [loading, setLoading] = useState(true)
    // 用户池：第一次打开添加弹窗才拉（与组织架构批量拉人同策略），null=还没拉过
    const [pool, setPool] = useState<UserRow[] | null>(null)
    const [adding, setAdding] = useState<string | null>(null)
    const [picked, setPicked] = useState<number[]>([])
    const [saving, setSaving] = useState(false)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<ModuleAdmins[]>('/admin/system-admins')
            setData(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载系统管理员失败'))
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        load()
    }, [load])

    const openAdd = async (module: string) => {
        setPicked([])
        setAdding(module)
        if (pool !== null) return
        try {
            const res = await api.get<PageResult<UserRow>>('/admin/users', {
                params: { page: 1, size: 500 }
            })
            setPool(res.data.list)
        } catch (err) {
            message.error(errorMessage(err, '加载用户列表失败'))
            setAdding(null)
        }
    }

    const submitAdd = async () => {
        if (!adding || picked.length === 0) return
        setSaving(true)
        // 接口按单用户设计（INSERT IGNORE 幂等），逐个提交、best-effort 汇总失败原因
        let failed = 0
        for (const userId of picked) {
            try {
                const res = await api.post<ModuleAdmins[]>('/admin/system-admins', {
                    module: adding,
                    userId: String(userId)
                })
                setData(res.data)
            } catch {
                failed += 1
            }
        }
        setSaving(false)
        setAdding(null)
        if (failed > 0) {
            message.warning(`已添加 ${picked.length - failed} 人，${failed} 人失败`)
        } else {
            message.success(`已为「${MODULE_LABEL[adding] ?? adding}」添加 ${picked.length} 名系统管理员`)
        }
    }

    const remove = async (module: string, userId: number) => {
        try {
            const res = await api.delete<ModuleAdmins[]>(`/admin/system-admins/${module}/${userId}`)
            setData(res.data)
            message.success('已移除')
        } catch (err) {
            message.error(errorMessage(err, '移除失败'))
        }
    }

    const columns = [
        {
            title: '系统（模块）',
            key: 'module',
            width: 180,
            render: (_: unknown, row: ModuleAdmins) => (
                <Tag color={MODULE_COLOR[row.module] ?? '#4f46e5'} style={{ marginInlineEnd: 0 }}>
                    {MODULE_LABEL[row.module] ?? row.module}
                </Tag>
            )
        },
        {
            title: '系统管理员（权限申请第 2 级审批人）',
            key: 'admins',
            render: (_: unknown, row: ModuleAdmins) =>
                row.admins.length === 0 ? (
                    <span style={{ color: '#fa8c16' }}>
                        未配置——该系统权限的申请只能由 ADMIN 终审
                    </span>
                ) : (
                    <Space size={[4, 8]} wrap>
                        {row.admins.map((admin) => (
                            <Popconfirm
                                key={admin.userId}
                                title={`移除 ${admin.nickname} 的「${MODULE_LABEL[row.module] ?? row.module}」系统管理员身份？`}
                                okText="移除"
                                okButtonProps={{ danger: true }}
                                cancelText="取消"
                                onConfirm={() => remove(row.module, admin.userId)}
                            >
                                <Tag style={{ marginInlineEnd: 0, cursor: 'pointer' }}>
                                    {admin.nickname}
                                    <span style={{ color: '#98a2b3', marginLeft: 6 }}>{admin.account}</span>
                                </Tag>
                            </Popconfirm>
                        ))}
                    </Space>
                )
        },
        {
            title: '操作',
            key: 'action',
            width: 110,
            render: (_: unknown, row: ModuleAdmins) => (
                <Button size="small" type="primary" onClick={() => openAdd(row.module)}>
                    添加
                </Button>
            )
        }
    ]

    const addingModule = data.find((item) => item.module === adding)
    const moduleLabel = adding ? (MODULE_LABEL[adding] ?? adding) : ''

    return (
        <Card className="table-card">
            <Typography.Paragraph type="secondary" style={{ marginBottom: 14 }}>
                权限申请走两级审批：第 1 级是申请人所在团队的负责人（组织链路，自动生效），
                第 2 级由这里配置的系统管理员审批；ADMIN 在任意一级可一次批整单。
            </Typography.Paragraph>

            <Table<ModuleAdmins>
                rowKey="module"
                loading={loading}
                columns={columns}
                dataSource={data}
                pagination={false}
            />

            <Modal
                title={`为「${moduleLabel}」添加系统管理员`}
                open={adding !== null}
                onCancel={() => setAdding(null)}
                onOk={() => submitAdd()}
                okText="添加"
                cancelText="取消"
                confirmLoading={saving}
                okButtonProps={{ disabled: picked.length === 0 }}
                width={440}
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginTop: 16 }}
                    message="系统管理员 = 权限申请第 2 级的审批人"
                    description="模块未配置管理员时，该系统权限的申请只能由 ADMIN 终审。"
                />
                <Form layout="vertical" style={{ marginTop: 16 }}>
                    <Form.Item label="选择用户（可多选，已是该模块管理员的不列出）">
                        <Select
                            mode="multiple"
                            value={picked}
                            onChange={setPicked}
                            placeholder="按花名或手机号搜索"
                            optionFilterProp="label"
                            options={(pool ?? [])
                                .filter((user) =>
                                    !(addingModule?.admins ?? []).some((admin) => admin.userId === user.id)
                                )
                                .map((user) => ({
                                    value: user.id,
                                    label: `${user.nickname}（${user.account}）`
                                }))}
                            style={{ width: '100%' }}
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </Card>
    )
}
