import {
    ApartmentOutlined,
    DollarOutlined,
    DownloadOutlined,
    ExclamationCircleOutlined,
    PlusOutlined,
    RocketOutlined
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
    Tooltip,
    Typography,
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import type {
    IntlCarrier,
    IntlExportBatch,
    IntlExportBatchDetail,
    IntlExportOrder,
    IntlExportOrderDetail,
    IntlExportOrderItem,
    IntlOmsDashboard,
    PageResult
} from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import {
    COUNTRY_OPTIONS,
    CURRENCIES,
    EXPORT_ORDER_STATUS,
    EXPORT_BATCH_STATUS,
    EXPORT_MODE,
    INCOTERMS,
    ModeTag,
    ExportBatchStatusTag,
    ExportOrderStatusTag,
    PackTaskStatusTag,
    ShipmentStatusTag,
    dash,
    formatCbm,
    formatDate,
    formatKg,
    formatMoney,
    formatUtc
} from './shared'

const ORDER_STATUS_OPTIONS = Object.entries(EXPORT_ORDER_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))
const BATCH_STATUS_OPTIONS = Object.entries(EXPORT_BATCH_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))
const MODE_OPTIONS = Object.entries(EXPORT_MODE).map(([value, meta]) => ({ value, label: meta.label }))
const INCOTERM_OPTIONS = INCOTERMS.map((v) => ({ value: v, label: v }))
const CURRENCY_OPTIONS = CURRENCIES.map((v) => ({ value: v, label: v }))

/**
 * 出口订单页（/intl/export-orders）——OMS 侧的国际子单与母单。
 *
 * 「运单/备货」几列是**跨库聚合**：后端拿 TMS/WMS 的数据回填，
 * 下游挂了列显示「—」而列表照常渲染（这是 M1 的明确取舍，
 * 见计划 §8.6）。所以这两列的空值是有含义的，不是「没数据」。
 */
export default function IntlOrdersPage() {
    const { hasPermission } = useAuth()
    const canEdit = hasPermission('oms:intl:edit')
    const [tab, setTab] = useState('orders')
    const [error, setError] = useState<string | null>(null)
    const [dashboard, setDashboard] = useState<IntlOmsDashboard | null>(null)

    // 子单列表
    const [orders, setOrders] = useState<IntlExportOrder[]>([])
    const [orderTotal, setOrderTotal] = useState(0)
    const [orderPage, setOrderPage] = useState(1)
    const [orderSize, setOrderSize] = useState(10)
    const [orderKeyword, setOrderKeyword] = useState('')
    const [orderStatus, setOrderStatus] = useState<string | undefined>()
    const [orderCountry, setOrderCountry] = useState<string | undefined>()
    const [orderIncoterm, setOrderIncoterm] = useState<string | undefined>()
    const [orderOnlyException, setOrderOnlyException] = useState(false)
    const [orderLoading, setOrderLoading] = useState(false)

    // 母单列表
    const [batches, setBatches] = useState<IntlExportBatch[]>([])
    const [batchTotal, setBatchTotal] = useState(0)
    const [batchPage, setBatchPage] = useState(1)
    const [batchSize, setBatchSize] = useState(10)
    const [batchKeyword, setBatchKeyword] = useState('')
    const [batchStatus, setBatchStatus] = useState<string | undefined>()
    const [batchLoading, setBatchLoading] = useState(false)

    // 详情 / 表单
    const [orderDetail, setOrderDetail] = useState<IntlExportOrderDetail | null>(null)
    const [orderDetailLoading, setOrderDetailLoading] = useState(false)
    const [batchDetail, setBatchDetail] = useState<IntlExportBatchDetail | null>(null)
    const [batchDetailLoading, setBatchDetailLoading] = useState(false)

    const [advanceOpen, setAdvanceOpen] = useState(false)
    const [advanceTarget, setAdvanceTarget] = useState<IntlExportOrder | null>(null)
    const [advanceForm] = Form.useForm<{ status: string; remark?: string }>()

    const [batchAdvanceOpen, setBatchAdvanceOpen] = useState(false)
    const [batchAdvanceTarget, setBatchAdvanceTarget] = useState<IntlExportBatch | null>(null)
    const [batchAdvanceForm] = Form.useForm<{ status: string; remark?: string }>()

    const [shipOpen, setShipOpen] = useState(false)
    const [shipTarget, setShipTarget] = useState<IntlExportBatch | null>(null)
    const [shipForm] = Form.useForm<{ carrierId?: number; mode?: string; containerType?: string; containerQty?: number }>()
    const [carriers, setCarriers] = useState<IntlCarrier[]>([])

    /**
     * batchNo → 运输方式。子单列表的「运输方式」列靠它渲染。
     *
     * 为什么需要自己拼这张表：M1 的子单接口不返回 mode（`ExportOrder` 里压根没这个字段，
     * mode 挂在母单上），而「这一行是海运还是快递」恰恰是列表最该回答的问题。
     * 用一次 `/oms/intl/batches/brief`（同租户全量母单，几十行量级）换掉每行一次请求。
     *
     * 为什么拉 brief 全量而不是当前页的母单分页：母单列表是分页的（第 2 页的子单在第 1 页
     * 根本查不到对应母单），拿分页当字典会让「运输方式」列随机地缺值——那比整列都没有更容易
     * 被当成「这行没有运输方式」。
     *
     * 拉不到就留空字典、列显示「—」：这一列是补上的读数，不是列表的生存条件。
     */
    const [batchModes, setBatchModes] = useState<Record<string, string>>({})

    const loadBatchModes = useCallback(
        () =>
            api
                .get<IntlExportBatch[]>('/oms/intl/batches/brief')
                .then((res) => {
                    const map: Record<string, string> = {}
                    for (const b of res.data ?? []) {
                        if (b.batchNo && b.mode) {
                            map[b.batchNo] = b.mode
                        }
                    }
                    setBatchModes(map)
                })
                .catch(() => setBatchModes({})),
        []
    )

    /** 母单增删/改运输方式后要让字典跟着变，否则这一列会悄悄过期。 */
    useEffect(() => {
        loadBatchModes()
    }, [loadBatchModes])

    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<IntlOmsDashboard>('/oms/intl/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载出口看板失败'))
        }
    }, [])

    const loadOrders = useCallback(async () => {
        setOrderLoading(true)
        try {
            const res = await api.get<PageResult<IntlExportOrder>>('/oms/intl/export-orders', {
                params: {
                    page: orderPage,
                    size: orderSize,
                    keyword: orderKeyword || undefined,
                    status: orderStatus || undefined,
                    consigneeCountry: orderCountry || undefined,
                    incoterm: orderIncoterm || undefined,
                    hasException: orderOnlyException || undefined
                }
            })
            setOrders(res.data.list)
            setOrderTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载出口子单列表失败'))
        } finally {
            setOrderLoading(false)
        }
    }, [orderPage, orderSize, orderKeyword, orderStatus, orderCountry, orderIncoterm, orderOnlyException])

    const loadBatches = useCallback(async () => {
        setBatchLoading(true)
        try {
            const res = await api.get<PageResult<IntlExportBatch>>('/oms/intl/batches', {
                params: {
                    page: batchPage,
                    size: batchSize,
                    keyword: batchKeyword || undefined,
                    status: batchStatus || undefined
                }
            })
            setBatches(res.data.list)
            setBatchTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载出口母单列表失败'))
        } finally {
            setBatchLoading(false)
        }
    }, [batchPage, batchSize, batchKeyword, batchStatus])

    useEffect(() => {
        loadDashboard()
    }, [loadDashboard])

    useEffect(() => {
        if (tab === 'orders') {
            loadOrders()
        } else if (tab === 'batches') {
            loadBatches()
        }
    }, [tab, loadOrders, loadBatches])

    // 承运商下拉只在点「发起订舱」时才拉——不是每个页面都要为一次弹窗付出一次请求
    useEffect(() => {
        if (!shipOpen) {
            return
        }
        api.get<IntlCarrier[]>('/tms/intl/carriers/brief')
            .then((res) => setCarriers(res.data ?? []))
            .catch(() => setCarriers([]))
    }, [shipOpen])

    const openOrderDetail = async (row: IntlExportOrder) => {
        setOrderDetailLoading(true)
        try {
            const res = await api.get<IntlExportOrderDetail>(`/oms/intl/export-orders/${row.id}`)
            setOrderDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载子单详情失败'))
        } finally {
            setOrderDetailLoading(false)
        }
    }

    const openBatchDetail = async (row: IntlExportBatch) => {
        setBatchDetailLoading(true)
        try {
            const res = await api.get<IntlExportBatchDetail>(`/oms/intl/batches/${row.id}`)
            setBatchDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载母单详情失败'))
        } finally {
            setBatchDetailLoading(false)
        }
    }

    const openAdvance = (row: IntlExportOrder) => {
        setAdvanceTarget(row)
        advanceForm.resetFields()
        setAdvanceOpen(true)
    }

    const submitAdvance = async (values: { status: string; remark?: string }) => {
        if (!advanceTarget) {
            return
        }
        try {
            await api.patch(`/oms/intl/export-orders/${advanceTarget.id}/status`, values)
            message.success('子单状态已推进')
            setAdvanceOpen(false)
            loadOrders()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '推进子单状态失败'))
        }
    }

    const openBatchAdvance = (row: IntlExportBatch) => {
        setBatchAdvanceTarget(row)
        batchAdvanceForm.resetFields()
        setBatchAdvanceOpen(true)
    }

    const submitBatchAdvance = async (values: { status: string; remark?: string }) => {
        if (!batchAdvanceTarget) {
            return
        }
        try {
            await api.patch(`/oms/intl/batches/${batchAdvanceTarget.id}/status`, values)
            message.success('母单状态已推进')
            setBatchAdvanceOpen(false)
            loadBatches()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '推进母单状态失败'))
        }
    }

    const openShip = (row: IntlExportBatch) => {
        setShipTarget(row)
        shipForm.resetFields()
        shipForm.setFieldsValue({ mode: row.mode, containerType: row.containerType, containerQty: row.containerQty })
        setShipOpen(true)
    }

    const submitShip = async (values: {
        carrierId?: number
        mode?: string
        containerType?: string
        containerQty?: number
    }) => {
        if (!shipTarget) {
            return
        }
        try {
            await api.post(`/oms/intl/batches/${shipTarget.id}/ship`, values)
            message.success('已在 TMS 建运单，母单推进到「订舱中」')
            setShipOpen(false)
            loadBatches()
            // 发起订舱是本页唯一可能改到 mode 的写操作，改完要让运输方式字典跟上
            loadBatchModes()
        } catch (err) {
            message.error(errorMessage(err, '发起订舱失败'))
        }
    }

    const orderColumns = [
        {
            title: '出口子单号',
            dataIndex: 'orderNo',
            width: 160,
            render: (v: string) => (
                <Typography.Text copyable={{ text: v }} style={{ fontWeight: 500 }}>
                    {v}
                </Typography.Text>
            )
        },
        { title: '客户', dataIndex: 'customerName', width: 200, render: (v?: string) => dash(v) },
        { title: '收货国', dataIndex: 'consigneeCountry', width: 90, render: (v?: string) => dash(v) },
        {
            title: '境外收货',
            dataIndex: 'consigneeName',
            width: 170,
            render: (v: string, row: IntlExportOrder) => (
                <div>
                    <div>{v}</div>
                    <div style={{ fontSize: 12, color: '#98a2b3' }}>{row.consigneeCompany || '—'}</div>
                </div>
            )
        },
        { title: '条款', dataIndex: 'incoterm', width: 80, render: (v?: string) => (v ? <Tag color="geekblue">{v}</Tag> : dash(v)) },
        {
            title: '订单金额',
            dataIndex: 'totalAmount',
            width: 140,
            align: 'right' as const,
            render: (v: number | undefined, row: IntlExportOrder) => (
                <span style={{ fontWeight: 500 }}>{formatMoney(v, row.currency ?? 'USD')}</span>
            )
        },
        { title: '件数', dataIndex: 'totalQty', width: 80, align: 'right' as const, render: (v?: number) => v ?? '—' },
        {
            title: '毛重 / 体积',
            width: 150,
            render: (_: unknown, row: IntlExportOrder) => (
                <div style={{ fontSize: 12, color: '#667085' }}>
                    <div>{formatKg(row.totalWeight)}</div>
                    <div>{formatCbm(row.totalVolume)}</div>
                </div>
            )
        },
        {
            title: '母单号',
            dataIndex: 'batchNo',
            width: 150,
            render: (v?: string) => (v ? <Tag>{v}</Tag> : dash(v))
        },
        /**
         * 运输方式（P2-1）。**这一列是「子单看起来像两票完全不同的货」的解药**：
         * `1Z999AA10123456801`（UPS 快递追踪号）和 `176-30874125`（航司空运单号）
         * 在界面上原本长得一样，而「运单号本身不告诉你这是海运还是快递」——
         * 没有这一列就只能靠猜承运商，猜错就会把一票快递当成海运整柜主线来审。
         *
         * 放在「母单号」之后、「运单状态 / 订舱单号」之前：这三列合起来才回答完
         * 「这票货归哪张母单 → 怎么运 → 走到哪一步 → 凭哪个单号查」，
         * 而这一列必须落在首屏（表格总宽 1700，1680 的屏能看 1600）才谈得上「一眼分清」。
         *
         * mode 取自母单字典（子单接口不带这个字段，见 batchModes 的注释），
         * 取不到就是「—」：空 Tag 是个有边框的空盒子，比「—」更容易被读成
         * 「有值但颜色没取到」，反而更像出了问题。
         */
        {
            title: '运输方式',
            dataIndex: 'batchNo',
            width: 90,
            render: (_: unknown, row: IntlExportOrder) => <ModeTag code={batchModes[row.batchNo ?? '']} />
        },
        {
            title: '运单状态',
            dataIndex: 'shipmentStatus',
            width: 110,
            // 状态色留给状态，运输方式只走左侧色条（为什么这么分工见 shared.tsx 的 ShipmentStatusTag）
            render: (v: string | null | undefined, row: IntlExportOrder) => (
                <ShipmentStatusTag code={v} mode={batchModes[row.batchNo ?? '']} />
            )
        },
        { title: '订舱单号', dataIndex: 'awbNo', width: 150, render: (v: string | null | undefined, row: IntlExportOrder) => dash(v ?? row.bookingNo) },
        {
            title: 'ETD / ETA',
            width: 150,
            render: (_: unknown, row: IntlExportOrder) => (
                <div style={{ fontSize: 12, color: '#667085' }}>
                    <div>{formatDate(row.etdDate)}</div>
                    <div>{formatDate(row.etaDate)}</div>
                </div>
            )
        },
        { title: '备货', dataIndex: 'packStatus', width: 100, render: (v: string | null | undefined) => <PackTaskStatusTag code={v} /> },
        { title: '状态', dataIndex: 'status', width: 110, render: (v: string) => <ExportOrderStatusTag code={v} /> },
        {
            title: '操作',
            width: 150,
            align: 'center' as const,
            render: (_: unknown, row: IntlExportOrder) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openOrderDetail(row)}>
                        详情
                    </Button>
                    {canEdit ? (
                        <Button type="link" size="small" onClick={() => openAdvance(row)}>
                            推进状态
                        </Button>
                    ) : null}
                </Space>
            )
        }
    ]

    const itemColumns = [
        { title: '行号', dataIndex: 'lineNo', width: 60, align: 'right' as const },
        {
            title: 'SKU',
            dataIndex: 'sku',
            width: 140,
            render: (v: string | undefined, row: IntlExportOrderItem) => (
                <Space size={4}>
                    <span>{v || '—'}</span>
                    {row.isDangerous ? (
                        <Tooltip
                            title={`UN${row.unNumber || ''} / class ${row.dgClass || '-'} / ${
                                row.packingInstruction || '-'
                            }${row.batteryWattHours ? ` / ${row.batteryWattHours}Wh` : ''}`}
                        >
                            <Tag color="error">危险品</Tag>
                        </Tooltip>
                    ) : null}
                </Space>
            )
        },
        { title: '品名', dataIndex: 'productName', width: 160, render: (v?: string) => dash(v) },
        {
            title: '报关品名',
            width: 200,
            render: (_: unknown, row: IntlExportOrderItem) => (
                <div style={{ fontSize: 12 }}>
                    <div>{row.customsName || '—'}</div>
                    <div style={{ color: '#98a2b3' }}>{row.customsNameEn || '—'}</div>
                </div>
            )
        },
        { title: 'HS 编码', dataIndex: 'hsCode', width: 110, render: (v?: string) => dash(v) },
        { title: '原产国', dataIndex: 'originCountry', width: 80, render: (v?: string) => dash(v) },
        { title: '数量', dataIndex: 'quantity', width: 80, align: 'right' as const },
        { title: '单价', dataIndex: 'unitPrice', width: 110, align: 'right' as const, render: (v: number | undefined) => formatMoney(v, 'USD') },
        { title: '申报货值', dataIndex: 'declaredValue', width: 130, align: 'right' as const, render: (v: number | undefined) => formatMoney(v, 'USD') },
        { title: '毛重', dataIndex: 'grossWeight', width: 90, align: 'right' as const, render: (v?: number) => dash(v) },
        { title: '体积', dataIndex: 'volume', width: 100, align: 'right' as const, render: (v?: number) => dash(v) }
    ]

    const batchColumns = [
        {
            title: '母单号',
            dataIndex: 'batchNo',
            width: 165,
            render: (v: string) => (
                <Typography.Text copyable={{ text: v }} style={{ fontWeight: 500 }}>
                    {v}
                </Typography.Text>
            )
        },
        { title: '方式', dataIndex: 'mode', width: 100, render: (v: string) => <ModeTag code={v} /> },
        {
            title: '航线',
            width: 200,
            render: (_: unknown, row: IntlExportBatch) => (
                <span>
                    {row.polCode || '—'} → {row.podCode || '—'}
                    {row.podName ? (
                        <span style={{ color: '#98a2b3', fontSize: 12 }}>（{row.podName}）</span>
                    ) : null}
                </span>
            )
        },
        {
            title: '箱型 × 箱量',
            width: 110,
            render: (_: unknown, row: IntlExportBatch) => dash(`${row.containerType || '—'}×${row.containerQty ?? 0}`)
        },
        { title: '件数', dataIndex: 'totalQty', width: 80, align: 'right' as const, render: (v?: number) => v ?? '—' },
        { title: '毛重', dataIndex: 'totalWeight', width: 110, align: 'right' as const, render: (v?: number) => formatKg(v) },
        { title: '体积', dataIndex: 'totalVolume', width: 110, align: 'right' as const, render: (v?: number) => formatCbm(v) },
        { title: 'ETD', dataIndex: 'etdDate', width: 110, render: (v?: string | null) => formatDate(v) },
        { title: 'ETA', dataIndex: 'etaDate', width: 110, render: (v?: string | null) => formatDate(v) },
        { title: '截关(UTC)', dataIndex: 'cutoffAt', width: 150, render: (v?: string | null) => formatUtc(v) },
        { title: '运单号', dataIndex: 'shipmentNo', width: 155, render: (v?: string | null) => dash(v) },
        { title: '状态', dataIndex: 'status', width: 110, render: (v: string) => <ExportBatchStatusTag code={v} /> },
        {
            title: '操作',
            width: 210,
            align: 'center' as const,
            render: (_: unknown, row: IntlExportBatch) => (
                <Space size={2}>
                    <Button type="link" size="small" onClick={() => openBatchDetail(row)}>
                        详情
                    </Button>
                    {canEdit ? (
                        <>
                            <Button type="link" size="small" onClick={() => openShip(row)}>
                                发起订舱
                            </Button>
                            <Button type="link" size="small" onClick={() => openBatchAdvance(row)}>
                                推进状态
                            </Button>
                        </>
                    ) : null}
                </Space>
            )
        }
    ]

    return (
        <div className="fade-in">
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="本月出口额（USD）"
                            value={dashboard?.monthAmountUsd ?? 0}
                            prefix={<DollarOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="本月出口件数"
                            value={dashboard?.monthQty ?? 0}
                            prefix={<DownloadOutlined style={{ color: '#2e90fa' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="在途子单"
                            value={dashboard?.inTransit ?? 0}
                            prefix={<RocketOutlined style={{ color: '#f79009' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="异常子单"
                            value={dashboard?.exceptionCount ?? 0}
                            prefix={<ExclamationCircleOutlined style={{ color: '#f04438' }} />}
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
                        key: 'orders',
                        label: (
                            <span>
                                <DownloadOutlined />
                                出口子单
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`出口子单 · 共 ${orderTotal} 条`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="子单状态"
                                            allowClear
                                            style={{ width: 120 }}
                                            options={ORDER_STATUS_OPTIONS}
                                            value={orderStatus}
                                            onChange={(v) => {
                                                setOrderPage(1)
                                                setOrderStatus(v)
                                            }}
                                        />
                                        <Select
                                            placeholder="收货国"
                                            allowClear
                                            style={{ width: 140 }}
                                            options={COUNTRY_OPTIONS}
                                            value={orderCountry}
                                            onChange={(v) => {
                                                setOrderPage(1)
                                                setOrderCountry(v)
                                            }}
                                        />
                                        <Select
                                            placeholder="贸易条款"
                                            allowClear
                                            style={{ width: 110 }}
                                            options={INCOTERM_OPTIONS}
                                            value={orderIncoterm}
                                            onChange={(v) => {
                                                setOrderPage(1)
                                                setOrderIncoterm(v)
                                            }}
                                        />
                                        <Input.Search
                                            placeholder="子单号 / 母单号 / 客户 / 收货人"
                                            allowClear
                                            style={{ width: 240 }}
                                            onSearch={(v) => {
                                                setOrderPage(1)
                                                setOrderKeyword(v)
                                            }}
                                        />
                                        <Space size={4}>
                                            <span style={{ fontSize: 12, color: '#667085' }}>只看异常</span>
                                            <Switch
                                                checked={orderOnlyException}
                                                onChange={(v) => {
                                                    setOrderPage(1)
                                                    setOrderOnlyException(v)
                                                }}
                                            />
                                        </Space>
                                        <Button onClick={loadOrders}>刷新</Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={orderLoading}
                                    columns={orderColumns}
                                    dataSource={orders}
                                    scroll={{ x: 1700 }}
                                    locale={{
                                        emptyText: (
                                            <Empty
                                                description={
                                                    orderKeyword || orderStatus || orderCountry
                                                        ? '没有符合条件的出口子单'
                                                        : '还没有出口子单，先到国际运单页面组批或新建'
                                                }
                                            />
                                        )
                                    }}
                                    pagination={{
                                        current: orderPage,
                                        pageSize: orderSize,
                                        total: orderTotal,
                                        showSizeChanger: true,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p, s) => {
                                            setOrderPage(p)
                                            setOrderSize(s)
                                        }
                                    }}
                                    onRow={(row) => ({
                                        onClick: () => openOrderDetail(row),
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
                                <ApartmentOutlined />
                                出口母单
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`出口母单（拼批 / 订舱）· 共 ${batchTotal} 条`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="母单状态"
                                            allowClear
                                            style={{ width: 130 }}
                                            options={BATCH_STATUS_OPTIONS}
                                            value={batchStatus}
                                            onChange={(v) => {
                                                setBatchPage(1)
                                                setBatchStatus(v)
                                            }}
                                        />
                                        <Input.Search
                                            placeholder="搜索母单号"
                                            allowClear
                                            style={{ width: 200 }}
                                            onSearch={(v) => {
                                                setBatchPage(1)
                                                setBatchKeyword(v)
                                            }}
                                        />
                                        <Button onClick={loadBatches}>刷新</Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={batchLoading}
                                    columns={batchColumns}
                                    dataSource={batches}
                                    scroll={{ x: 1500 }}
                                    locale={{
                                        emptyText: (
                                            <Empty description="还没有出口母单，先把已装箱的子单组批成一票货" />
                                        )
                                    }}
                                    pagination={{
                                        current: batchPage,
                                        pageSize: batchSize,
                                        total: batchTotal,
                                        showSizeChanger: true,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p, s) => {
                                            setBatchPage(p)
                                            setBatchSize(s)
                                        }
                                    }}
                                    onRow={(row) => ({
                                        onClick: () => openBatchDetail(row),
                                        style: { cursor: 'pointer' }
                                    })}
                                />
                            </Card>
                        )
                    }
                ]}
            />

            {/* 子单详情 */}
            <Drawer
                title={orderDetail ? `子单详情 · ${orderDetail.order.orderNo}` : '子单详情'}
                open={!!orderDetail}
                onClose={() => setOrderDetail(null)}
                width={860}
                destroyOnClose
            >
                <Spin spinning={orderDetailLoading}>
                    {orderDetail ? (
                        <Tabs
                            size="small"
                            items={[
                                {
                                    key: 'overview',
                                    label: '概览',
                                    children: (
                                        <Descriptions column={2} size="small" bordered>
                                            <Descriptions.Item label="母单号">
                                                {dash(orderDetail.order.batchNo)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="母单状态">
                                                <ExportBatchStatusTag code={orderDetail.batch?.status} />
                                            </Descriptions.Item>
                                            <Descriptions.Item label="客户" span={2}>
                                                {dash(orderDetail.order.customerName)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="境外收货人">
                                                {dash(orderDetail.order.consigneeName)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="收货公司">
                                                {dash(orderDetail.order.consigneeCompany)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="收货国">
                                                {dash(orderDetail.order.consigneeCountry)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="收货电话">
                                                {dash(orderDetail.order.consigneePhone)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="收货地址" span={2}>
                                                {dash(orderDetail.order.consigneeAddress)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="贸易条款">
                                                {dash(orderDetail.order.incoterm)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="币种">
                                                {dash(orderDetail.order.currency)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="订单金额">
                                                {formatMoney(orderDetail.order.totalAmount, orderDetail.order.currency)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="应收运费">
                                                {formatMoney(orderDetail.order.freightAmount, orderDetail.order.currency)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="ETD（当地）">
                                                {formatDate(orderDetail.order.etdDate)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="ETA（当地）">
                                                {formatDate(orderDetail.order.etaDate)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="状态">
                                                <ExportOrderStatusTag code={orderDetail.order.status} />
                                            </Descriptions.Item>
                                            <Descriptions.Item label="备注">
                                                {dash(orderDetail.order.remark)}
                                            </Descriptions.Item>
                                        </Descriptions>
                                    )
                                },
                                {
                                    key: 'items',
                                    label: `明细（${orderDetail.items.length}）`,
                                    children: (
                                        <Table
                                            rowKey="id"
                                            size="small"
                                            columns={itemColumns}
                                            dataSource={orderDetail.items}
                                            pagination={false}
                                            scroll={{ x: 1300 }}
                                        />
                                    )
                                },
                                {
                                    key: 'shipment',
                                    label: '母单与运单',
                                    children: (
                                        <Space direction="vertical" style={{ width: '100%' }} size={12}>
                                            <Descriptions column={2} size="small" bordered>
                                                <Descriptions.Item label="母单号">
                                                    {dash(orderDetail.batch?.batchNo)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="运输方式">
                                                    <ModeTag code={orderDetail.batch?.mode} />
                                                </Descriptions.Item>
                                                <Descriptions.Item label="航线">
                                                    {dash(`${orderDetail.batch?.polCode ?? '—'} → ${orderDetail.batch?.podCode ?? '—'}`)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="箱型 × 箱量">
                                                    {dash(
                                                        `${orderDetail.batch?.containerType || '—'}×${
                                                            orderDetail.batch?.containerQty ?? 0
                                                        }`
                                                    )}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="国际运单号">
                                                    {dash(orderDetail.shipment?.shipmentNo)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="运单状态">
                                                    <ShipmentStatusTag code={orderDetail.shipment?.status} />
                                                </Descriptions.Item>
                                                <Descriptions.Item label="AWB / 订舱号">
                                                    {dash(
                                                        orderDetail.shipment?.awbNo ||
                                                            orderDetail.shipment?.bookingNo ||
                                                            orderDetail.shipment?.blNo
                                                    )}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="ETD（UTC）">
                                                    {formatUtc(orderDetail.shipment?.etdAt)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="ETA（UTC）">
                                                    {formatUtc(orderDetail.shipment?.etaAt)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="截关（UTC）">
                                                    {formatUtc(orderDetail.batch?.cutoffAt)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="备货任务">
                                                    {orderDetail.packTask ? (
                                                        <Space size={4}>
                                                            <span>{orderDetail.packTask.taskNo}</span>
                                                            <PackTaskStatusTag code={orderDetail.packTask.status} />
                                                        </Space>
                                                    ) : (
                                                        dash(null)
                                                    )}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="箱号">
                                                    {dash(orderDetail.packTask?.boxNo)}
                                                </Descriptions.Item>
                                            </Descriptions>
                                            {orderDetail.packTask ? (
                                                <Alert
                                                    type="info"
                                                    showIcon
                                                    message="运输事实以 TMS 运单为准；出口子单状态是面向客户的展示层，允许滞后。"
                                                />
                                            ) : (
                                                <Alert
                                                    type="warning"
                                                    showIcon
                                                    message="该子单还没有备货任务：到「备货装箱」页填本单号建一条任务。"
                                                />
                                            )}
                                        </Space>
                                    )
                                }
                            ]}
                        />
                    ) : (
                        <Empty description="没有选中子单" />
                    )}
                </Spin>
            </Drawer>

            {/* 母单详情 */}
            <Drawer
                title={batchDetail ? `母单详情 · ${batchDetail.batch.batchNo}` : '母单详情'}
                open={!!batchDetail}
                onClose={() => setBatchDetail(null)}
                width={900}
                destroyOnClose
            >
                <Spin spinning={batchDetailLoading}>
                    {batchDetail ? (
                        <Space direction="vertical" style={{ width: '100%' }} size={12}>
                            <Descriptions column={2} size="small" bordered>
                                <Descriptions.Item label="运输方式">
                                    <ModeTag code={batchDetail.batch.mode} />
                                </Descriptions.Item>
                                <Descriptions.Item label="状态">
                                    <ExportBatchStatusTag code={batchDetail.batch.status} />
                                </Descriptions.Item>
                                <Descriptions.Item label="起运港">
                                    {dash(`${batchDetail.batch.polCode ?? '—'} ${batchDetail.batch.polName ?? ''}`)}
                                </Descriptions.Item>
                                <Descriptions.Item label="目的港">
                                    {dash(`${batchDetail.batch.podCode ?? '—'} ${batchDetail.batch.podName ?? ''}`)}
                                </Descriptions.Item>
                                <Descriptions.Item label="ETD（当地）">
                                    {formatDate(batchDetail.batch.etdDate)}
                                </Descriptions.Item>
                                <Descriptions.Item label="ETA（当地）">
                                    {formatDate(batchDetail.batch.etaDate)}
                                </Descriptions.Item>
                                <Descriptions.Item label="截关（UTC）">
                                    {formatUtc(batchDetail.batch.cutoffAt)}
                                </Descriptions.Item>
                                <Descriptions.Item label="国际运单号">
                                    {dash(batchDetail.shipment?.shipmentNo)}
                                </Descriptions.Item>
                            </Descriptions>
                            {batchDetail.shipment ? (
                                <Alert
                                    type="success"
                                    showIcon
                                    message={`TMS 已建运单 ${batchDetail.shipment.shipmentNo}，状态「${
                                        batchDetail.shipment.status ?? '—'
                                    }」`}
                                />
                            ) : (
                                <Alert
                                    type="info"
                                    showIcon
                                    message="还没发起订舱：点下面的「发起订舱」会在 TMS 建运单并把母单推进到「订舱中」。"
                                />
                            )}
                            <Card size="small" title={`本票子单（${batchDetail.orders.length}）`}>
                                <Table
                                    rowKey="id"
                                    size="small"
                                    pagination={false}
                                    dataSource={batchDetail.orders}
                                    columns={[
                                        {
                                            title: '子单号',
                                            dataIndex: 'orderNo',
                                            render: (v: string) => (
                                                <Typography.Text copyable={{ text: v }}>{v}</Typography.Text>
                                            )
                                        },
                                        { title: '客户', dataIndex: 'customerName', render: (v?: string) => dash(v) },
                                        { title: '件数', dataIndex: 'totalQty', align: 'right' },
                                        { title: '毛重', dataIndex: 'totalWeight', align: 'right', render: (v?: number) => formatKg(v) },
                                        { title: '状态', dataIndex: 'status', render: (v: string) => <ExportOrderStatusTag code={v} /> }
                                    ]}
                                />
                            </Card>
                            {canEdit ? (
                                <Space>
                                    <Button type="primary" onClick={() => openShip(batchDetail.batch)}>
                                        发起订舱
                                    </Button>
                                    <Button onClick={() => openBatchAdvance(batchDetail.batch)}>推进母单状态</Button>
                                </Space>
                            ) : null}
                        </Space>
                    ) : (
                        <Empty description="没有选中母单" />
                    )}
                </Spin>
            </Drawer>

            {/* 推进子单状态 */}
            <Modal
                title={advanceTarget ? `推进状态 · ${advanceTarget.orderNo}` : '推进状态'}
                open={advanceOpen}
                onCancel={() => setAdvanceOpen(false)}
                onOk={() => advanceForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Form form={advanceForm} layout="vertical" onFinish={submitAdvance} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="status"
                        label="目标状态"
                        rules={[{ required: true, message: '请选择目标状态' }]}
                    >
                        <Select options={ORDER_STATUS_OPTIONS} placeholder="选择目标状态" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={3} placeholder="选填，会覆盖原备注" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 推进母单状态 */}
            <Modal
                title={batchAdvanceTarget ? `推进母单状态 · ${batchAdvanceTarget.batchNo}` : '推进母单状态'}
                open={batchAdvanceOpen}
                onCancel={() => setBatchAdvanceOpen(false)}
                onOk={() => batchAdvanceForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Form form={batchAdvanceForm} layout="vertical" onFinish={submitBatchAdvance} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="status"
                        label="目标状态"
                        rules={[{ required: true, message: '请选择目标状态' }]}
                    >
                        <Select options={BATCH_STATUS_OPTIONS} placeholder="选择目标状态" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={3} placeholder="选填" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 发起订舱 */}
            <Modal
                title={shipTarget ? `发起订舱 · ${shipTarget.batchNo}` : '发起订舱'}
                open={shipOpen}
                onCancel={() => setShipOpen(false)}
                onOk={() => shipForm.submit()}
                okText="确认订舱"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="这一步会在 TMS 建国际运单（先查后建：已有运单则直接复用），并把母单推进到「订舱中」。下游不可用时整单回滚，可安全重试。"
                />
                <Form form={shipForm} layout="vertical" onFinish={submitShip}>
                    <Form.Item
                        name="carrierId"
                        label="承运商"
                        rules={[{ required: true, message: '必须先选承运商' }]}
                    >
                        <Select
                            showSearch
                            optionFilterProp="label"
                            placeholder="选择承运商"
                            options={carriers.map((c) => ({ value: c.id, label: `${c.code} · ${c.nameZh}` }))}
                        />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="mode" label="运输方式" style={{ width: 200 }}>
                            <Select options={MODE_OPTIONS} />
                        </Form.Item>
                        <Form.Item name="containerType" label="箱型" style={{ width: 160 }}>
                            <Select
                                options={[
                                    { value: '20GP', label: '20GP' },
                                    { value: '40GP', label: '40GP' },
                                    { value: '40HQ', label: '40HQ' },
                                    { value: 'LCL', label: 'LCL 拼箱' }
                                ]}
                            />
                        </Form.Item>
                        <Form.Item name="containerQty" label="箱量" style={{ width: 120 }}>
                            <Input type="number" min={0} />
                        </Form.Item>
                    </Space>
                </Form>
            </Modal>
        </div>
    )
}
