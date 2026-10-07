import {
    CarOutlined,
    DashboardOutlined,
    DeleteOutlined,
    EditOutlined,
    FundOutlined,
    PlusOutlined,
    TeamOutlined,
    TruckOutlined
} from '@ant-design/icons'
import {
    Alert,
    Button,
    Card,
    Col,
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
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import type {
    OmsOrder,
    PageResult,
    TmsCostComparison,
    TmsDashboard,
    TmsDispatchRecommendation,
    TmsDriver,
    TmsOrder,
    TmsVehicle
} from '../../api/types'
import { OrderStatusTag as OmsOrderStatusTag, channelMeta } from '../oms/shared'
import {
    DRIVER_STATUS,
    ORDER_STATUS,
    VEHICLE_STATUS,
    DriverStatusTag,
    OrderStatusTag,
    VehicleStatusTag,
    driverStatusMeta,
    formatTime,
    orderStatusMeta,
    vehicleStatusMeta
} from './shared'

const ORDER_STATUS_OPTIONS = Object.entries(ORDER_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const VEHICLE_STATUS_OPTIONS = Object.entries(VEHICLE_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const DRIVER_STATUS_OPTIONS = Object.entries(DRIVER_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

export default function TmsPage() {
    const [tab, setTab] = useState('orders')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    // 看板数据
    const [dashboard, setDashboard] = useState<TmsDashboard | null>(null)

    // 订单列表
    const [orders, setOrders] = useState<TmsOrder[]>([])
    const [orderTotal, setOrderTotal] = useState(0)
    /** OMS 订单简表索引（orderNo → 订单），「OMS 订单」反向列 join 用 */
    const [omsBrief, setOmsBrief] = useState<Record<string, OmsOrder>>({})
    const [orderPage, setOrderPage] = useState(1)
    const [orderSize, setOrderSize] = useState(10)
    const [orderKeyword, setOrderKeyword] = useState('')
    const [orderStatus, setOrderStatus] = useState<string | undefined>()
    const [orderLoading, setOrderLoading] = useState(false)

    // 车辆列表
    const [vehicles, setVehicles] = useState<TmsVehicle[]>([])
    const [vehicleTotal, setVehicleTotal] = useState(0)
    const [vehiclePage, setVehiclePage] = useState(1)
    const [vehicleSize, setVehicleSize] = useState(10)
    const [vehicleKeyword, setVehicleKeyword] = useState('')
    const [vehicleLoading, setVehicleLoading] = useState(false)

    // 司机列表
    const [drivers, setDrivers] = useState<TmsDriver[]>([])
    const [driverTotal, setDriverTotal] = useState(0)
    const [driverPage, setDriverPage] = useState(1)
    const [driverSize, setDriverSize] = useState(10)
    const [driverKeyword, setDriverKeyword] = useState('')
    const [driverLoading, setDriverLoading] = useState(false)

    // 订单表单
    const [orderFormOpen, setOrderFormOpen] = useState(false)
    const [editingOrder, setEditingOrder] = useState<TmsOrder | null>(null)
    const [orderForm] = Form.useForm<Partial<TmsOrder>>()

    // 车辆表单
    const [vehicleFormOpen, setVehicleFormOpen] = useState(false)
    const [editingVehicle, setEditingVehicle] = useState<TmsVehicle | null>(null)
    const [vehicleForm] = Form.useForm<Partial<TmsVehicle>>()

    // 司机表单
    const [driverFormOpen, setDriverFormOpen] = useState(false)
    const [editingDriver, setEditingDriver] = useState<TmsDriver | null>(null)
    const [driverForm] = Form.useForm<Partial<TmsDriver>>()

    // 成本对比 + 派车推荐
    const [cost, setCost] = useState<TmsCostComparison | null>(null)
    const [recommendOrderId, setRecommendOrderId] = useState<number | null>(null)
    const [recommendList, setRecommendList] = useState<TmsDispatchRecommendation[]>([])
    const [recommendLoading, setRecommendLoading] = useState(false)

    // 详情抽屉
    const [detail, setDetail] = useState<TmsOrder | TmsVehicle | TmsDriver | null>(null)
    const [detailType, setDetailType] = useState<'order' | 'vehicle' | 'driver'>('order')
    const [detailLoading, setDetailLoading] = useState(false)

    // 加载看板
    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<TmsDashboard>('/tms/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载 TMS 看板失败'))
        }
    }, [])

    // 加载订单列表
    const loadOrders = useCallback(async () => {
        setOrderLoading(true)
        try {
            const res = await api.get<PageResult<TmsOrder>>('/tms/orders', {
                params: {
                    page: orderPage,
                    size: orderSize,
                    keyword: orderKeyword || undefined,
                    status: orderStatus || undefined
                }
            })
            setOrders(res.data.list)
            setOrderTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载订单列表失败'))
        } finally {
            setOrderLoading(false)
        }
    }, [orderPage, orderSize, orderKeyword, orderStatus])

    // 加载车辆列表
    const loadVehicles = useCallback(async () => {
        setVehicleLoading(true)
        try {
            const res = await api.get<PageResult<TmsVehicle>>('/tms/vehicles', {
                params: {
                    page: vehiclePage,
                    size: vehicleSize,
                    keyword: vehicleKeyword || undefined
                }
            })
            setVehicles(res.data.list)
            setVehicleTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载车辆列表失败'))
        } finally {
            setVehicleLoading(false)
        }
    }, [vehiclePage, vehicleSize, vehicleKeyword])

    // 加载司机列表
    const loadDrivers = useCallback(async () => {
        setDriverLoading(true)
        try {
            const res = await api.get<PageResult<TmsDriver>>('/tms/drivers', {
                params: {
                    page: driverPage,
                    size: driverSize,
                    keyword: driverKeyword || undefined
                }
            })
            setDrivers(res.data.list)
            setDriverTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载司机列表失败'))
        } finally {
            setDriverLoading(false)
        }
    }, [driverPage, driverSize, driverKeyword])

    // 加载成本对比
    const loadCost = useCallback(async () => {
        try {
            const res = await api.get<TmsCostComparison>('/tms/dashboard/cost')
            setCost(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载成本对比失败'))
        }
    }, [])

    // 智能派车推荐
    const loadRecommend = async (orderId: number) => {
        setRecommendOrderId(orderId)
        setRecommendLoading(true)
        try {
            const res = await api.get<TmsDispatchRecommendation[]>('/tms/dashboard/recommend', {
                params: { orderId }
            })
            setRecommendList(res.data)
        } catch (err) {
            message.error(errorMessage(err, '获取派车推荐失败'))
        } finally {
            setRecommendLoading(false)
        }
    }

    useEffect(() => {
        loadDashboard()
    }, [loadDashboard])

    // OMS 订单简表只拉一次建索引。用户没有 oms:view 权限时会 403，
    // 静默降级为空索引（反向列全显 —），不干扰 TMS 页本身
    useEffect(() => {
        api.get<OmsOrder[]>('/oms/orders/brief')
            .then((res) => {
                const map: Record<string, OmsOrder> = {}
                for (const order of res.data ?? []) {
                    map[order.orderNo] = order
                }
                setOmsBrief(map)
            })
            .catch(() => undefined)
    }, [])

    useEffect(() => {
        if (tab === 'orders') {
            loadOrders()
        } else if (tab === 'vehicles') {
            loadVehicles()
        } else if (tab === 'drivers') {
            loadDrivers()
        } else if (tab === 'cost') {
            loadCost()
            loadOrders()
        }
    }, [tab, loadOrders, loadVehicles, loadDrivers, loadCost])

    // 订单操作
    const openOrderCreate = () => {
        setEditingOrder(null)
        orderForm.resetFields()
        orderForm.setFieldsValue({ status: 'PENDING' })
        setOrderFormOpen(true)
    }

    const openOrderEdit = (row: TmsOrder) => {
        setEditingOrder(row)
        orderForm.setFieldsValue({
            orderNo: row.orderNo,
            customerName: row.customerName,
            origin: row.origin,
            destination: row.destination,
            status: row.status,
            distanceKm: row.distanceKm,
            cost: row.cost
        })
        setOrderFormOpen(true)
    }

    const submitOrder = async (values: Partial<TmsOrder>) => {
        try {
            if (editingOrder) {
                await api.put(`/tms/orders/${editingOrder.id}`, { ...values, id: editingOrder.id })
                message.success('订单已更新')
            } else {
                await api.post('/tms/orders', values)
                message.success('订单已创建')
            }
            setOrderFormOpen(false)
            loadOrders()
        } catch (err) {
            message.error(errorMessage(err, '保存订单失败'))
        }
    }

    const removeOrder = async (row: TmsOrder) => {
        try {
            await api.delete(`/tms/orders/${row.id}`)
            message.success(`已删除订单「${row.orderNo}」`)
            loadOrders()
        } catch (err) {
            message.error(errorMessage(err, '删除订单失败'))
        }
    }

    // 车辆操作
    const openVehicleCreate = () => {
        setEditingVehicle(null)
        vehicleForm.resetFields()
        vehicleForm.setFieldsValue({ status: 'IDLE', isExternal: false })
        setVehicleFormOpen(true)
    }

    const openVehicleEdit = (row: TmsVehicle) => {
        setEditingVehicle(row)
        vehicleForm.setFieldsValue({
            plateNo: row.plateNo,
            type: row.type,
            status: row.status,
            driverName: row.driverName,
            costPerKm: row.costPerKm,
            dailyFixedCost: row.dailyFixedCost,
            isExternal: row.isExternal ?? false
        })
        setVehicleFormOpen(true)
    }

    const submitVehicle = async (values: Partial<TmsVehicle>) => {
        try {
            if (editingVehicle) {
                await api.put(`/tms/vehicles/${editingVehicle.id}`, { ...values, id: editingVehicle.id })
                message.success('车辆已更新')
            } else {
                await api.post('/tms/vehicles', values)
                message.success('车辆已创建')
            }
            setVehicleFormOpen(false)
            loadVehicles()
        } catch (err) {
            message.error(errorMessage(err, '保存车辆失败'))
        }
    }

    const removeVehicle = async (row: TmsVehicle) => {
        try {
            await api.delete(`/tms/vehicles/${row.id}`)
            message.success(`已删除车辆「${row.plateNo}」`)
            loadVehicles()
        } catch (err) {
            message.error(errorMessage(err, '删除车辆失败'))
        }
    }

    // 司机操作
    const openDriverCreate = () => {
        setEditingDriver(null)
        driverForm.resetFields()
        driverForm.setFieldsValue({ status: 'ACTIVE' })
        setDriverFormOpen(true)
    }

    const openDriverEdit = (row: TmsDriver) => {
        setEditingDriver(row)
        driverForm.setFieldsValue({
            name: row.name,
            phone: row.phone,
            status: row.status
        })
        setDriverFormOpen(true)
    }

    const submitDriver = async (values: Partial<TmsDriver>) => {
        try {
            if (editingDriver) {
                await api.put(`/tms/drivers/${editingDriver.id}`, { ...values, id: editingDriver.id })
                message.success('司机已更新')
            } else {
                await api.post('/tms/drivers', values)
                message.success('司机已创建')
            }
            setDriverFormOpen(false)
            loadDrivers()
        } catch (err) {
            message.error(errorMessage(err, '保存司机失败'))
        }
    }

    const removeDriver = async (row: TmsDriver) => {
        try {
            await api.delete(`/tms/drivers/${row.id}`)
            message.success(`已删除司机「${row.name}」`)
            loadDrivers()
        } catch (err) {
            message.error(errorMessage(err, '删除司机失败'))
        }
    }

    // 详情抽屉
    const openDetail = async (row: TmsOrder | TmsVehicle | TmsDriver, type: 'order' | 'vehicle' | 'driver') => {
        setDetail(row)
        setDetailType(type)
        setDetailLoading(true)
        try {
            let res
            if (type === 'order') {
                res = await api.get<TmsOrder>(`/tms/orders/${row.id}`)
            } else if (type === 'vehicle') {
                res = await api.get<TmsVehicle>(`/tms/vehicles/${row.id}`)
            } else {
                res = await api.get<TmsDriver>(`/tms/drivers/${row.id}`)
            }
            setDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载详情失败'))
        } finally {
            setDetailLoading(false)
        }
    }

    const closeDetail = () => {
        setDetail(null)
    }

    // 订单列
    const orderColumns = [
        {
            title: '订单编号',
            dataIndex: 'orderNo',
            width: 140,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            // 三系统关联反向列：TMS order_no 即 OMS 订单号，前端 join OMS 简表显示所属订单
            title: 'OMS 订单',
            width: 130,
            align: 'center' as const,
            render: (_: unknown, row: TmsOrder) => {
                const oms = omsBrief[row.orderNo]
                if (!oms) {
                    return <span style={{ color: '#98A2B3' }}>—</span>
                }
                const channel = channelMeta(oms.channel)
                return (
                    <Tooltip title={`${oms.customerName || '—'} · ${channel.label}`}>
                        <span>{OmsOrderStatusTag(oms.status)}</span>
                    </Tooltip>
                )
            }
        },
        {
            title: '客户',
            dataIndex: 'customerName',
            width: 120,
            render: (value: string) => value || '—'
        },
        {
            title: '起点',
            dataIndex: 'origin',
            width: 140,
            render: (value: string) => value || '—'
        },
        {
            title: '终点',
            dataIndex: 'destination',
            width: 140,
            render: (value: string) => value || '—'
        },
        {
            title: '距离(km)',
            dataIndex: 'distanceKm',
            width: 90,
            align: 'right' as const,
            render: (value: number | null | undefined) =>
                value != null ? <span style={{ color: '#667085' }}>{value}</span> : '—'
        },
        {
            title: '成本(元)',
            dataIndex: 'cost',
            width: 100,
            align: 'right' as const,
            render: (value: number | null | undefined) =>
                value != null ? <span style={{ fontWeight: 500 }}>{value.toFixed(0)}</span> : '—'
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            align: 'center' as const,
            render: (value: string) => OrderStatusTag(value)
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: 150,
            render: (value: string) => (
                <span style={{ color: '#667085', fontSize: 13 }}>{formatTime(value)}</span>
            )
        },
        {
            title: '操作',
            width: 180,
            align: 'center' as const,
            render: (_: unknown, row: TmsOrder) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openDetail(row, 'order')}>
                        详情
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openOrderEdit(row)} />
                    <Popconfirm
                        title={`删除订单「${row.orderNo}」？`}
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeOrder(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    // 车辆列
    const vehicleColumns = [
        {
            title: '车牌号',
            dataIndex: 'plateNo',
            width: 120,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: '类型',
            width: 90,
            align: 'center' as const,
            render: (_: unknown, row: TmsVehicle) =>
                row.isExternal ? <Tag color="purple">外部</Tag> : <Tag color="blue">自有</Tag>
        },
        {
            title: '车型',
            dataIndex: 'type',
            width: 120,
            render: (value: string) => value || '—'
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            align: 'center' as const,
            render: (value: string) => VehicleStatusTag(value)
        },
        {
            title: '司机',
            dataIndex: 'driverName',
            width: 110,
            render: (value: string) => value || '—'
        },
        {
            title: '元/公里',
            dataIndex: 'costPerKm',
            width: 90,
            align: 'right' as const,
            render: (value: number | null | undefined) =>
                value != null ? <span style={{ fontWeight: 500 }}>{value.toFixed(2)}</span> : '—'
        },
        {
            title: '日固定成本',
            dataIndex: 'dailyFixedCost',
            width: 110,
            align: 'right' as const,
            render: (value: number | null | undefined) =>
                value != null ? <span style={{ color: '#667085' }}>{value.toFixed(0)} 元</span> : '—'
        },
        {
            title: '操作',
            width: 180,
            align: 'center' as const,
            render: (_: unknown, row: TmsVehicle) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openDetail(row, 'vehicle')}>
                        详情
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openVehicleEdit(row)} />
                    <Popconfirm
                        title={`删除车辆「${row.plateNo}」？`}
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeVehicle(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    // 司机列
    const driverColumns = [
        {
            title: '姓名',
            dataIndex: 'name',
            width: 120,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: '电话',
            dataIndex: 'phone',
            width: 140,
            render: (value: string) => value || '—'
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            align: 'center' as const,
            render: (value: string) => DriverStatusTag(value)
        },
        {
            title: '操作',
            width: 180,
            align: 'center' as const,
            render: (_: unknown, row: TmsDriver) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openDetail(row, 'driver')}>
                        详情
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openDriverEdit(row)} />
                    <Popconfirm
                        title={`删除司机「${row.name}」？`}
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeDriver(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    return (
        <div className="fade-in">
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            {/* 统计卡片 */}
            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="订单总数"
                            value={dashboard?.orders.total ?? 0}
                            prefix={<TruckOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="运输中"
                            value={dashboard?.orders.inTransit ?? 0}
                            prefix={<CarOutlined style={{ color: '#f79009' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="已送达"
                            value={dashboard?.orders.delivered ?? 0}
                            prefix={<DashboardOutlined style={{ color: '#12b76a' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="车辆总数"
                            value={dashboard?.vehicles.total ?? 0}
                            prefix={<CarOutlined style={{ color: '#2e90fa' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="空闲车辆"
                            value={dashboard?.vehicles.idle ?? 0}
                            prefix={<CarOutlined style={{ color: '#12b76a' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="司机总数"
                            value={dashboard?.drivers.total ?? 0}
                            prefix={<TeamOutlined style={{ color: '#8b5cf6' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="在职司机"
                            value={dashboard?.drivers.active ?? 0}
                            prefix={<TeamOutlined style={{ color: '#12b76a' }} />}
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
                                <TruckOutlined />
                                运输订单
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`运输订单列表 · 共 ${orderTotal} 条`}
                                extra={
                                    <Space>
                                        <Select
                                            placeholder="订单状态"
                                            allowClear
                                            options={ORDER_STATUS_OPTIONS}
                                            value={orderStatus}
                                            onChange={(value) => {
                                                setOrderPage(1)
                                                setOrderStatus(value)
                                            }}
                                            style={{ width: 120 }}
                                        />
                                        <Input.Search
                                            placeholder="搜索订单编号 / 客户"
                                            allowClear
                                            onSearch={(value) => {
                                                setOrderPage(1)
                                                setOrderKeyword(value)
                                            }}
                                            style={{ width: 220 }}
                                        />
                                        <Button onClick={loadOrders}>刷新</Button>
                                        <Button type="primary" icon={<PlusOutlined />} onClick={openOrderCreate}>
                                            新建订单
                                        </Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={orderLoading}
                                    columns={orderColumns}
                                    dataSource={orders}
                                    onRow={(row) => ({
                                        onClick: () => openDetail(row, 'order'),
                                        style: { cursor: 'pointer' }
                                    })}
                                    pagination={{
                                        current: orderPage,
                                        pageSize: orderSize,
                                        total: orderTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setOrderPage(nextPage)
                                            setOrderSize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'vehicles',
                        label: (
                            <span>
                                <CarOutlined />
                                车辆列表
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`车辆列表 · 共 ${vehicleTotal} 辆`}
                                extra={
                                    <Space>
                                        <Input.Search
                                            placeholder="搜索车牌号"
                                            allowClear
                                            onSearch={(value) => {
                                                setVehiclePage(1)
                                                setVehicleKeyword(value)
                                            }}
                                            style={{ width: 220 }}
                                        />
                                        <Button onClick={loadVehicles}>刷新</Button>
                                        <Button type="primary" icon={<PlusOutlined />} onClick={openVehicleCreate}>
                                            新建车辆
                                        </Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={vehicleLoading}
                                    columns={vehicleColumns}
                                    dataSource={vehicles}
                                    onRow={(row) => ({
                                        onClick: () => openDetail(row, 'vehicle'),
                                        style: { cursor: 'pointer' }
                                    })}
                                    pagination={{
                                        current: vehiclePage,
                                        pageSize: vehicleSize,
                                        total: vehicleTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setVehiclePage(nextPage)
                                            setVehicleSize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'drivers',
                        label: (
                            <span>
                                <TeamOutlined />
                                司机列表
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`司机列表 · 共 ${driverTotal} 人`}
                                extra={
                                    <Space>
                                        <Input.Search
                                            placeholder="搜索姓名"
                                            allowClear
                                            onSearch={(value) => {
                                                setDriverPage(1)
                                                setDriverKeyword(value)
                                            }}
                                            style={{ width: 220 }}
                                        />
                                        <Button onClick={loadDrivers}>刷新</Button>
                                        <Button type="primary" icon={<PlusOutlined />} onClick={openDriverCreate}>
                                            新建司机
                                        </Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={driverLoading}
                                    columns={driverColumns}
                                    dataSource={drivers}
                                    onRow={(row) => ({
                                        onClick: () => openDetail(row, 'driver'),
                                        style: { cursor: 'pointer' }
                                    })}
                                    pagination={{
                                        current: driverPage,
                                        pageSize: driverSize,
                                        total: driverTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setDriverPage(nextPage)
                                            setDriverSize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'cost',
                        label: (
                            <span>
                                <FundOutlined />
                                成本优化
                            </span>
                        ),
                        children: (
                            <div>
                                <Row gutter={[16, 16]}>
                                    <Col xs={24} lg={12}>
                                        <Card
                                            className="table-card"
                                            title="自有车辆 vs 外部车辆（货拉拉）"
                                            extra={
                                                <Button size="small" onClick={loadCost}>
                                                    刷新
                                                </Button>
                                            }
                                        >
                                            <Table
                                                rowKey={(row) => row.key}
                                                pagination={false}
                                                dataSource={[
                                                    {
                                                        key: 'self',
                                                        name: '自有车辆',
                                                        ...cost?.selfOwned
                                                    },
                                                    {
                                                        key: 'external',
                                                        name: '外部车辆',
                                                        ...cost?.external
                                                    }
                                                ]}
                                                columns={[
                                                    {
                                                        title: '归属',
                                                        dataIndex: 'name',
                                                        render: (value: string, row) =>
                                                            row.key === 'external' ? (
                                                                <Tag color="purple">{value}</Tag>
                                                            ) : (
                                                                <Tag color="blue">{value}</Tag>
                                                            )
                                                    },
                                                    {
                                                        title: '车辆数',
                                                        dataIndex: 'count',
                                                        align: 'right' as const,
                                                        render: (v: number) => v ?? '—'
                                                    },
                                                    {
                                                        title: '元/公里',
                                                        dataIndex: 'avgCostPerKm',
                                                        align: 'right' as const,
                                                        render: (v: number) =>
                                                            v != null ? v.toFixed(2) : '—'
                                                    },
                                                    {
                                                        title: '日固定成本',
                                                        dataIndex: 'totalFixedCost',
                                                        align: 'right' as const,
                                                        render: (v: number) =>
                                                            v != null ? `${v.toFixed(0)} 元` : '—'
                                                    },
                                                    {
                                                        title: '历史订单成本',
                                                        dataIndex: 'totalOrderCost',
                                                        align: 'right' as const,
                                                        render: (v: number) =>
                                                            v != null ? `${v.toFixed(0)} 元` : '—'
                                                    },
                                                    {
                                                        title: '单均成本',
                                                        dataIndex: 'avgOrderCost',
                                                        align: 'right' as const,
                                                        render: (v: number) =>
                                                            v != null ? `${v.toFixed(0)} 元` : '—'
                                                    }
                                                ]}
                                            />
                                            {cost ? (
                                                <Alert
                                                    type="info"
                                                    showIcon
                                                    style={{ marginTop: 12 }}
                                                    message={
                                                        cost.costPerKmDiff > 0
                                                            ? `自有车辆每公里贵 ${cost.costPerKmDiff.toFixed(2)} 元，单均贵 ${cost.avgOrderCostDiff.toFixed(0)} 元；波动线路建议优先用外部车辆`
                                                            : cost.costPerKmDiff < 0
                                                              ? `自有车辆每公里便宜 ${Math.abs(cost.costPerKmDiff).toFixed(2)} 元，稳定线路建议优先用自有车辆`
                                                              : '两类车辆每公里成本相同，按可用性派车即可'
                                                    }
                                                />
                                            ) : null}
                                        </Card>
                                    </Col>
                                    <Col xs={24} lg={12}>
                                        <Card className="table-card" title="智能派车推荐">
                                            <Space style={{ marginBottom: 16 }} wrap>
                                                <span style={{ color: '#667085', fontSize: 13 }}>
                                                    选择待发车订单：
                                                </span>
                                                <Select
                                                    placeholder="选择订单"
                                                    style={{ width: 320 }}
                                                    showSearch
                                                    optionFilterProp="label"
                                                    value={recommendOrderId}
                                                    onChange={(value) => loadRecommend(value)}
                                                    options={orders
                                                        .filter((row) => row.status === 'PENDING')
                                                        .map((row) => ({
                                                            value: row.id,
                                                            label: `${row.orderNo} · ${row.origin} → ${row.destination}`
                                                        }))}
                                                />
                                                <Button onClick={() => loadOrders()}>刷新订单</Button>
                                            </Space>

                                            <Spin spinning={recommendLoading}>
                                                {!recommendOrderId ? (
                                                    <Empty description="选择一个待发车订单查看推荐车辆" />
                                                ) : recommendList.length === 0 ? (
                                                    <Empty description="暂无空闲车辆，或订单已发车" />
                                                ) : (
                                                    <Table
                                                        rowKey="vehicleId"
                                                        pagination={false}
                                                        dataSource={recommendList}
                                                        columns={[
                                                            {
                                                                title: '推荐',
                                                                width: 70,
                                                                align: 'center' as const,
                                                                render: (_: unknown, row, index) =>
                                                                    index === 0 ? (
                                                                        <Tag color="green">首选</Tag>
                                                                    ) : (
                                                                        <span style={{ color: '#98a2b3' }}>
                                                                            {index + 1}
                                                                        </span>
                                                                        )
                                                            },
                                                            {
                                                                title: '车牌号',
                                                                dataIndex: 'plateNo',
                                                                render: (value: string) => (
                                                                    <span style={{ fontWeight: 500 }}>{value}</span>
                                                                )
                                                            },
                                                            {
                                                                title: '归属',
                                                                width: 80,
                                                                align: 'center' as const,
                                                                render: (_: unknown, row: TmsDispatchRecommendation) =>
                                                                    row.isExternal ? (
                                                                        <Tag color="purple">外部</Tag>
                                                                    ) : (
                                                                        <Tag color="blue">自有</Tag>
                                                                    )
                                                            },
                                                            {
                                                                title: '司机',
                                                                dataIndex: 'driverName',
                                                                width: 90,
                                                                render: (value: string) => value || '—'
                                                            },
                                                            {
                                                                title: '预估成本',
                                                                dataIndex: 'estimatedCost',
                                                                width: 100,
                                                                align: 'right' as const,
                                                                render: (value: number) => (
                                                                    <span style={{ fontWeight: 600 }}>
                                                                        {value.toFixed(0)} 元
                                                                    </span>
                                                                )
                                                            },
                                                            {
                                                                title: '说明',
                                                                dataIndex: 'reason',
                                                                render: (value: string) => (
                                                                    <span style={{ color: '#667085', fontSize: 12 }}>
                                                                        {value}
                                                                    </span>
                                                                )
                                                            }
                                                        ]}
                                                    />
                                                )}
                                            </Spin>
                                        </Card>
                                    </Col>
                                </Row>
                            </div>
                        )
                    }
                ]}
            />

            {/* 订单表单 Modal */}
            <Modal
                title={editingOrder ? `编辑订单 · ${editingOrder.orderNo}` : '新建订单'}
                open={orderFormOpen}
                onCancel={() => setOrderFormOpen(false)}
                onOk={() => orderForm.submit()}
                okText="保存"
                cancelText="取消"
                width={560}
                destroyOnClose
            >
                <Form form={orderForm} layout="vertical" onFinish={submitOrder} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="orderNo"
                        label="订单编号"
                        rules={[{ required: true, message: '请输入订单编号' }]}
                    >
                        <Input placeholder="如：TMS20261002001" />
                    </Form.Item>
                    <Form.Item
                        name="customerName"
                        label="客户名称"
                        rules={[{ required: true, message: '请输入客户名称' }]}
                    >
                        <Input placeholder="如：云启科技" />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item
                            name="origin"
                            label="起点"
                            style={{ width: 220 }}
                            rules={[{ required: true, message: '请输入起点' }]}
                        >
                            <Input placeholder="如：上海" />
                        </Form.Item>
                        <Form.Item
                            name="destination"
                            label="终点"
                            style={{ width: 220 }}
                            rules={[{ required: true, message: '请输入终点' }]}
                        >
                            <Input placeholder="如：北京" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="status" label="状态">
                        <Select options={ORDER_STATUS_OPTIONS} />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="distanceKm" label="距离（公里）" style={{ width: 220 }}>
                            <Input type="number" placeholder="如：300" />
                        </Form.Item>
                        <Form.Item name="cost" label="成本（元）" style={{ width: 220 }}>
                            <Input type="number" placeholder="如：1200" />
                        </Form.Item>
                    </Space>
                </Form>
            </Modal>

            {/* 车辆表单 Modal */}
            <Modal
                title={editingVehicle ? `编辑车辆 · ${editingVehicle.plateNo}` : '新建车辆'}
                open={vehicleFormOpen}
                onCancel={() => setVehicleFormOpen(false)}
                onOk={() => vehicleForm.submit()}
                okText="保存"
                cancelText="取消"
                width={480}
                destroyOnClose
            >
                <Form form={vehicleForm} layout="vertical" onFinish={submitVehicle} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="plateNo"
                        label="车牌号"
                        rules={[{ required: true, message: '请输入车牌号' }]}
                    >
                        <Input placeholder="如：沪A12345" />
                    </Form.Item>
                    <Form.Item name="type" label="车型">
                        <Input placeholder="如：厢式货车" />
                    </Form.Item>
                    <Form.Item name="driverName" label="司机">
                        <Input placeholder="如：张师傅" />
                    </Form.Item>
                    <Form.Item name="status" label="状态">
                        <Select options={VEHICLE_STATUS_OPTIONS} />
                    </Form.Item>
                    <Form.Item name="isExternal" label="车辆归属" valuePropName="checked">
                        <Switch checkedChildren="外部（货拉拉）" unCheckedChildren="自有车辆" />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="costPerKm" label="每公里成本（元）" style={{ width: 200 }}>
                            <Input type="number" placeholder="如：3.5" />
                        </Form.Item>
                        <Form.Item name="dailyFixedCost" label="日固定成本（元）" style={{ width: 200 }}>
                            <Input type="number" placeholder="如：200" />
                        </Form.Item>
                    </Space>
                </Form>
            </Modal>

            {/* 司机表单 Modal */}
            <Modal
                title={editingDriver ? `编辑司机 · ${editingDriver.name}` : '新建司机'}
                open={driverFormOpen}
                onCancel={() => setDriverFormOpen(false)}
                onOk={() => driverForm.submit()}
                okText="保存"
                cancelText="取消"
                width={480}
                destroyOnClose
            >
                <Form form={driverForm} layout="vertical" onFinish={submitDriver} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="name"
                        label="姓名"
                        rules={[{ required: true, message: '请输入姓名' }]}
                    >
                        <Input placeholder="如：张师傅" />
                    </Form.Item>
                    <Form.Item name="phone" label="电话">
                        <Input placeholder="如：13800000007" />
                    </Form.Item>
                    <Form.Item name="status" label="状态">
                        <Select options={DRIVER_STATUS_OPTIONS} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 详情抽屉 */}
            <Drawer
                title={
                    detail ? (
                        <Space>
                            <span>
                                {detailType === 'order' && (detail as TmsOrder).orderNo}
                                {detailType === 'vehicle' && (detail as TmsVehicle).plateNo}
                                {detailType === 'driver' && (detail as TmsDriver).name}
                            </span>
                            {detailType === 'order' && OrderStatusTag((detail as TmsOrder).status)}
                            {detailType === 'vehicle' && VehicleStatusTag((detail as TmsVehicle).status)}
                            {detailType === 'driver' && DriverStatusTag((detail as TmsDriver).status)}
                        </Space>
                    ) : (
                        '详情'
                    )
                }
                open={!!detail}
                onClose={closeDetail}
                width={560}
                destroyOnClose
            >
                {detail ? (
                    <Spin spinning={detailLoading}>
                        {detailType === 'order' && (
                            <div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>订单编号</div>
                                    <div style={{ fontSize: 14, fontWeight: 500 }}>
                                        {(detail as TmsOrder).orderNo}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>客户名称</div>
                                    <div style={{ fontSize: 14 }}>{(detail as TmsOrder).customerName || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>起点</div>
                                    <div style={{ fontSize: 14 }}>{(detail as TmsOrder).origin || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>终点</div>
                                    <div style={{ fontSize: 14 }}>{(detail as TmsOrder).destination || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>状态</div>
                                    <div style={{ fontSize: 14 }}>
                                        {OrderStatusTag((detail as TmsOrder).status)}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16, display: 'flex', gap: 32 }}>
                                    <div>
                                        <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>距离（公里）</div>
                                        <div style={{ fontSize: 14 }}>
                                            {(detail as TmsOrder).distanceKm ?? '—'}
                                        </div>
                                    </div>
                                    <div>
                                        <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>成本（元）</div>
                                        <div style={{ fontSize: 14, fontWeight: 500 }}>
                                            {(detail as TmsOrder).cost ?? '—'}
                                        </div>
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>创建时间</div>
                                    <div style={{ fontSize: 14 }}>{formatTime((detail as TmsOrder).createdAt)}</div>
                                </div>
                            </div>
                        )}
                        {detailType === 'vehicle' && (
                            <div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>车牌号</div>
                                    <div style={{ fontSize: 14, fontWeight: 500 }}>
                                        {(detail as TmsVehicle).plateNo}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>车型</div>
                                    <div style={{ fontSize: 14 }}>{(detail as TmsVehicle).type || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>状态</div>
                                    <div style={{ fontSize: 14 }}>
                                        {VehicleStatusTag((detail as TmsVehicle).status)}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>司机</div>
                                    <div style={{ fontSize: 14 }}>{(detail as TmsVehicle).driverName || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16, display: 'flex', gap: 32 }}>
                                    <div>
                                        <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>归属</div>
                                        <div style={{ fontSize: 14 }}>
                                            {(detail as TmsVehicle).isExternal ? (
                                                <Tag color="purple">外部（货拉拉）</Tag>
                                            ) : (
                                                <Tag color="blue">自有</Tag>
                                            )}
                                        </div>
                                    </div>
                                    <div>
                                        <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>每公里成本</div>
                                        <div style={{ fontSize: 14 }}>
                                            {(detail as TmsVehicle).costPerKm ?? '—'} 元
                                        </div>
                                    </div>
                                    <div>
                                        <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>日固定成本</div>
                                        <div style={{ fontSize: 14 }}>
                                            {(detail as TmsVehicle).dailyFixedCost ?? '—'} 元
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}
                        {detailType === 'driver' && (
                            <div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>姓名</div>
                                    <div style={{ fontSize: 14, fontWeight: 500 }}>
                                        {(detail as TmsDriver).name}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>电话</div>
                                    <div style={{ fontSize: 14 }}>{(detail as TmsDriver).phone || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>状态</div>
                                    <div style={{ fontSize: 14 }}>
                                        {DriverStatusTag((detail as TmsDriver).status)}
                                    </div>
                                </div>
                            </div>
                        )}
                    </Spin>
                ) : (
                    <Empty description="暂无数据" />
                )}
            </Drawer>
        </div>
    )
}
