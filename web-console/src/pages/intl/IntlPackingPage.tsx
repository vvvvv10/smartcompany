import {
    ExperimentOutlined,
    InboxOutlined,
    PlusOutlined,
    RocketOutlined,
    ShopOutlined,
    ToolOutlined,
    WarningOutlined
} from '@ant-design/icons'
import {
    Alert,
    Button,
    Card,
    Col,
    Descriptions,
    Drawer,
    Empty,
    Form,
    Input,
    InputNumber,
    Modal,
    Popconfirm,
    Row,
    Select,
    Space,
    Spin,
    Statistic,
    Switch,
    Table,
    Tabs,
    Tag,
    Typography,
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import type {
    IntlInTransit,
    IntlInventoryBatch,
    IntlPackTask,
    IntlPackTaskDetail,
    IntlWmsDashboard,
    PageResult,
    WmsWarehouse
} from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import {
    BATCH_STATUS,
    IN_TRANSIT_STATUS,
    PACK_TASK_STATUS,
    batchStatusMeta,
    dash,
    formatCbm,
    formatDate,
    formatKg,
    formatUtc
} from './shared'

const PACK_STATUS_OPTIONS = Object.entries(PACK_TASK_STATUS).map(([value, m]) => ({ value, label: m.label }))
const BATCH_STATUS_OPTIONS = Object.entries(BATCH_STATUS).map(([value, m]) => ({ value, label: m.label }))
const TRANSIT_STATUS_OPTIONS = Object.entries(IN_TRANSIT_STATUS).map(([value, m]) => ({ value, label: m.label }))

const PACK_ADVANCE_OPTIONS = [
    { value: 'PENDING', label: '待处理 → 拣货中（点「开始拣货」）' },
    { value: 'PICKING', label: '拣货中' },
    { value: 'PACKED', label: '拣货中 → 已装箱' },
    { value: 'LABELLED', label: '已装箱 → 已贴标' },
    { value: 'HANDED_OVER', label: '已贴标 → 已交接承运人' },
    { value: 'SHORTAGE', label: '缺料' },
    { value: 'CANCELLED', label: '已取消' }
]

/**
 * 备货装箱页（/intl/packing）——WMS 侧。
 *
 * M1 的备货是**弱联动**：任务由人在这页填「出口子单号」建，
 * OMS 不主动派单（WMS→OMS 的反向回调是 M2）。所以出口订单列表上的
 * 「备货」列显示 — 时，要来这里补建任务——这是刻意可见的缺口，不是 bug。
 */
export default function IntlPackingPage() {
    const { hasPermission } = useAuth()
    const canEdit = hasPermission('wms:intl:edit')
    const [tab, setTab] = useState('tasks')
    const [error, setError] = useState<string | null>(null)
    const [dashboard, setDashboard] = useState<IntlWmsDashboard | null>(null)
    const [warehouses, setWarehouses] = useState<WmsWarehouse[]>([])

    // 备货任务
    const [tasks, setTasks] = useState<IntlPackTask[]>([])
    const [taskTotal, setTaskTotal] = useState(0)
    const [taskPage, setTaskPage] = useState(1)
    const [taskSize, setTaskSize] = useState(10)
    const [taskKeyword, setTaskKeyword] = useState('')
    const [taskStatus, setTaskStatus] = useState<string | undefined>()
    const [taskLoading, setTaskLoading] = useState(false)
    const [taskDetail, setTaskDetail] = useState<IntlPackTaskDetail | null>(null)
    const [taskDetailLoading, setTaskDetailLoading] = useState(false)

    // 库存批次
    const [batches, setBatches] = useState<IntlInventoryBatch[]>([])
    const [batchTotal, setBatchTotal] = useState(0)
    const [batchPage, setBatchPage] = useState(1)
    const [batchKeyword, setBatchKeyword] = useState('')
    const [batchStatus, setBatchStatus] = useState<string | undefined>()
    const [expiringDays, setExpiringDays] = useState<number | undefined>()
    const [batchLoading, setBatchLoading] = useState(false)

    // 在途
    const [transits, setTransits] = useState<IntlInTransit[]>([])
    const [transitTotal, setTransitTotal] = useState(0)
    const [transitPage, setTransitPage] = useState(1)
    const [transitStatus, setTransitStatus] = useState<string | undefined>()
    const [transitLoading, setTransitLoading] = useState(false)

    // 仓库
    const [warehouseRows, setWarehouseRows] = useState<WmsWarehouse[]>([])

    // 表单
    const [taskFormOpen, setTaskFormOpen] = useState(false)
    const [taskForm] = Form.useForm<{ orderNo: string; warehouseId?: number; locationCode?: string; remark?: string }>()
    const [advanceOpen, setAdvanceOpen] = useState(false)
    const [advanceTarget, setAdvanceTarget] = useState<IntlPackTask | null>(null)
    const [advanceForm] = Form.useForm<{
        status: string
        boxNo?: string
        containerType?: string
        pieces?: number
        grossWeight?: number
        volume?: number
        marks?: string
        remark?: string
    }>()
    const [transitOpen, setTransitOpen] = useState(false)
    const [transitTarget, setTransitTarget] = useState<IntlInTransit | null>(null)
    const [transitForm] = Form.useForm<{ status: string; toWarehouseId?: number }>()
    const [batchFormOpen, setBatchFormOpen] = useState(false)
    const [batchForm] = Form.useForm<Record<string, unknown>>()

    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<IntlWmsDashboard>('/wms/intl/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载仓储看板失败'))
        }
    }, [])

    const loadTasks = useCallback(async () => {
        setTaskLoading(true)
        try {
            const res = await api.get<PageResult<IntlPackTask>>('/wms/intl/pack-tasks', {
                params: {
                    page: taskPage,
                    size: taskSize,
                    keyword: taskKeyword || undefined,
                    status: taskStatus || undefined
                }
            })
            setTasks(res.data.list)
            setTaskTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载备货任务失败'))
        } finally {
            setTaskLoading(false)
        }
    }, [taskPage, taskSize, taskKeyword, taskStatus])

    const loadBatches = useCallback(async () => {
        setBatchLoading(true)
        try {
            const res = await api.get<PageResult<IntlInventoryBatch>>('/wms/intl/inventory-batches', {
                params: {
                    page: batchPage,
                    size: 10,
                    keyword: batchKeyword || undefined,
                    status: batchStatus || undefined,
                    expiringWithinDays: expiringDays || undefined
                }
            })
            setBatches(res.data.list)
            setBatchTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载库存批次失败'))
        } finally {
            setBatchLoading(false)
        }
    }, [batchPage, batchKeyword, batchStatus, expiringDays])

    const loadTransits = useCallback(async () => {
        setTransitLoading(true)
        try {
            const res = await api.get<PageResult<IntlInTransit>>('/wms/intl/in-transit', {
                params: { page: transitPage, size: 10, status: transitStatus || undefined }
            })
            setTransits(res.data.list)
            setTransitTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载在途库存失败'))
        } finally {
            setTransitLoading(false)
        }
    }, [transitPage, transitStatus])

    const loadWarehouses = useCallback(async () => {
        try {
            const res = await api.get<PageResult<WmsWarehouse>>('/wms/warehouses', { params: { page: 1, size: 50 } })
            setWarehouseRows(res.data.list)
            setWarehouses(res.data.list)
        } catch (err) {
            setError(errorMessage(err, '加载仓库失败'))
        }
    }, [])

    useEffect(() => {
        loadDashboard()
        loadWarehouses()
    }, [loadDashboard, loadWarehouses])

    useEffect(() => {
        if (tab === 'tasks') {
            loadTasks()
        } else if (tab === 'batches') {
            loadBatches()
        } else if (tab === 'transit') {
            loadTransits()
        }
    }, [tab, loadTasks, loadBatches, loadTransits])

    const openTaskDetail = async (row: IntlPackTask) => {
        setTaskDetailLoading(true)
        try {
            const res = await api.get<IntlPackTaskDetail>(`/wms/intl/pack-tasks/${row.id}`)
            setTaskDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载任务详情失败'))
        } finally {
            setTaskDetailLoading(false)
        }
    }

    const openAdvance = (row: IntlPackTask) => {
        setAdvanceTarget(row)
        advanceForm.resetFields()
        advanceForm.setFieldsValue({
            status: 'PICKING',
            boxNo: row.boxNo,
            containerType: row.containerType,
            pieces: row.pieces,
            grossWeight: row.grossWeight,
            volume: row.volume
        })
        setAdvanceOpen(true)
    }

    const submitAdvance = async (values: {
        status: string
        boxNo?: string
        containerType?: string
        pieces?: number
        grossWeight?: number
        volume?: number
        marks?: string
        remark?: string
    }) => {
        if (!advanceTarget) {
            return
        }
        try {
            await api.patch(`/wms/intl/pack-tasks/${advanceTarget.id}/status`, values)
            message.success('任务状态已推进')
            setAdvanceOpen(false)
            loadTasks()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '推进任务状态失败'))
        }
    }

    const createTask = async (values: { orderNo: string; warehouseId?: number; locationCode?: string; remark?: string }) => {
        try {
            await api.post('/wms/intl/pack-tasks', {
                orderNo: values.orderNo,
                warehouseId: values.warehouseId,
                locationCode: values.locationCode,
                taskType: 'PACK',
                status: 'PENDING',
                remark: values.remark
            })
            message.success('备货任务已创建')
            setTaskFormOpen(false)
            loadTasks()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '创建备货任务失败'))
        }
    }

    const openTransitStatus = (row: IntlInTransit) => {
        setTransitTarget(row)
        transitForm.resetFields()
        transitForm.setFieldsValue({ status: 'ARRIVED', toWarehouseId: row.toWarehouseId })
        setTransitOpen(true)
    }

    const submitTransit = async (values: { status: string; toWarehouseId?: number }) => {
        if (!transitTarget) {
            return
        }
        try {
            await api.patch(`/wms/intl/in-transit/${transitTarget.id}/status`, values)
            message.success('在途状态已推进')
            setTransitOpen(false)
            loadTransits()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '推进在途状态失败'))
        }
    }

    const deleteTask = async (row: IntlPackTask) => {
        try {
            await api.delete(`/wms/intl/pack-tasks/${row.id}`)
            message.success('任务已删除')
            loadTasks()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '删除任务失败'))
        }
    }

    const taskColumns = [
        {
            title: '任务号',
            dataIndex: 'taskNo',
            width: 160,
            render: (v: string) => (
                <Typography.Text copyable={{ text: v }} style={{ fontWeight: 500 }}>
                    {v}
                </Typography.Text>
            )
        },
        { title: '出口子单号', dataIndex: 'orderNo', width: 150, render: (v: string) => v },
        { title: '仓库', dataIndex: 'warehouseName', width: 150, render: (v?: string | null) => dash(v) },
        { title: '库位', dataIndex: 'locationCode', width: 100, render: (v?: string) => dash(v) },
        { title: '箱号', dataIndex: 'boxNo', width: 190, render: (v?: string) => dash(v) },
        { title: '包装', dataIndex: 'containerType', width: 90, render: (v?: string) => (v ? <Tag>{v}</Tag> : dash(v)) },
        { title: '件数', dataIndex: 'pieces', width: 80, align: 'right' as const, render: (v?: number) => v ?? '—' },
        { title: '毛重', dataIndex: 'grossWeight', width: 110, align: 'right' as const, render: (v?: number) => formatKg(v) },
        { title: '体积', dataIndex: 'volume', width: 110, align: 'right' as const, render: (v?: number) => formatCbm(v) },
        {
            title: '唛头',
            dataIndex: 'marks',
            width: 200,
            render: (v?: string) =>
                v ? (
                    <Typography.Paragraph
                        copyable={{ text: v }}
                        style={{ whiteSpace: 'pre-wrap', fontSize: 12, marginBottom: 0 }}
                        ellipsis={{ rows: 3, tooltip: v }}
                    >
                        {v}
                    </Typography.Paragraph>
                ) : (
                    dash(v)
                )
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (v: string) => <Tag color={packTaskStatusColor(v)}>{PACK_TASK_STATUS[v]?.label ?? v}</Tag>
        },
        { title: '交接时间(UTC)', dataIndex: 'handedOverAt', width: 150, render: (v?: string | null) => formatUtc(v) },
        {
            title: '操作',
            width: 180,
            align: 'center' as const,
            render: (_: unknown, row: IntlPackTask) => (
                <Space size={2}>
                    <Button type="link" size="small" onClick={() => openTaskDetail(row)}>
                        详情
                    </Button>
                    {canEdit ? (
                        <>
                            <Button type="link" size="small" onClick={() => openAdvance(row)}>
                                推进状态
                            </Button>
                            {row.status === 'PENDING' || row.status === 'PICKING' ? (
                                <Popconfirm
                                    title={`删除任务「${row.taskNo}」？`}
                                    okText="删除"
                                    cancelText="取消"
                                    okButtonProps={{ danger: true }}
                                    onConfirm={() => deleteTask(row)}
                                >
                                    <Button type="link" size="small" danger>
                                        删除
                                    </Button>
                                </Popconfirm>
                            ) : null}
                        </>
                    ) : null}
                </Space>
            )
        }
    ]

    const batchColumns = [
        { title: '仓库', dataIndex: 'warehouseName', width: 150, render: (v?: string | null) => dash(v) },
        { title: 'SKU', dataIndex: 'sku', width: 140, render: (v: string) => v },
        { title: '品名', dataIndex: 'productName', width: 170, render: (v?: string) => dash(v) },
        { title: '批次号', dataIndex: 'batchNo', width: 120, render: (v: string) => v },
        { title: '生产日期', dataIndex: 'productionDate', width: 110, render: (v?: string | null) => formatDate(v) },
        {
            title: '效期',
            dataIndex: 'expiryDate',
            width: 110,
            render: (v: string | null | undefined, row: IntlInventoryBatch) => {
                const m = batchStatusMeta(row.status)
                return (
                    <Tag color={m.color === 'success' ? undefined : m.color}>
                        {formatDate(v)}
                        {row.status === 'NEAR_EXPIRY' ? '（临期）' : row.status === 'EXPIRED' ? '（过期）' : ''}
                    </Tag>
                )
            }
        },
        { title: '在库', dataIndex: 'quantity', width: 80, align: 'right' as const },
        { title: '占用', dataIndex: 'reservedQty', width: 80, align: 'right' as const },
        {
            title: '可用（在库−占用）',
            dataIndex: 'availableQty',
            width: 140,
            align: 'right' as const,
            render: (v?: number) => (
                <span style={{ fontWeight: 600, color: v !== null && v !== undefined && v <= 0 ? '#f04438' : '#12b76a' }}>
                    {v ?? '—'}
                </span>
            )
        },
        { title: '单位成本', dataIndex: 'unitCost', width: 110, align: 'right' as const, render: (v?: number) => (v ?? '—') },
        { title: '库位', dataIndex: 'locationCode', width: 100, render: (v?: string) => dash(v) },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (v: string) => {
                const m = batchStatusMeta(v)
                return <Tag color={m.color}>{m.label}</Tag>
            }
        }
    ]

    const transitColumns = [
        { title: '出口子单号', dataIndex: 'orderNo', width: 150, render: (v?: string) => dash(v) },
        { title: '运单号', dataIndex: 'shipmentNo', width: 155, render: (v: string) => v },
        { title: 'SKU', dataIndex: 'sku', width: 140, render: (v?: string) => dash(v) },
        { title: '品名', dataIndex: 'productName', width: 170, render: (v?: string) => dash(v) },
        { title: '批次', dataIndex: 'batchNo', width: 110, render: (v?: string) => dash(v) },
        { title: '在途数量', dataIndex: 'quantity', width: 100, align: 'right' as const },
        {
            title: '发货仓 → 目的仓',
            width: 220,
            render: (_: unknown, row: IntlInTransit) => (
                <span>
                    {dash(row.fromWarehouseName)} → {row.toWarehouseId ? dash(row.toWarehouseName) : dash(null)}
                </span>
            )
        },
        { title: '出库时间(UTC)', dataIndex: 'shippedAt', width: 150, render: (v: string) => formatUtc(v) },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (v: string) => {
                const m = IN_TRANSIT_STATUS[v] ?? { label: v, color: 'default' }
                return <Tag color={m.color}>{m.label}</Tag>
            }
        },
        {
            title: '操作',
            width: 110,
            align: 'center' as const,
            render: (_: unknown, row: IntlInTransit) =>
                canEdit && row.status !== 'RECEIVED' ? (
                    <Button type="link" size="small" onClick={() => openTransitStatus(row)}>
                        推进状态
                    </Button>
                ) : (
                    dash(null)
                )
        }
    ]

    const warehouseColumns = [
        { title: '仓库名称', dataIndex: 'name', width: 170 },
        { title: '位置', dataIndex: 'location', width: 220, render: (v?: string) => dash(v) },
        { title: '仓库类型', dataIndex: 'warehouseType', width: 110 },
        { title: '所在国', dataIndex: 'country', width: 90 },
        { title: '时区', dataIndex: 'timezone', width: 160 },
        {
            title: '保税',
            dataIndex: 'isBonded',
            width: 80,
            render: (v?: number) => (v ? <Tag color="gold">保税</Tag> : dash(null))
        },
        { title: '海外仓服务商', dataIndex: 'overseaOperator', width: 150, render: (v?: string) => dash(v) },
        { title: '容量', dataIndex: 'capacity', width: 100, align: 'right' as const, render: (v?: number) => v ?? '—' },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (v: string) => <Tag color={v === 'ACTIVE' ? 'success' : 'default'}>{v}</Tag>
        }
    ]

    return (
        <div className="fade-in">
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="待处理任务"
                            value={dashboard?.packTask.pending ?? 0}
                            prefix={<InboxOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="拣货中"
                            value={dashboard?.packTask.picking ?? 0}
                            prefix={<ToolOutlined style={{ color: '#f79009' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="缺料"
                            value={dashboard?.packTask.shortage ?? 0}
                            prefix={<WarningOutlined style={{ color: '#f04438' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="已交接"
                            value={dashboard?.packTask.handedOver ?? 0}
                            prefix={<ExperimentOutlined style={{ color: '#12b76a' }} />}
                        />
                    </Card>
                </Col>
            </Row>

            <Tabs
                activeKey={tab}
                onChange={setTab}
                style={{ marginTop: 16 }}
                items={[
                    {
                        key: 'tasks',
                        label: (
                            <span>
                                <InboxOutlined />
                                备货任务
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`备货/装箱/贴标任务 · 共 ${taskTotal} 条`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="任务状态"
                                            allowClear
                                            style={{ width: 130 }}
                                            options={PACK_STATUS_OPTIONS}
                                            value={taskStatus}
                                            onChange={(v) => {
                                                setTaskPage(1)
                                                setTaskStatus(v)
                                            }}
                                        />
                                        <Input.Search
                                            placeholder="任务号 / 子单号 / 箱号"
                                            allowClear
                                            style={{ width: 230 }}
                                            onSearch={(v) => {
                                                setTaskPage(1)
                                                setTaskKeyword(v)
                                            }}
                                        />
                                        <Button onClick={loadTasks}>刷新</Button>
                                        {canEdit ? (
                                            <Button
                                                type="primary"
                                                icon={<PlusOutlined />}
                                                onClick={() => {
                                                    taskForm.resetFields()
                                                    setTaskFormOpen(true)
                                                }}
                                            >
                                                新建备货任务
                                            </Button>
                                        ) : null}
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={taskLoading}
                                    columns={taskColumns}
                                    dataSource={tasks}
                                    scroll={{ x: 1800 }}
                                    locale={{ emptyText: <Empty description="还没有备货任务" /> }}
                                    pagination={{
                                        current: taskPage,
                                        pageSize: taskSize,
                                        total: taskTotal,
                                        showSizeChanger: true,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p, s) => {
                                            setTaskPage(p)
                                            setTaskSize(s)
                                        }
                                    }}
                                    onRow={(row) => ({
                                        onClick: () => openTaskDetail(row),
                                        style: { cursor: 'pointer' }
                                    })}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'batches',
                        label: (
                            <span>
                                <ExperimentOutlined />
                                库存批次
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`库存批次与效期 · 共 ${batchTotal} 条（可用量 = 在库 − 占用，服务端现算）`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="批次状态"
                                            allowClear
                                            style={{ width: 120 }}
                                            options={BATCH_STATUS_OPTIONS}
                                            value={batchStatus}
                                            onChange={(v) => {
                                                setBatchPage(1)
                                                setBatchStatus(v)
                                            }}
                                        />
                                        <Select
                                            placeholder="只看临期 N 天"
                                            allowClear
                                            style={{ width: 150 }}
                                            options={[
                                                { value: 7, label: '7 天内到期' },
                                                { value: 30, label: '30 天内到期' },
                                                { value: 90, label: '90 天内到期' }
                                            ]}
                                            value={expiringDays}
                                            onChange={(v) => {
                                                setBatchPage(1)
                                                setExpiringDays(v)
                                            }}
                                        />
                                        <Input.Search
                                            placeholder="SKU / 品名 / 批次号"
                                            allowClear
                                            style={{ width: 210 }}
                                            onSearch={(v) => {
                                                setBatchPage(1)
                                                setBatchKeyword(v)
                                            }}
                                        />
                                        <Button onClick={loadBatches}>刷新</Button>
                                        {canEdit ? (
                                            <Button
                                                type="primary"
                                                icon={<PlusOutlined />}
                                                onClick={() => {
                                                    batchForm.resetFields()
                                                    setBatchFormOpen(true)
                                                }}
                                            >
                                                新增库存批次
                                            </Button>
                                        ) : null}
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={batchLoading}
                                    columns={batchColumns}
                                    dataSource={batches}
                                    scroll={{ x: 1500 }}
                                    locale={{ emptyText: <Empty description="还没有库存批次" /> }}
                                    pagination={{
                                        current: batchPage,
                                        pageSize: 10,
                                        total: batchTotal,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p) => setBatchPage(p)
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'transit',
                        label: (
                            <span>
                                <RocketOutlined />
                                在途库存
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`在途库存 · 共 ${transitTotal} 条（在途 ${dashboard?.inTransit.inTransit ?? 0} / 已到仓 ${
                                    dashboard?.inTransit.arrived ?? 0
                                } / 丢件 ${dashboard?.inTransit.lost ?? 0}）`}
                                extra={
                                    <Space>
                                        <Select
                                            placeholder="在途状态"
                                            allowClear
                                            style={{ width: 130 }}
                                            options={TRANSIT_STATUS_OPTIONS}
                                            value={transitStatus}
                                            onChange={(v) => {
                                                setTransitPage(1)
                                                setTransitStatus(v)
                                            }}
                                        />
                                        <Button onClick={loadTransits}>刷新</Button>
                                    </Space>
                                }
                            >
                                {/*
                                 * P2-4：说明必须写在**在途这个 Tab 里**，不能只写在评审文档里。
                                 * 做库存的人打开这一页第一件事就是「在途怎么比在库还多」，
                                 * 数字摆在那里是对的（M1 有意的半截真相，见 InTransitService
                                 * 类注释），但没有这句话，这个数字就长得像 bug——
                                 * 而一旦被当成 bug，第一个动作就是去「修数据」，
                                 * 把唯一一份可信的登记改坏。
                                 *
                                 * 为什么用 warning 而不是 info：它要打断的是「在途+在库=总量」
                                 * 这个错误心算，info 会被当成页脚说明划过去。
                                 *
                                 * 文案在 P2-4 落地后改过一次。原来写的是「入库也不扣库存」，
                                 * 那是 M1 的事实；现在**入库已经会真实搬货**了（推「已入库」
                                 * 时同事务扣源仓批次 + 记目的仓），继续说「两本账都不动」
                                 * 就是把过期的话留在页面上。
                                 * 现在剩下的半截真相只有一处：**出库侧没联动**——在库量里
                                 * 仍然含着已经发走的货。这才是「在途比在库还大」的真正原因，
                                 * 说准了人才知道该去补哪一段。
                                 *
                                 * 出库联动做完（发运时扣源仓批次）之后，这条提示才该撤掉。
                                 */}
                                <Alert
                                    type="warning"
                                    showIcon
                                    style={{ marginBottom: 12 }}
                                    message="在途与在库是两本账，不可相加"
                                    description="入库这一侧已经打通：把在途推到「已入库」时，系统会在同一个事务里扣减源仓的库存批次、并把同样的数量记入目的仓，两边同步增减，不会凭空多出货物。缺的是出库这一侧——货发出去时这里只记一笔「在海上」，并不从源仓批次扣减，所以同一个 SKU 的在库量里仍然含着已经发走的货。于是同一个 SKU 的在途量比在库量看起来还大是正常的，不是多发货、也不是数据错；别去「修数据」，那会把唯一一份可信的登记改坏。"
                                />
                                <Table
                                    rowKey="id"
                                    loading={transitLoading}
                                    columns={transitColumns}
                                    dataSource={transits}
                                    scroll={{ x: 1400 }}
                                    locale={{ emptyText: <Empty description="还没有在途记录" /> }}
                                    pagination={{
                                        current: transitPage,
                                        pageSize: 10,
                                        total: transitTotal,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p) => setTransitPage(p)
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'warehouses',
                        label: (
                            <span>
                                <ShopOutlined />
                                仓库
                            </span>
                        ),
                        children: (
                            <Card className="table-card" title="仓库（含国际仓属性）">
                                <Table
                                    rowKey="id"
                                    columns={warehouseColumns}
                                    dataSource={warehouseRows}
                                    scroll={{ x: 1100 }}
                                    pagination={false}
                                    locale={{ emptyText: <Empty description="还没有仓库" /> }}
                                />
                            </Card>
                        )
                    }
                ]}
            />

            {/* 任务详情 */}
            <Drawer
                title={taskDetail ? `备货任务详情 · ${taskDetail.task.taskNo}` : '备货任务详情'}
                open={!!taskDetail}
                onClose={() => setTaskDetail(null)}
                width={900}
                destroyOnClose
            >
                <Spin spinning={taskDetailLoading}>
                    {taskDetail ? (
                        <Space direction="vertical" style={{ width: '100%' }} size={12}>
                            <Descriptions column={2} size="small" bordered>
                                <Descriptions.Item label="出口子单号">{taskDetail.task.orderNo}</Descriptions.Item>
                                <Descriptions.Item label="仓库">{dash(taskDetail.task.warehouseName)}</Descriptions.Item>
                                <Descriptions.Item label="库位">{dash(taskDetail.task.locationCode)}</Descriptions.Item>
                                <Descriptions.Item label="箱号">{dash(taskDetail.task.boxNo)}</Descriptions.Item>
                                <Descriptions.Item label="状态">
                                    <Tag color={packTaskStatusColor(taskDetail.task.status)}>
                                        {PACK_TASK_STATUS[taskDetail.task.status]?.label ?? taskDetail.task.status}
                                    </Tag>
                                </Descriptions.Item>
                                <Descriptions.Item label="包装">
                                    {dash(taskDetail.task.containerType)}
                                </Descriptions.Item>
                                <Descriptions.Item label="贴标时间(UTC)">
                                    {formatUtc(taskDetail.task.labelPrintedAt)}
                                </Descriptions.Item>
                                <Descriptions.Item label="交接时间(UTC)">
                                    {formatUtc(taskDetail.task.handedOverAt)}
                                </Descriptions.Item>
                                <Descriptions.Item label="唛头" span={2}>
                                    <Typography.Paragraph
                                        copyable={{ text: taskDetail.task.marks }}
                                        style={{ whiteSpace: 'pre-wrap', fontSize: 12, marginBottom: 0 }}
                                    >
                                        {dash(taskDetail.task.marks)}
                                    </Typography.Paragraph>
                                </Descriptions.Item>
                            </Descriptions>
                            <Card size="small" title={`任务明细（${taskDetail.items.length}）`}>
                                <Table
                                    rowKey="id"
                                    size="small"
                                    pagination={false}
                                    dataSource={taskDetail.items}
                                    columns={[
                                        { title: 'SKU', dataIndex: 'sku', width: 140 },
                                        { title: '品名', dataIndex: 'productName', width: 180, render: (v?: string) => dash(v) },
                                        { title: '批次号', dataIndex: 'batchNo', width: 120, render: (v?: string) => dash(v) },
                                        { title: '应备', dataIndex: 'quantity', width: 80, align: 'right' },
                                        {
                                            title: '实备',
                                            dataIndex: 'pickedQuantity',
                                            width: 80,
                                            align: 'right',
                                            render: (v: number | undefined, row: { quantity: number; pickedQuantity: number }) =>
                                                v !== null && v !== undefined && v < row.quantity ? (
                                                    <Tag color="error">{v}</Tag>
                                                ) : (
                                                    v
                                                )
                                        },
                                        { title: '生产日期', dataIndex: 'productionDate', width: 110, render: (v?: string | null) => formatDate(v) },
                                        { title: '效期', dataIndex: 'expiryDate', width: 110, render: (v?: string | null) => formatDate(v) },
                                        { title: 'HS 编码', dataIndex: 'hsCode', width: 110, render: (v?: string) => dash(v) }
                                    ]}
                                />
                            </Card>
                            <Card size="small" title="FEFO 推荐批次（只推荐不强制，可一键改）">
                                <Table
                                    rowKey="id"
                                    size="small"
                                    pagination={false}
                                    dataSource={taskDetail.batchCandidates}
                                    locale={{
                                        emptyText: <Empty description="本仓这些 SKU 还没有库存批次" />
                                    }}
                                    columns={[
                                        { title: 'SKU', dataIndex: 'sku', width: 150 },
                                        { title: '批次号', dataIndex: 'batchNo', width: 130 },
                                        {
                                            title: '效期',
                                            dataIndex: 'expiryDate',
                                            width: 120,
                                            render: (v?: string | null) => formatDate(v)
                                        },
                                        { title: '可用', dataIndex: 'availableQty', width: 90, align: 'right' },
                                        { title: '库位', dataIndex: 'locationCode', width: 100, render: (v?: string) => dash(v) }
                                    ]}
                                />
                            </Card>
                        </Space>
                    ) : (
                        <Empty description="没有选中任务" />
                    )}
                </Spin>
            </Drawer>

            {/* 新建任务 */}
            <Modal
                title="新建备货任务"
                open={taskFormOpen}
                onCancel={() => setTaskFormOpen(false)}
                onOk={() => taskForm.submit()}
                okText="创建"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="填 OMS 出口子单号即可建立弱联动；M1 不由 OMS 派单，下游 OMS→WMS 直连是 M2 的事。"
                />
                <Form form={taskForm} layout="vertical" onFinish={createTask}>
                    <Form.Item
                        name="orderNo"
                        label="出口子单号"
                        rules={[{ required: true, message: '请填出口子单号，如 EXP20261010001' }]}
                    >
                        <Input placeholder="EXP + yyyyMMdd + 3 位" />
                    </Form.Item>
                    <Form.Item
                        name="warehouseId"
                        label="作业仓库"
                        rules={[{ required: true, message: '请选择作业仓库' }]}
                    >
                        <Select
                            showSearch
                            optionFilterProp="label"
                            options={warehouses.map((w) => ({ value: w.id, label: w.name }))}
                        />
                    </Form.Item>
                    <Form.Item name="locationCode" label="库位">
                        <Input placeholder="三段式，如 A-01-03" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 推进任务状态 */}
            <Modal
                title={advanceTarget ? `推进状态 · ${advanceTarget.taskNo}` : '推进状态'}
                open={advanceOpen}
                onCancel={() => setAdvanceOpen(false)}
                onOk={() => advanceForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="合法链路：待处理 → 拣货中 → 已装箱 → 已贴标 → 已交接。装箱时带上箱号与毛重体积。"
                />
                <Form form={advanceForm} layout="vertical" onFinish={submitAdvance}>
                    <Form.Item name="status" label="目标状态" rules={[{ required: true, message: '请选择状态' }]}>
                        <Select options={PACK_ADVANCE_OPTIONS} />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="boxNo" label="箱号" style={{ width: 240 }}>
                            <Input placeholder="留空则用 CTN-子单号-01" />
                        </Form.Item>
                        <Form.Item name="containerType" label="外包装" style={{ width: 160 }}>
                            <Select
                                options={[
                                    { value: 'CARTON', label: 'CARTON 纸箱' },
                                    { value: 'PALLET', label: 'PALLET 托盘' },
                                    { value: 'BAG', label: 'BAG 袋' },
                                    { value: 'DRUM', label: 'DRUM 桶' }
                                ]}
                            />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="pieces" label="件数" style={{ width: 120 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                        <Form.Item name="grossWeight" label="毛重 KG" style={{ width: 160 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                        <Form.Item name="volume" label="体积 CBM" style={{ width: 160 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                    </Space>
                    <Form.Item name="marks" label="唛头">
                        <Input.TextArea rows={3} placeholder="收货人抬头 / 单号 / 目的港 / 箱号 / MADE IN CHINA" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 新增库存批次 */}
            <Modal
                title="新增库存批次"
                open={batchFormOpen}
                onCancel={() => setBatchFormOpen(false)}
                onOk={() => batchForm.submit()}
                okText="创建"
                cancelText="取消"
                destroyOnClose
            >
                <Form
                    form={batchForm}
                    layout="vertical"
                    onFinish={async (values: Record<string, unknown>) => {
                        try {
                            await api.post('/wms/intl/inventory-batches', values)
                            message.success('库存批次已新增')
                            setBatchFormOpen(false)
                            loadBatches()
                            loadDashboard()
                        } catch (err) {
                            message.error(errorMessage(err, '新增库存批次失败'))
                        }
                    }}
                >
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="warehouseId" label="仓库" style={{ width: 220 }} rules={[{ required: true, message: '必填' }]}>
                            <Select
                                showSearch
                                optionFilterProp="label"
                                options={warehouses.map((w) => ({ value: w.id, label: w.name }))}
                            />
                        </Form.Item>
                        <Form.Item name="sku" label="SKU" style={{ width: 200 }} rules={[{ required: true, message: '必填' }]}>
                            <Input placeholder="IPH-15-BLK" />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="productName" label="品名" style={{ width: 240 }}>
                            <Input />
                        </Form.Item>
                        <Form.Item name="batchNo" label="批次号" style={{ width: 180 }} rules={[{ required: true, message: '必填' }]}>
                            <Input placeholder="B261001-A" />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="productionDate" label="生产日期" style={{ width: 170 }}>
                            <Input placeholder="2026-09-01" />
                        </Form.Item>
                        <Form.Item name="expiryDate" label="效期" style={{ width: 170 }}>
                            <Input placeholder="2027-09-01" />
                        </Form.Item>
                        <Form.Item name="locationCode" label="库位" style={{ width: 140 }}>
                            <Input placeholder="A-01-03" />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="quantity" label="在库数量" style={{ width: 140 }} rules={[{ required: true, message: '必填' }]}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                        <Form.Item name="reservedQty" label="占用数量" style={{ width: 140 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                        <Form.Item name="unitCost" label="单位成本" style={{ width: 140 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                    </Space>
                </Form>
            </Modal>

            {/* 在途状态 */}
            <Modal
                title={transitTarget ? `在途状态 · ${transitTarget.shipmentNo}` : '在途状态'}
                open={transitOpen}
                onCancel={() => setTransitOpen(false)}
                onOk={() => transitForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="入库（RECEIVED）在本期只落状态、不扣库存批次——库存占用/释放要与出库联动（M2）一起做才自洽。"
                />
                <Form form={transitForm} layout="vertical" onFinish={submitTransit}>
                    <Form.Item name="status" label="目标状态" rules={[{ required: true, message: '请选择状态' }]}>
                        <Select options={TRANSIT_STATUS_OPTIONS} />
                    </Form.Item>
                    <Form.Item name="toWarehouseId" label="目的仓（入库时用）">
                        <Select
                            allowClear
                            showSearch
                            optionFilterProp="label"
                            options={warehouses.map((w) => ({ value: w.id, label: w.name }))}
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    )
}

function packTaskStatusColor(code: string) {
    return PACK_TASK_STATUS[code]?.color ?? 'default'
}
