import { CheckCircleOutlined, CloseCircleOutlined, StopOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Radio, Space, Table, Tabs, Tag, Tooltip, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'
import { NicknameRequestRow, PageResult } from '../api/types'
import { useAuth } from '../auth/AuthContext'
// 模块名与配色全站一份，权限申请列表的「模块」列直接用它，避免又一套叫法
import { MODULE_COLOR, MODULE_LABEL } from '../components/permission-shared'

const STATUS_META: Record<string, { color: string; label: string }> = {
    PENDING: { color: 'orange', label: '待审批' },
    APPROVED: { color: 'green', label: '已通过' },
    REJECTED: { color: 'red', label: '已驳回' }
}

// 权限申请的口径是「批准 / 驳回」（花名那边叫「通过」），分开两份避免同页两种叫法
const PERM_STATUS_META: Record<string, { color: string; label: string }> = {
    PENDING: { color: 'orange', label: '待审批' },
    APPROVED: { color: 'green', label: '已批准' },
    REJECTED: { color: 'red', label: '已驳回' }
}

/*
 * 审批中心：花名变更申请 + 权限申请两类单子，页内按审批权显隐成 Tabs。
 * 花名变更走 nickname:review、权限申请走 permission:review（App.tsx 用 anyOf
 * 守卫整页，任一命中即可进入），所以只持有一种审批权的人看到的是单页、没有 tab 栏。
 * permission:review 由 resolve 派生给团队负责人与系统管理员（41 号），他们的
 * 权限申请列表由后端按查看者圈范围——只看得到自己那级待办与处理过的单子。
 * 花名申请同样由后端圈范围（nickname:review 人人持有，入口权限≠全量视野）：
 * 非 ADMIN 只见「下属团队的在途单 ∪ 我处理过的」，ADMIN 全量且可自批。
 */
/**
 * 花名变更页签：花名申请的唯一审批入口。
 *
 * 批准 = 真正把新花名写进 users（后端先验资格、抢占 PENDING 再改名，同事务），
 * 所以这里的"通过"不是标记而已，点了就生效——Popconfirm 要把后果说清楚。
 */
function NicknameRequestsTab() {
    const [rows, setRows] = useState<NicknameRequestRow[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    const [status, setStatus] = useState<string>('PENDING')
    const [loading, setLoading] = useState(false)
    const [rejecting, setRejecting] = useState<NicknameRequestRow | null>(null)
    const [rejectSaving, setRejectSaving] = useState(false)
    const [rejectForm] = Form.useForm<{ note: string }>()

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<PageResult<NicknameRequestRow>>('/admin/nickname-requests', {
                params: { page, size, status: status === 'ALL' ? undefined : status }
            })
            setRows(res.data.list)
            setTotal(res.data.total)
        } catch (err) {
            message.error(errorMessage(err, '加载申请列表失败'))
        } finally {
            setLoading(false)
        }
    }, [page, size, status])

    useEffect(() => {
        load()
    }, [load])

    const approve = async (row: NicknameRequestRow) => {
        try {
            await api.post(`/admin/nickname-requests/${row.id}/approve`)
            message.success(`已批准：${row.account} 的花名改为「${row.newNickname}」`)
            await load()
        } catch (err) {
            // 常见失败是批准时名字已被新注册抢占（nickname_exists），
            // 后端文案已经写明"让申请人重新提交"，原样透出即可
            message.error(errorMessage(err, '批准失败'))
            await load()
        }
    }

    const submitReject = async (values: { note: string }) => {
        if (!rejecting) return
        setRejectSaving(true)
        try {
            await api.post(`/admin/nickname-requests/${rejecting.id}/reject`, {
                note: values.note.trim()
            })
            message.success('已驳回，申请人可在「我的身份」看到理由')
            setRejecting(null)
            await load()
        } catch (err) {
            message.error(errorMessage(err, '驳回失败'))
        } finally {
            setRejectSaving(false)
        }
    }

    const columns: ColumnsType<NicknameRequestRow> = [
        {
            title: '申请人',
            dataIndex: 'account',
            key: 'account',
            width: 180,
            render: (_, row) => (
                <Space size={4}>
                    <span style={{ fontWeight: 500 }}>{row.account}</span>
                    <span style={{ color: '#98a2b3' }}>#{row.userId}</span>
                </Space>
            )
        },
        {
            title: '当前花名',
            dataIndex: 'oldNickname',
            key: 'oldNickname',
            width: 150,
            render: (v) => <span style={{ color: '#667085' }}>{v}</span>
        },
        {
            title: '申请改为',
            dataIndex: 'newNickname',
            key: 'newNickname',
            width: 170,
            render: (v) => (
                <span style={{ fontWeight: 500, color: '#101828' }}>{v}</span>
            )
        },
        {
            title: '提交时间',
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 170,
            render: (v) => <span style={{ color: '#667085' }}>{v}</span>
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: 110,
            filters: [
                { text: '待审批', value: 'PENDING' },
                { text: '已通过', value: 'APPROVED' },
                { text: '已驳回', value: 'REJECTED' }
            ],
            render: (v: string, row) => {
                const meta = STATUS_META[v] ?? { color: 'default', label: v }
                return (
                    <Tooltip
                        title={
                            v === 'REJECTED'
                                ? `驳回理由：${row.reviewNote || '未填写'}`
                                : v === 'APPROVED'
                                  ? `审批时间：${row.reviewedAt || '-'}`
                                  : '审批通过后新花名立即生效'
                        }
                    >
                        <Tag color={meta.color} style={{ cursor: 'help' }}>
                            {meta.label}
                        </Tag>
                    </Tooltip>
                )
            }
        },
        {
            title: '操作',
            key: 'action',
            width: 190,
            render: (_, row) =>
                row.status === 'PENDING' ? (
                    <Space size={8}>
                        <Popconfirm
                            title="批准这次花名变更？"
                            description={
                                <>
                                    {row.account} 的花名将由「{row.oldNickname}」改为
                                    「{row.newNickname}」，批准后立即生效。
                                </>
                            }
                            okText="批准"
                            cancelText="再想想"
                            onConfirm={() => approve(row)}
                        >
                            <Button type="primary" size="small" icon={<CheckCircleOutlined />}>
                                批准
                            </Button>
                        </Popconfirm>
                        <Button
                            size="small"
                            danger
                            icon={<CloseCircleOutlined />}
                            onClick={() => {
                                rejectForm.resetFields()
                                setRejecting(row)
                            }}
                        >
                            驳回
                        </Button>
                    </Space>
                ) : (
                    <span style={{ color: '#98a2b3' }}>
                        {row.status === 'APPROVED'
                            ? `已由 #${row.reviewerId ?? '-'} 批准`
                            : `已由 #${row.reviewerId ?? '-'} 驳回`}
                    </span>
                )
        }
    ]

    return (
        <>
            <Card className="table-card">
                <div
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        marginBottom: 16,
                        flexWrap: 'wrap',
                        gap: 12
                    }}
                >
                    <Radio.Group
                        value={status}
                        onChange={(e) => {
                            setStatus(e.target.value)
                            setPage(1)
                        }}
                        optionType="button"
                        buttonStyle="solid"
                        size="small"
                        options={[
                            { label: '待审批', value: 'PENDING' },
                            { label: '已通过', value: 'APPROVED' },
                            { label: '已驳回', value: 'REJECTED' },
                            { label: '全部', value: 'ALL' }
                        ]}
                    />
                    <span style={{ fontSize: 13, color: '#667085' }}>
                        批准后新花名立即生效；同一时刻每人只能有一条待审批申请
                    </span>
                </div>

                <Table<NicknameRequestRow>
                    rowKey="id"
                    loading={loading}
                    columns={columns}
                    dataSource={rows}
                    pagination={{
                        current: page,
                        pageSize: size,
                        total,
                        showSizeChanger: true,
                        showTotal: (t) => `共 ${t} 条`,
                        onChange: (p, s) => {
                            setPage(p)
                            setSize(s)
                        }
                    }}
                    locale={{
                        emptyText: status === 'PENDING' ? (
                            <span style={{ color: '#98a2b3' }}>
                                <StopOutlined /> 没有待审批的花名变更申请
                            </span>
                        ) : undefined
                    }}
                />
            </Card>

            <Modal
                title={`驳回 ${rejecting?.account ?? ''} 的花名申请`}
                open={rejecting !== null}
                onCancel={() => setRejecting(null)}
                onOk={() => rejectForm.submit()}
                okText="确认驳回"
                cancelText="取消"
                confirmLoading={rejectSaving}
                width={440}
                destroyOnClose
            >
                <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 16 }}
                    message="驳回理由会原样展示给申请人"
                    description={
                        rejecting
                            ? `申请内容：${rejecting.oldNickname} → ${rejecting.newNickname}`
                            : undefined
                    }
                />
                <Form
                    form={rejectForm}
                    layout="vertical"
                    onFinish={submitReject}
                    style={{ marginTop: 16 }}
                >
                    <Form.Item
                        name="note"
                        label="驳回理由"
                        rules={[
                            { required: true, message: '理由不能为空' },
                            { min: 2, max: 255, message: '理由长度 2-255 字' }
                        ]}
                        extra="写清楚原因，申请人才知道下次该改成什么"
                    >
                        <Input.TextArea
                            rows={3}
                            maxLength={255}
                            showCount
                            placeholder="如：与已有花名过于相似，请换一个"
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </>
    )
}

/* ---------------- 权限申请（permission:review） ---------------- */

/** 权限申请单（审批视角）。申请人与权限点信息由后端 join 出来 */
interface PermissionRequestRow {
    id: number
    userId: number
    account: string
    nickname: string
    permissionCode: string
    permissionName: string
    module: string
    note: string | null
    status: 'PENDING' | 'APPROVED' | 'REJECTED'
    reviewNote: string | null
    reviewerId: number | null
    createdAt: string
    reviewedAt: string | null
    // 两级审批（41 号）：当前级次与两级链路展示信息，canAct=这一级轮到我批吗（后端算好）
    stage: number
    leadReviewerId: number | null
    leadReviewedAt: string | null
    leadNames: string
    sysAdminNames: string
    canAct: boolean
}

/** 两级审批链路文案（状态列 tooltip 共用）：第 1 级组织上一级，第 2 级系统管理员 */
function stageChain(row: PermissionRequestRow): string {
    const lead = row.leadNames || '无负责人（需 ADMIN 批准）'
    const sys = row.sysAdminNames
        ? `第 2 级系统管理员：${row.sysAdminNames}`
        : '第 2 级：该模块未配置系统管理员（需 ADMIN）'
    if (row.status === 'PENDING' && row.stage === 2) {
        return `第 1 级已由 #${row.leadReviewerId ?? '-'} 于 ${row.leadReviewedAt || '-'} 批准；${sys}`
    }
    return `第 1 级（组织上一级）：${lead}；${sys}`
}

/**
 * 权限申请页签：两级串行审批（41 号）——第 1 级组织上一级（申请人所在团队的
 * 负责人），第 2 级系统管理员（权限所属模块的 system_admins），ADMIN 任意一级
 * 可一次批整单。谁能看/谁能批都由后端按查看者算好（canAct），前端不自算权限；
 * 申请人自己的单非 ADMIN 根本不在列表里（后端剔除），ADMIN 自批放行照常给按钮。
 *
 * 批准 = 后端写 user_permissions 直授行，申请人最晚在下次拉 /users/me 时看到
 * （AuthContext 有 15s 轮询，实际很快），不需要重登；驳回理由会原样展示给申请人。
 */
function PermissionRequestsTab() {
    const { isAdmin } = useAuth()
    const [rows, setRows] = useState<PermissionRequestRow[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    // 默认只看待审批——和花名列表同口径，进来就是要处理的单子
    const [status, setStatus] = useState<string>('PENDING')
    const [loading, setLoading] = useState(false)
    const [pendingCount, setPendingCount] = useState(0)
    const [rejecting, setRejecting] = useState<PermissionRequestRow | null>(null)
    const [rejectSaving, setRejectSaving] = useState(false)
    const [rejectForm] = Form.useForm<{ note: string }>()

    const loadPending = useCallback(async () => {
        try {
            const res = await api.get<number>('admin/permission-requests/pending-count')
            setPendingCount(res.data)
        } catch {
            // 顶部计数刷不到不影响审批本身，静默；列表接口失败才会提示
        }
    }, [])

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<PageResult<PermissionRequestRow>>(
                'admin/permission-requests',
                { params: { status, page, size } }
            )
            setRows(res.data.list)
            setTotal(res.data.total)
        } catch (err) {
            message.error(errorMessage(err, '加载权限申请列表失败'))
        } finally {
            setLoading(false)
        }
    }, [page, size, status])

    useEffect(() => {
        load()
        loadPending()
    }, [load, loadPending])

    const approve = async (row: PermissionRequestRow) => {
        try {
            const res = await api.post<PermissionRequestRow>(`admin/permission-requests/${row.id}/approve`)
            // 返回的单子还 PENDING = 第 1 级刚过、正转给系统管理员；终态 = 已生效
            if (res.data.status === 'PENDING') {
                message.success('已通过第 1 级（组织上一级）审批，转系统管理员继续审批')
            } else {
                message.success(`已批准：${row.nickname} 获得「${row.permissionName}」`)
            }
        } catch (err) {
            // 409 = 已被其他管理员处理，后端文案直接透出；刷新列表让状态跟上
            message.error(errorMessage(err, '批准失败'))
        } finally {
            await Promise.all([load(), loadPending()])
        }
    }

    const submitReject = async (values: { note: string }) => {
        if (!rejecting) return
        setRejectSaving(true)
        try {
            await api.post(`admin/permission-requests/${rejecting.id}/reject`, {
                note: values.note.trim()
            })
            message.success('已驳回，申请人可在「权限中心」看到理由')
            setRejecting(null)
            await Promise.all([load(), loadPending()])
        } catch (err) {
            message.error(errorMessage(err, '驳回失败'))
        } finally {
            setRejectSaving(false)
        }
    }

    const columns: ColumnsType<PermissionRequestRow> = [
        {
            title: '申请人',
            key: 'applicant',
            width: 180,
            render: (_, row) => (
                <Space size={4} direction="vertical" style={{ lineHeight: 1.4 }}>
                    <span style={{ fontWeight: 500 }}>{row.nickname}</span>
                    <span style={{ color: '#98a2b3', fontSize: 12 }}>{row.account}</span>
                </Space>
            )
        },
        {
            title: '权限点',
            key: 'permission',
            width: 230,
            render: (_, row) => (
                <Space size={4} direction="vertical" style={{ lineHeight: 1.4 }}>
                    <span style={{ fontWeight: 500, color: '#101828' }}>{row.permissionName}</span>
                    <span style={{ color: '#98a2b3', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11 }}>
                        {row.permissionCode}
                    </span>
                </Space>
            )
        },
        {
            title: '模块',
            dataIndex: 'module',
            key: 'module',
            width: 110,
            render: (v) => (
                <Tag color={MODULE_COLOR[v] ?? '#4f46e5'} style={{ marginInlineEnd: 0 }}>
                    {(MODULE_LABEL[v] ?? v) || '—'}
                </Tag>
            )
        },
        {
            title: '申请理由',
            dataIndex: 'note',
            key: 'note',
            render: (v) =>
                v ? (
                    <span style={{ color: '#667085' }}>{v}</span>
                ) : (
                    <span style={{ color: '#98a2b3' }}>—</span>
                )
        },
        {
            title: '提交时间',
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 170,
            render: (v) => <span style={{ color: '#667085' }}>{v}</span>
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: 150,
            render: (v: string, row) => {
                // 待审批按当前级次分开叫：待上级审批 / 待系统管理员审批
                const meta = v === 'PENDING'
                    ? (row.stage === 2
                        ? { color: 'orange', label: '待系统管理员审批' }
                        : { color: 'orange', label: '待上级审批' })
                    : (PERM_STATUS_META[v] ?? { color: 'default', label: v })
                return (
                    <Tooltip
                        title={
                            v === 'REJECTED'
                                ? `${stageChain(row)}；驳回理由：${row.reviewNote || '未填写'}`
                                : v === 'APPROVED'
                                  ? `${stageChain(row)}；批准后即时写入直授行，审批时间：${row.reviewedAt || '-'}`
                                  : stageChain(row)
                        }
                    >
                        <Tag color={meta.color} style={{ cursor: 'help' }}>
                            {meta.label}
                        </Tag>
                    </Tooltip>
                )
            }
        },
        {
            title: '操作',
            key: 'action',
            width: 190,
            render: (_, row) => {
                if (row.status !== 'PENDING') {
                    return (
                        <span style={{ color: '#98a2b3' }}>
                            {row.status === 'APPROVED'
                                ? `已由 #${row.reviewerId ?? '-'} 批准`
                                : `已由 #${row.reviewerId ?? '-'} 驳回`}
                        </span>
                    )
                }
                if (!row.canAct) {
                    // 看得见但轮不到自己——最常见是「我批完第 1 级，单子转给了系统管理员」。
                    // 自己的单非 ADMIN 根本不在范围内（后端已剔除），ADMIN 自批放行，
                    // 所以这里不需要「我的申请」分支
                    return (
                        <span style={{ color: '#98a2b3' }}>
                            {row.stage === 2
                                ? `待系统管理员${row.sysAdminNames ? `（${row.sysAdminNames}）` : ''}审批`
                                : `待 ${row.leadNames || '负责人'} 审批`}
                        </span>
                    )
                }
                return (
                    <Space size={8}>
                        <Popconfirm
                            title="批准这条权限申请？"
                            description={
                                row.stage === 1 ? (
                                    isAdmin ? (
                                        <>
                                            ADMIN 批准 = 两级一并通过，{row.nickname} 立即获得
                                            「{row.permissionName}」。
                                        </>
                                    ) : (
                                        <>
                                            通过后转系统管理员进行第 2 级审批，通过该级才生效。
                                        </>
                                    )
                                ) : (
                                    <>
                                        批准后 {row.nickname} 立即获得「{row.permissionName}」，
                                        本人无需重登。
                                    </>
                                )
                            }
                            okText="批准"
                            cancelText="再想想"
                            onConfirm={() => approve(row)}
                        >
                            <Button type="primary" size="small" icon={<CheckCircleOutlined />}>
                                批准
                            </Button>
                        </Popconfirm>
                        <Button
                            size="small"
                            danger
                            icon={<CloseCircleOutlined />}
                            onClick={() => {
                                rejectForm.resetFields()
                                setRejecting(row)
                            }}
                        >
                            驳回
                        </Button>
                    </Space>
                )
            }
        }
    ]

    return (
        <>
            <Card className="table-card">
                <div
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        marginBottom: 16,
                        flexWrap: 'wrap',
                        gap: 12
                    }}
                >
                    <Radio.Group
                        value={status}
                        onChange={(e) => {
                            setStatus(e.target.value)
                            setPage(1)
                        }}
                        optionType="button"
                        buttonStyle="solid"
                        size="small"
                        options={[
                            { label: '待审批', value: 'PENDING' },
                            { label: '已批准', value: 'APPROVED' },
                            { label: '已驳回', value: 'REJECTED' },
                            { label: '全部', value: 'ALL' }
                        ]}
                    />
                    <span style={{ fontSize: 13, color: '#667085' }}>
                        待审批 {pendingCount} 条（我的待办）；两级审批：组织上一级 →
                        系统管理员，ADMIN 可一次批整单；驳回必须填写理由
                    </span>
                </div>

                <Table<PermissionRequestRow>
                    rowKey="id"
                    loading={loading}
                    columns={columns}
                    dataSource={rows}
                    pagination={{
                        current: page,
                        pageSize: size,
                        total,
                        showSizeChanger: true,
                        showTotal: (t) => `共 ${t} 条`,
                        onChange: (p, s) => {
                            setPage(p)
                            setSize(s)
                        }
                    }}
                    locale={{
                        emptyText: status === 'PENDING' ? (
                            <span style={{ color: '#98a2b3' }}>
                                <StopOutlined /> 没有待审批的权限申请
                            </span>
                        ) : undefined
                    }}
                />
            </Card>

            <Modal
                title={`驳回 ${rejecting?.nickname ?? ''} 的权限申请`}
                open={rejecting !== null}
                onCancel={() => setRejecting(null)}
                onOk={() => rejectForm.submit()}
                okText="确认驳回"
                cancelText="取消"
                confirmLoading={rejectSaving}
                width={440}
                destroyOnClose
            >
                <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 16 }}
                    message="驳回理由会原样展示给申请人"
                    description={
                        rejecting
                            ? `申请内容：${rejecting.permissionName}（${rejecting.permissionCode}）`
                            : undefined
                    }
                />
                <Form
                    form={rejectForm}
                    layout="vertical"
                    onFinish={submitReject}
                    style={{ marginTop: 16 }}
                >
                    <Form.Item
                        name="note"
                        label="驳回理由"
                        rules={[
                            { required: true, message: '理由不能为空' },
                            { min: 2, max: 255, message: '理由长度 2-255 字' }
                        ]}
                        extra="写清楚原因，申请人才知道下次该怎么申请"
                    >
                        <Input.TextArea
                            rows={3}
                            maxLength={255}
                            showCount
                            placeholder="如：本岗位暂不需要该权限，或改用角色授权"
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </>
    )
}

export default function ApprovalsPage() {
    const { hasPermission } = useAuth()
    // 两类单子各自的审批权：整页由 App.tsx 的 anyOf 守卫（任一命中），
    // 页内再按可见页签显隐，只持有一种审批权的人不会看到自己碰不了的单子
    const items = [
        ...(hasPermission('nickname:review')
            ? [{ key: 'nickname', label: '花名变更', children: <NicknameRequestsTab /> }]
            : []),
        ...(hasPermission('permission:review')
            ? [{ key: 'perm', label: '权限申请', children: <PermissionRequestsTab /> }]
            : [])
    ]
    // 默认落在第一个可见页签上；hooks 必须无条件调用，所以放在分支之前
    const [tab, setTab] = useState(items[0]?.key ?? 'nickname')

    // 只剩一个页签时不渲染 Tabs 容器：为单个页签画一条空 tab 栏没有意义
    if (items.length <= 1) {
        return <>{items[0]?.children ?? null}</>
    }
    // 审批权在别处被收回时，当前页签可能已不在可见列表——回落到第一个可见页签
    const activeKey = items.some((item) => item.key === tab) ? tab : items[0].key
    return (
        <div className="fade-in">
            <Tabs activeKey={activeKey} onChange={setTab} items={items} />
        </div>
    )
}
