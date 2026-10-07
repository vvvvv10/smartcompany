import {
    AlertOutlined,
    ClockCircleOutlined,
    ContainerOutlined,
    FileProtectOutlined,
    PlusOutlined,
    SendOutlined
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
    Table,
    Tabs,
    Tag,
    Timeline,
    Tooltip,
    Typography,
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import type {
    IntlCarrier,
    IntlCustomsDeclaration,
    IntlShipment,
    IntlShipmentDetail,
    IntlShipmentItem,
    IntlTmsDashboard,
    IntlTrackingNode,
    PageResult
} from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import {
    DECLARATION_STATUS,
    EXPORT_MODE,
    ModeTag,
    DeclarationStatusTag,
    ShipmentStatusTag,
    TrackingSourceTag,
    dash,
    formatCbm,
    formatKg,
    formatMoney,
    formatUtc,
    isOverdue
} from './shared'

const SHIPMENT_STATUS_OPTIONS = Object.entries(
    // 只列业务上「能从 DRAFT 走到 DEPARTED」的推进态；终态（POD_CONFIRMED/RETURNED/CANCELLED）
    // 与 EXCEPTION 走单独入口，不放进日常推进下拉。
    {
        DRAFT: { label: '草稿', color: 'default' },
        BOOKING: { label: '订舱中', color: 'processing' },
        BOOKED: { label: '已订舱', color: 'cyan' },
        PICKED_UP: { label: '已揽收', color: 'blue' },
        EXPORT_DECLARED: { label: '已报关', color: 'geekblue' },
        DEPARTED: { label: '已开航', color: 'processing' },
        IN_TRANSIT: { label: '在途', color: 'processing' },
        ARRIVED: { label: '已到港', color: 'lime' },
        CLEARING: { label: '清关中', color: 'gold' },
        CLEARED: { label: '已清关', color: 'green' },
        LAST_MILE: { label: '尾程派送', color: 'cyan' },
        DELIVERED: { label: '已妥投', color: 'success' }
    }
).map(([value, meta]) => ({ value, label: meta.label }))

const MODE_OPTIONS = Object.entries(EXPORT_MODE).map(([value, meta]) => ({ value, label: meta.label }))

/**
 * 国际运单页（/intl/shipments）——TMS 侧。
 *
 * 「运输事实」以本页为准：运单状态是唯一真相，OMS 的出口子单状态只是展示层，
 * 允许滞后（这是 M1 刻意不做反向回调换来的可见性取舍）。
 * 详情里把两边的状态并排显示，让人一眼看出差异而不是猜。
 */
export default function IntlShipmentsPage() {
    const { hasPermission } = useAuth()
    const canEdit = hasPermission('tms:intl:edit')
    const [error, setError] = useState<string | null>(null)
    const [dashboard, setDashboard] = useState<IntlTmsDashboard | null>(null)

    const [rows, setRows] = useState<IntlShipment[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    const [keyword, setKeyword] = useState('')
    const [status, setStatus] = useState<string | undefined>()
    const [mode, setMode] = useState<string | undefined>()
    const [carrierId, setCarrierId] = useState<number | undefined>()
    const [loading, setLoading] = useState(false)
    const [carriers, setCarriers] = useState<IntlCarrier[]>([])

    const [detail, setDetail] = useState<IntlShipmentDetail | null>(null)
    const [detailLoading, setDetailLoading] = useState(false)

    const [advanceOpen, setAdvanceOpen] = useState(false)
    const [advanceTarget, setAdvanceTarget] = useState<IntlShipment | null>(null)
    const [advanceForm] = Form.useForm<{ status: string; remark?: string; exceptionReason?: string }>()

    const [trackingOpen, setTrackingOpen] = useState(false)
    const [trackingTarget, setTrackingTarget] = useState<IntlShipment | null>(null)
    const [trackingForm] = Form.useForm<{
        nodeCode: string
        nodeName?: string
        nodeTime: string
        location?: string
        remark?: string
    }>()

    const [customsOpen, setCustomsOpen] = useState(false)
    const [customsTarget, setCustomsTarget] = useState<IntlShipment | null>(null)
    const [customsForm] = Form.useForm<{
        customsBroker?: string
        brokerContact?: string
        hsCodeSummary?: string
        declaredValue?: number
        currency?: string
        dutyAmount?: number
        vatAmount?: number
        depositAmount?: number
        remark?: string
    }>()

    const [declStatusOpen, setDeclStatusOpen] = useState(false)
    const [declStatusTarget, setDeclStatusTarget] = useState<IntlCustomsDeclaration | null>(null)
    const [declStatusForm] = Form.useForm<{ status: string }>()

    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<IntlTmsDashboard>('/tms/intl/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载国际运单看板失败'))
        }
    }, [])

    const loadList = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<PageResult<IntlShipment>>('/tms/intl/shipments', {
                params: {
                    page,
                    size,
                    keyword: keyword || undefined,
                    status: status || undefined,
                    mode: mode || undefined,
                    carrierId: carrierId || undefined
                }
            })
            setRows(res.data.list)
            setTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载国际运单列表失败'))
        } finally {
            setLoading(false)
        }
    }, [page, size, keyword, status, mode, carrierId])

    useEffect(() => {
        loadDashboard()
    }, [loadDashboard])

    useEffect(() => {
        loadList()
    }, [loadList])

    useEffect(() => {
        api.get<IntlCarrier[]>('/tms/intl/carriers/brief')
            .then((res) => setCarriers(res.data ?? []))
            .catch(() => setCarriers([]))
    }, [])

    const openDetail = async (row: IntlShipment) => {
        setDetailLoading(true)
        try {
            const res = await api.get<IntlShipmentDetail>(`/tms/intl/shipments/${row.id}`)
            setDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载运单详情失败'))
        } finally {
            setDetailLoading(false)
        }
    }

    const openAdvance = (row: IntlShipment) => {
        setAdvanceTarget(row)
        advanceForm.resetFields()
        setAdvanceOpen(true)
    }

    const submitAdvance = async (values: { status: string; remark?: string; exceptionReason?: string }) => {
        if (!advanceTarget) {
            return
        }
        try {
            await api.patch(`/tms/intl/shipments/${advanceTarget.id}/status`, values)
            message.success('运单状态已推进，并记录了一条轨迹')
            setAdvanceOpen(false)
            loadList()
            loadDashboard()
            if (detail?.shipment.id === advanceTarget.id) {
                openDetail(advanceTarget)
            }
        } catch (err) {
            message.error(errorMessage(err, '推进运单状态失败'))
        }
    }

    const openTracking = (row: IntlShipment) => {
        setTrackingTarget(row)
        trackingForm.resetFields()
        trackingForm.setFieldsValue({ nodeTime: new Date().toISOString().slice(0, 19) })
        setTrackingOpen(true)
    }

    const submitTracking = async (values: {
        nodeCode: string
        nodeName?: string
        nodeTime: string
        location?: string
        remark?: string
    }) => {
        if (!trackingTarget) {
            return
        }
        try {
            await api.post(`/tms/intl/shipments/${trackingTarget.id}/tracking`, values)
            message.success('轨迹已记录（来源：人工）')
            setTrackingOpen(false)
            if (detail?.shipment.id === trackingTarget.id) {
                openDetail(trackingTarget)
            }
        } catch (err) {
            message.error(errorMessage(err, '记录轨迹失败'))
        }
    }

    const openCustoms = (row: IntlShipment) => {
        setCustomsTarget(row)
        customsForm.resetFields()
        customsForm.setFieldsValue({ currency: 'USD' })
        setCustomsOpen(true)
    }

    const submitCustoms = async (values: Record<string, unknown>) => {
        if (!customsTarget) {
            return
        }
        try {
            await api.post('/tms/intl/customs', { ...values, shipmentId: customsTarget.id })
            message.success('报关单已创建（DRAFT）')
            setCustomsOpen(false)
            openDetail(customsTarget)
            loadList()
        } catch (err) {
            message.error(errorMessage(err, '创建报关单失败'))
        }
    }

    const openDeclStatus = (declaration: IntlCustomsDeclaration) => {
        setDeclStatusTarget(declaration)
        declStatusForm.resetFields()
        setDeclStatusOpen(true)
    }

    const submitDeclStatus = async (values: { status: string }) => {
        if (!declStatusTarget) {
            return
        }
        try {
            await api.patch(`/tms/intl/customs/${declStatusTarget.id}/status`, values)
            message.success(
                values.status === 'RELEASED'
                    ? '报关单已放行，运单同步推进到「已报关」'
                    : '报关单状态已推进'
            )
            setDeclStatusOpen(false)
            if (detail) {
                openDetail(detail.shipment)
            }
        } catch (err) {
            message.error(errorMessage(err, '推进报关单状态失败'))
        }
    }

    const deleteShipment = async (row: IntlShipment) => {
        try {
            await api.delete(`/tms/intl/shipments/${row.id}`)
            message.success(`已删除运单「${row.shipmentNo}」`)
            loadList()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '删除运单失败'))
        }
    }

    const columns = [
        {
            title: '运单号',
            dataIndex: 'shipmentNo',
            width: 165,
            render: (v: string) => (
                <Typography.Text copyable={{ text: v }} style={{ fontWeight: 500 }}>
                    {v}
                </Typography.Text>
            )
        },
        { title: '母单号', dataIndex: 'batchNo', width: 155, render: (v?: string) => dash(v) },
        { title: '方式', dataIndex: 'mode', width: 100, render: (v: string) => <ModeTag code={v} /> },
        {
            title: '承运商',
            dataIndex: 'carrierName',
            width: 130,
            render: (v?: string | null) => (v ? v : dash(null))
        },
        {
            title: '航线',
            width: 150,
            render: (_: unknown, row: IntlShipment) => (
                <span>
                    {row.polCode || '—'} → {row.podCode || '—'}
                </span>
            )
        },
        {
            title: '箱型 × 箱量',
            width: 110,
            render: (_: unknown, row: IntlShipment) =>
                row.containerType ? `${row.containerType}×${row.containerQty ?? 0}` : dash(null)
        },
        {
            // 列名不叫「空运单号」：awb_no 这一列实际同时装 IATA 空运单号（176-30874125）、
            // 海运提单号与快递追踪号（1Z…）——快递没有 AWB。叫「运输单号」并用 tooltip
            // 说明各模式该填什么，比让用户对着列名猜要靠得住（P1-3 的最低成本修法）。
            title: (
                <Tooltip title="海运填提单号 BL（如 COSU6123456）；空运填 IATA AWB（如 176-30874125）；快递填承运商追踪号（如 1Z…）">
                    <span>运输单号</span>
                </Tooltip>
            ),
            width: 150,
            render: (_: unknown, row: IntlShipment) => {
                const text = row.blNo || row.awbNo
                if (!text) {
                    return dash(null)
                }
                return (
                    <Tooltip title={text}>
                        <Typography.Text ellipsis style={{ maxWidth: 140 }}>
                            {text}
                        </Typography.Text>
                    </Tooltip>
                )
            }
        },
        { title: '订舱号', dataIndex: 'bookingNo', width: 140, render: (v?: string) => dash(v) },
        { title: '件数', dataIndex: 'totalPieces', width: 80, align: 'right' as const, render: (v?: number) => v ?? '—' },
        { title: '毛重', dataIndex: 'totalGrossWeight', width: 110, align: 'right' as const, render: (v?: number) => formatKg(v) },
        { title: '计费重', dataIndex: 'chargeableWeight', width: 110, align: 'right' as const, render: (v?: number) => formatKg(v) },
        {
            title: 'ETD（UTC）',
            dataIndex: 'etdAt',
            width: 150,
            render: (v: string | null | undefined, row: IntlShipment) => (
                <span style={{ color: isOverdue(v, row.status) ? '#f04438' : '#667085', fontSize: 13 }}>
                    {formatUtc(v)}
                </span>
            )
        },
        { title: 'ETA（UTC）', dataIndex: 'etaAt', width: 150, render: (v?: string | null) => <span style={{ fontSize: 13 }}>{formatUtc(v)}</span> },
        { title: '状态', dataIndex: 'status', width: 110, render: (v: string) => <ShipmentStatusTag code={v} /> },
        {
            title: '操作',
            width: 220,
            align: 'center' as const,
            render: (_: unknown, row: IntlShipment) => (
                <Space size={2}>
                    <Button type="link" size="small" onClick={() => openDetail(row)}>
                        详情
                    </Button>
                    {canEdit ? (
                        <>
                            <Button type="link" size="small" onClick={() => openAdvance(row)}>
                                推进状态
                            </Button>
                            <Button type="link" size="small" onClick={() => openTracking(row)}>
                                录轨迹
                            </Button>
                            <Button type="link" size="small" onClick={() => openCustoms(row)}>
                                报关
                            </Button>
                            {row.status === 'DRAFT' ? (
                                <Popconfirm
                                    title={`删除运单「${row.shipmentNo}」？`}
                                    okText="删除"
                                    cancelText="取消"
                                    okButtonProps={{ danger: true }}
                                    onConfirm={() => deleteShipment(row)}
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

    const itemColumns = [
        { title: '分单号', dataIndex: 'houseNo', width: 175, render: (v?: string) => dash(v) },
        { title: '出口子单号', dataIndex: 'orderNo', width: 150, render: (v?: string) => dash(v) },
        { title: '集装箱号', dataIndex: 'containerNo', width: 130, render: (v?: string) => dash(v) },
        { title: '箱型', dataIndex: 'containerType', width: 90, render: (v?: string) => dash(v) },
        { title: '封条号', dataIndex: 'sealNo', width: 120, render: (v?: string) => dash(v) },
        {
            title: '唛头',
            dataIndex: 'marks',
            width: 240,
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
        { title: '件数', dataIndex: 'pieces', width: 80, align: 'right' as const },
        { title: '毛重', dataIndex: 'grossWeight', width: 110, align: 'right' as const, render: (v?: number) => formatKg(v) },
        { title: '体积', dataIndex: 'volume', width: 110, align: 'right' as const, render: (v?: number) => formatCbm(v) },
        {
            title: '危险品',
            dataIndex: 'isDangerous',
            width: 100,
            render: (v: number | undefined, row: IntlShipmentItem) =>
                v ? <Tag color="error">{row.unNumber || '危险品'}</Tag> : dash(null)
        }
    ]

    return (
        <div className="fade-in">
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="运单总数"
                            value={dashboard?.shipment.total ?? 0}
                            prefix={<ContainerOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="在途"
                            value={dashboard?.shipment.inTransit ?? 0}
                            prefix={<SendOutlined style={{ color: '#f79009' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="未来 7 天出港"
                            value={dashboard?.etd.next7Days ?? 0}
                            prefix={<ClockCircleOutlined style={{ color: '#2e90fa' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={6}>
                    <Card className="stat-card">
                        <Statistic
                            title="异常"
                            value={dashboard?.shipment.exception ?? 0}
                            prefix={<AlertOutlined style={{ color: '#f04438' }} />}
                        />
                    </Card>
                </Col>
            </Row>

            <Card
                className="table-card"
                style={{ marginTop: 16 }}
                title={`国际运单 · 共 ${total} 条`}
                extra={
                    <Space wrap>
                        <Select
                            placeholder="运单状态"
                            allowClear
                            style={{ width: 130 }}
                            options={Object.entries({
                                DRAFT: { label: '草稿', color: 'default' },
                                BOOKING: { label: '订舱中', color: 'processing' },
                                BOOKED: { label: '已订舱', color: 'cyan' },
                                PICKED_UP: { label: '已揽收', color: 'blue' },
                                EXPORT_DECLARED: { label: '已报关', color: 'geekblue' },
                                DEPARTED: { label: '已开航', color: 'processing' },
                                IN_TRANSIT: { label: '在途', color: 'processing' },
                                ARRIVED: { label: '已到港', color: 'lime' },
                                CLEARING: { label: '清关中', color: 'gold' },
                                CLEARED: { label: '已清关', color: 'green' },
                                LAST_MILE: { label: '尾程派送', color: 'cyan' },
                                DELIVERED: { label: '已妥投', color: 'success' },
                                POD_CONFIRMED: { label: '签收已回传', color: 'success' },
                                EXCEPTION: { label: '异常', color: 'error' },
                                CANCELLED: { label: '已取消', color: 'default' }
                            }).map(([value, m]) => ({ value, label: m.label }))}
                            value={status}
                            onChange={(v) => {
                                setPage(1)
                                setStatus(v)
                            }}
                        />
                        <Select
                            placeholder="运输方式"
                            allowClear
                            style={{ width: 140 }}
                            options={MODE_OPTIONS}
                            value={mode}
                            onChange={(v) => {
                                setPage(1)
                                setMode(v)
                            }}
                        />
                        <Select
                            placeholder="承运商"
                            allowClear
                            showSearch
                            optionFilterProp="label"
                            style={{ width: 180 }}
                            options={carriers.map((c) => ({ value: c.id, label: c.nameZh }))}
                            value={carrierId}
                            onChange={(v) => {
                                setPage(1)
                                setCarrierId(v)
                            }}
                        />
                        <Input.Search
                            placeholder="运单号 / AWB / BL / S/O / 箱号 / 唛头"
                            allowClear
                            style={{ width: 260 }}
                            onSearch={(v) => {
                                setPage(1)
                                setKeyword(v)
                            }}
                        />
                        <Button onClick={loadList}>刷新</Button>
                    </Space>
                }
            >
                <Table
                    rowKey="id"
                    loading={loading}
                    columns={columns}
                    dataSource={rows}
                    scroll={{ x: 1900 }}
                    locale={{
                        emptyText: (
                            <Empty description="还没有国际运单。到「出口订单」的母单详情里点「发起订舱」会自动生成运单" />
                        )
                    }}
                    pagination={{
                        current: page,
                        pageSize: size,
                        total,
                        showSizeChanger: true,
                        showTotal: (n) => `共 ${n} 条`,
                        onChange: (p, s) => {
                            setPage(p)
                            setSize(s)
                        }
                    }}
                    onRow={(row) => ({
                        onClick: () => openDetail(row),
                        style: { cursor: 'pointer' }
                    })}
                />
            </Card>

            {/* 详情 */}
            <Drawer
                title={detail ? `运单详情 · ${detail.shipment.shipmentNo}` : '运单详情'}
                open={!!detail}
                onClose={() => setDetail(null)}
                width={980}
                destroyOnClose
            >
                <Spin spinning={detailLoading}>
                    {detail ? (
                        <Tabs
                            size="small"
                            items={[
                                {
                                    key: 'overview',
                                    label: '概览',
                                    children: (
                                        <Descriptions column={2} size="small" bordered>
                                            <Descriptions.Item label="母单号">{dash(detail.shipment.batchNo)}</Descriptions.Item>
                                            <Descriptions.Item label="状态">
                                                <ShipmentStatusTag code={detail.shipment.status} />
                                            </Descriptions.Item>
                                            <Descriptions.Item label="承运商">{dash(detail.shipment.carrierName)}</Descriptions.Item>
                                            <Descriptions.Item label="运输方式">
                                                <ModeTag code={detail.shipment.mode} />
                                            </Descriptions.Item>
                                            <Descriptions.Item label="航线">
                                                {dash(`${detail.shipment.polCode ?? '—'} → ${detail.shipment.podCode ?? '—'}`)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="箱型 × 箱量">
                                                {dash(`${detail.shipment.containerType || '—'}×${detail.shipment.containerQty ?? 0}`)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="运输单号">
                                                {/* 一列同时装 AWB / BL / 快递追踪号，标签不能只写 AWB（P1-3） */}
                                                {dash(detail.shipment.awbNo)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="BL 类型">
                                                {dash(detail.shipment.blType)}
                                                {detail.shipment.blIssuer ? `（签发：${detail.shipment.blIssuer}）` : ''}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="BL 主单号">{dash(detail.shipment.blNo)}</Descriptions.Item>
                                            <Descriptions.Item label="订舱号">{dash(detail.shipment.bookingNo)}</Descriptions.Item>
                                            <Descriptions.Item label="贸易条款">{dash(detail.shipment.incoterm)}</Descriptions.Item>
                                            <Descriptions.Item label="件数">{detail.shipment.totalPieces ?? '—'}</Descriptions.Item>
                                            <Descriptions.Item label="毛重">{formatKg(detail.shipment.totalGrossWeight)}</Descriptions.Item>
                                            <Descriptions.Item label="体积">{formatCbm(detail.shipment.totalVolume)}</Descriptions.Item>
                                            <Descriptions.Item label="体积重">
                                                {formatKg(detail.shipment.volumetricWeight)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="计费重">
                                                {/* 海运按 W/M（max(毛重, 体积 CBM)），空运/快递按 max(毛重, 体积重) */}
                                                {formatKg(detail.shipment.chargeableWeight)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="VGM（核实总重）">
                                                {/* SOLAS Method 2 = 货物+包装 + 集装箱自重；空运/快递不适用 */}
                                                {formatKg(detail.shipment.vgmWeight)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="集装箱自重">
                                                {formatKg(detail.shipment.containerTareWeight)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="ETD / ETA（UTC）">
                                                {formatUtc(detail.shipment.etdAt)} / {formatUtc(detail.shipment.etaAt)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="截关（UTC）">
                                                {formatUtc(detail.shipment.cutoffAt)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="实际开航（UTC）">
                                                {formatUtc(detail.shipment.actualEtdAt)}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="危险品">
                                                {detail.shipment.isDangerous ? (
                                                    <Tag color="error">
                                                        {detail.shipment.unNumber || '有'} · class{' '}
                                                        {detail.shipment.dgClass || '-'}
                                                    </Tag>
                                                ) : (
                                                    dash(null)
                                                )}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="制裁筛查">
                                                {detail.shipment.sanctionFlag === 'HIT' ? (
                                                    <Tag color="error">HIT（需人工放行）</Tag>
                                                ) : (
                                                    <Tag color="success">{detail.shipment.sanctionFlag || 'CLEAR'}</Tag>
                                                )}
                                            </Descriptions.Item>
                                            <Descriptions.Item label="IOSS">{dash(detail.shipment.iossNo)}</Descriptions.Item>
                                            <Descriptions.Item label="异常原因" span={2}>
                                                {dash(detail.shipment.exceptionReason)}
                                            </Descriptions.Item>
                                        </Descriptions>
                                    )
                                },
                                {
                                    key: 'items',
                                    label: `箱与分单（${detail.items.length}）`,
                                    children: (
                                        <Table
                                            rowKey="id"
                                            size="small"
                                            columns={itemColumns}
                                            dataSource={detail.items}
                                            pagination={false}
                                            scroll={{ x: 1300 }}
                                        />
                                    )
                                },
                                {
                                    key: 'tracking',
                                    label: `轨迹（${detail.trackingNodes.length}）`,
                                    children: (
                                        <Space direction="vertical" style={{ width: '100%' }} size={12}>
                                            <Timeline
                                                items={detail.trackingNodes.map((node: IntlTrackingNode) => ({
                                                    color:
                                                        node.source === 'CARRIER_CALLBACK'
                                                            ? 'green'
                                                            : node.source === 'SCHEDULED'
                                                              ? 'purple'
                                                              : 'blue',
                                                    children: (
                                                        <div>
                                                            <Space size={6}>
                                                                <strong>{node.nodeName || node.nodeCode}</strong>
                                                                <TrackingSourceTag code={node.source} />
                                                                {node.operator ? (
                                                                    <span style={{ fontSize: 12, color: '#98a2b3' }}>
                                                                        {node.operator}
                                                                    </span>
                                                                ) : null}
                                                            </Space>
                                                            <div style={{ fontSize: 12, color: '#667085' }}>
                                                                {formatUtc(node.nodeTime)}
                                                                {node.location ? ` · ${node.location}` : ''}
                                                            </div>
                                                            {node.remark ? (
                                                                <div style={{ fontSize: 12, color: '#98a2b3' }}>
                                                                    {node.remark}
                                                                </div>
                                                            ) : null}
                                                        </div>
                                                    )
                                                }))}
                                            />
                                            {detail.trackingNodes.length === 0 ? (
                                                <Empty description="还没有轨迹节点" />
                                            ) : null}
                                            {canEdit ? (
                                                <Button icon={<PlusOutlined />} onClick={() => openTracking(detail.shipment)}>
                                                    录入轨迹节点
                                                </Button>
                                            ) : null}
                                        </Space>
                                    )
                                },
                                {
                                    key: 'customs',
                                    label: '报关',
                                    children: detail.declaration ? (
                                        <Space direction="vertical" style={{ width: '100%' }} size={12}>
                                            <Descriptions column={2} size="small" bordered>
                                                <Descriptions.Item label="报关单号">
                                                    {detail.declaration.declarationNo}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="状态">
                                                    <DeclarationStatusTag code={detail.declaration.status} />
                                                </Descriptions.Item>
                                                <Descriptions.Item label="报关行">
                                                    {dash(detail.declaration.customsBroker)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="联系电话">
                                                    {dash(detail.declaration.brokerContact)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="HS 编码汇总" span={2}>
                                                    {dash(detail.declaration.hsCodeSummary)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="申报货值">
                                                    {formatMoney(
                                                        detail.declaration.declaredValue,
                                                        detail.declaration.currency
                                                    )}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="关税">
                                                    {formatMoney(detail.declaration.dutyAmount, detail.declaration.currency)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="增值税">
                                                    {formatMoney(detail.declaration.vatAmount, detail.declaration.currency)}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="消费税">
                                                    {formatMoney(
                                                        detail.declaration.consumptionTax,
                                                        detail.declaration.currency
                                                    )}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="保证金">
                                                    {formatMoney(
                                                        detail.declaration.depositAmount,
                                                        detail.declaration.currency
                                                    )}
                                                </Descriptions.Item>
                                                <Descriptions.Item label="申报 / 放行（UTC）">
                                                    {formatUtc(detail.declaration.filedAt)} /{' '}
                                                    {formatUtc(detail.declaration.releasedAt)}
                                                </Descriptions.Item>
                                            </Descriptions>
                                            {canEdit ? (
                                                <Button
                                                    icon={<FileProtectOutlined />}
                                                    onClick={() => openDeclStatus(detail.declaration!)}
                                                >
                                                    推进报关状态
                                                </Button>
                                            ) : null}
                                        </Space>
                                    ) : (
                                        <Space direction="vertical" style={{ width: '100%' }}>
                                            <Empty description="该运单还没有报关单" />
                                            {canEdit ? (
                                                <Button
                                                    type="primary"
                                                    icon={<FileProtectOutlined />}
                                                    onClick={() => openCustoms(detail.shipment)}
                                                >
                                                    新建报关单
                                                </Button>
                                            ) : null}
                                        </Space>
                                    )
                                }
                            ]}
                        />
                    ) : (
                        <Empty description="没有选中运单" />
                    )}
                </Spin>
            </Drawer>

            {/* 推进状态 */}
            <Modal
                title={advanceTarget ? `推进状态 · ${advanceTarget.shipmentNo}` : '推进状态'}
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
                    message="非法迁移会被服务端状态机拒掉（400）。进「异常」必须填异常原因；制裁筛查为 HIT 时不能推进到「已报关」。"
                />
                <Form form={advanceForm} layout="vertical" onFinish={submitAdvance}>
                    <Form.Item name="status" label="目标状态" rules={[{ required: true, message: '请选择目标状态' }]}>
                        <Select options={SHIPMENT_STATUS_OPTIONS} placeholder="选择目标状态" />
                    </Form.Item>
                    <Form.Item noStyle shouldUpdate={(prev, next) => prev.status !== next.status}>
                        {({ getFieldValue }) =>
                            getFieldValue('status') === 'EXCEPTION' ? (
                                <Form.Item
                                    name="exceptionReason"
                                    label="异常原因"
                                    rules={[{ required: true, message: '异常必须写原因' }]}
                                >
                                    <Input.TextArea rows={3} placeholder="例：海关随机查验，整柜暂扣在洋山港" />
                                </Form.Item>
                            ) : null
                        }
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} placeholder="选填" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 录轨迹 */}
            <Modal
                title={trackingTarget ? `录入轨迹 · ${trackingTarget.shipmentNo}` : '录入轨迹'}
                open={trackingOpen}
                onCancel={() => setTrackingOpen(false)}
                onOk={() => trackingForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="来源固定记「人工」——前端不能传 source，否则这一列就失去了它唯一的存在意义。节点时间按 UTC 存。"
                />
                <Form form={trackingForm} layout="vertical" onFinish={submitTracking}>
                    <Form.Item name="nodeCode" label="节点码" rules={[{ required: true, message: '请选择节点码' }]}>
                        <Select
                            options={[
                                { value: 'BOOKED', label: 'BOOKED 已订舱' },
                                { value: 'PICKED_UP', label: 'PICKED_UP 已揽收' },
                                { value: 'EXPORT_DECLARED', label: 'EXPORT_DECLARED 已报关' },
                                { value: 'DEPARTED', label: 'DEPARTED 已开航' },
                                { value: 'IN_TRANSIT', label: 'IN_TRANSIT 在途' },
                                { value: 'ARRIVED', label: 'ARRIVED 已到港' },
                                { value: 'CLEARING', label: 'CLEARING 清关中' },
                                { value: 'CLEARED', label: 'CLEARED 已清关' },
                                { value: 'LAST_MILE', label: 'LAST_MILE 尾程派送' },
                                { value: 'DELIVERED', label: 'DELIVERED 已妥投' },
                                { value: 'EXCEPTION', label: 'EXCEPTION 异常' }
                            ]}
                        />
                    </Form.Item>
                    <Form.Item name="nodeName" label="节点名（中文）">
                        <Input placeholder="留空则用节点码" />
                    </Form.Item>
                    <Form.Item
                        name="nodeTime"
                        label="节点时间（UTC）"
                        rules={[{ required: true, message: '请填写节点时间' }]}
                    >
                        <Input placeholder="2026-10-14T01:40:00" />
                    </Form.Item>
                    <Form.Item name="location" label="节点地点">
                        <Input placeholder="例：上海洋山港" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 新建报关单 */}
            <Modal
                title={customsTarget ? `新建报关单 · ${customsTarget.shipmentNo}` : '新建报关单'}
                open={customsOpen}
                onCancel={() => setCustomsOpen(false)}
                onOk={() => customsForm.submit()}
                okText="创建"
                cancelText="取消"
                width={620}
                destroyOnClose
            >
                <Form form={customsForm} layout="vertical" onFinish={submitCustoms}>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="customsBroker" label="报关行" style={{ width: 240 }}>
                            <Input placeholder="例：速通关报关行" />
                        </Form.Item>
                        <Form.Item name="brokerContact" label="联系电话" style={{ width: 180 }}>
                            <Input placeholder="138xxxx" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="hsCodeSummary" label="HS 编码汇总">
                        <Input placeholder="逗号分隔，例：8517130000,8518301000" />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="declaredValue" label="申报货值" style={{ width: 180 }}>
                            <Input type="number" min={0} />
                        </Form.Item>
                        <Form.Item name="currency" label="币种" style={{ width: 120 }}>
                            <Select options={[{ value: 'USD' }, { value: 'CNY' }, { value: 'EUR' }]} />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="dutyAmount" label="关税" style={{ width: 150 }}>
                            <Input type="number" min={0} />
                        </Form.Item>
                        <Form.Item name="vatAmount" label="增值税" style={{ width: 150 }}>
                            <Input type="number" min={0} />
                        </Form.Item>
                        <Form.Item name="depositAmount" label="保证金" style={{ width: 150 }}>
                            <Input type="number" min={0} />
                        </Form.Item>
                    </Space>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 推进报关状态 */}
            <Modal
                title={declStatusTarget ? `推进报关状态 · ${declStatusTarget.declarationNo}` : '推进报关状态'}
                open={declStatusOpen}
                onCancel={() => setDeclStatusOpen(false)}
                onOk={() => declStatusForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="放行（RELEASED）会把运单从「已订舱/已揽收」同步推进到「已报关」。非法迁移会被报关单状态机拒掉。"
                />
                <Form form={declStatusForm} layout="vertical" onFinish={submitDeclStatus}>
                    <Form.Item name="status" label="目标状态" rules={[{ required: true, message: '请选择状态' }]}>
                        <Select
                            options={Object.entries(DECLARATION_STATUS).map(([value, m]) => ({
                                value,
                                label: m.label
                            }))}
                        />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    )
}
