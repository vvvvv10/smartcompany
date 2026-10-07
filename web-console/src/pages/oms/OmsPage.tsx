import {
    BarChartOutlined,
    DeleteOutlined,
    EditOutlined,
    FundOutlined,
    InboxOutlined,
    PlusOutlined,
    RiseOutlined,
    ShoppingCartOutlined,
    TagsOutlined,
    ThunderboltOutlined,
    TruckOutlined,
    WalletOutlined
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
    InputNumber,
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
    Tooltip as AntdTooltip,
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import {
    Bar,
    BarChart,
    CartesianGrid,
    Cell,
    Pie,
    PieChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis
} from 'recharts'
import { api, errorMessage } from '../../api/client'
import type { OmsDashboard, OmsOrder, OmsOrderItem, OmsProduct, PageResult } from '../../api/types'
import { COLOR } from '../../theme'
import { OrderStatusTag as TmsOrderStatusTag } from '../tms/shared'
import {
    CATEGORY,
    CHANNEL,
    ORDER_STATUS,
    PRODUCT_STATUS,
    CategoryTag,
    ChannelTag,
    OrderStatusTag,
    ProductStatusTag,
    channelMeta,
    formatAmount,
    formatTime,
    orderStatusMeta
} from './shared'

const ORDER_STATUS_OPTIONS = Object.entries(ORDER_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const CHANNEL_OPTIONS = Object.entries(CHANNEL).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const CATEGORY_OPTIONS = Object.entries(CATEGORY).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const PRODUCT_STATUS_OPTIONS = Object.entries(PRODUCT_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

/** 明细编辑行：提交时才组装成后端要的 items 结构 */
interface DraftItem {
    key: number
    productId: number | null
    styleNo: string
    productName: string
    color?: string
    size?: string
    /** 单价（元），默认取吊牌价 */
    price: number
    quantity: number
}

let draftSeq = 0
const nextDraftKey = () => Date.now() * 1000 + draftSeq++

export default function OmsPage() {
    const [tab, setTab] = useState('orders')
    const [error, setError] = useState<string | null>(null)

    // 看板数据
    const [dashboard, setDashboard] = useState<OmsDashboard | null>(null)

    // 订单列表
    const [orders, setOrders] = useState<OmsOrder[]>([])
    const [orderTotal, setOrderTotal] = useState(0)
    const [orderPage, setOrderPage] = useState(1)
    const [orderSize, setOrderSize] = useState(10)
    const [orderKeyword, setOrderKeyword] = useState('')
    const [orderStatus, setOrderStatus] = useState<string | undefined>()
    const [orderChannel, setOrderChannel] = useState<string | undefined>()
    const [orderLoading, setOrderLoading] = useState(false)

    // 商品款号列表
    const [products, setProducts] = useState<OmsProduct[]>([])
    const [productTotal, setProductTotal] = useState(0)
    const [productPage, setProductPage] = useState(1)
    const [productSize, setProductSize] = useState(10)
    const [productKeyword, setProductKeyword] = useState('')
    const [productCategory, setProductCategory] = useState<string | undefined>()
    const [productStatus, setProductStatus] = useState<string | undefined>()
    const [productLoading, setProductLoading] = useState(false)

    // 商品下拉（订单明细选择用）
    const [productBriefs, setProductBriefs] = useState<OmsProduct[]>([])

    // 订单表单
    const [orderFormOpen, setOrderFormOpen] = useState(false)
    const [editingOrder, setEditingOrder] = useState<OmsOrder | null>(null)
    const [orderItems, setOrderItems] = useState<DraftItem[]>([])
    const [orderForm] = Form.useForm<Partial<OmsOrder>>()

    // 商品表单
    const [productFormOpen, setProductFormOpen] = useState(false)
    const [editingProduct, setEditingProduct] = useState<OmsProduct | null>(null)
    const [productForm] = Form.useForm<Partial<OmsProduct>>()

    // 订单详情抽屉
    const [detail, setDetail] = useState<OmsOrder | null>(null)
    const [detailLoading, setDetailLoading] = useState(false)

    // 加载看板
    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<OmsDashboard>('/oms/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载 OMS 看板失败'))
        }
    }, [])

    // 加载订单列表
    const loadOrders = useCallback(async () => {
        setOrderLoading(true)
        try {
            const res = await api.get<PageResult<OmsOrder>>('/oms/orders', {
                params: {
                    page: orderPage,
                    size: orderSize,
                    keyword: orderKeyword || undefined,
                    status: orderStatus || undefined,
                    channel: orderChannel || undefined
                }
            })
            setOrders(res.data.list)
            setOrderTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载订单列表失败'))
        } finally {
            setOrderLoading(false)
        }
    }, [orderPage, orderSize, orderKeyword, orderStatus, orderChannel])

    // 加载商品款号列表
    const loadProducts = useCallback(async () => {
        setProductLoading(true)
        try {
            const res = await api.get<PageResult<OmsProduct>>('/oms/products', {
                params: {
                    page: productPage,
                    size: productSize,
                    keyword: productKeyword || undefined,
                    category: productCategory || undefined,
                    status: productStatus || undefined
                }
            })
            setProducts(res.data.list)
            setProductTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载商品列表失败'))
        } finally {
            setProductLoading(false)
        }
    }, [productPage, productSize, productKeyword, productCategory, productStatus])

    // 商品下拉数据（全部款号）
    const loadProductBriefs = useCallback(async () => {
        try {
            const res = await api.get<OmsProduct[]>('/oms/products/brief')
            setProductBriefs(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载商品下拉数据失败'))
        }
    }, [])

    useEffect(() => {
        loadDashboard()
        loadProductBriefs()
    }, [loadDashboard, loadProductBriefs])

    useEffect(() => {
        if (tab === 'orders') {
            loadOrders()
        } else if (tab === 'products') {
            loadProducts()
        } else {
            loadDashboard()
        }
    }, [tab, loadOrders, loadProducts, loadDashboard])

    // ---------------- 订单操作 ----------------

    const openOrderCreate = () => {
        setEditingOrder(null)
        orderForm.resetFields()
        orderForm.setFieldsValue({ channel: 'DOUYIN', status: 'UNPAID' })
        setOrderItems([])
        setOrderFormOpen(true)
    }

    const openOrderEdit = async (row: OmsOrder) => {
        setEditingOrder(row)
        setOrderFormOpen(true)
        orderForm.setFieldsValue({
            channel: row.channel,
            customerName: row.customerName,
            phone: row.phone,
            address: row.address,
            status: row.status,
            remark: row.remark
        })
        // 明细只有详情接口返回，编辑前先拉一次
        try {
            const res = await api.get<OmsOrder>(`/oms/orders/${row.id}`)
            const full = res.data
            setOrderItems((full.items ?? []).map((item, index) => toDraft(item, index)))
        } catch (err) {
            message.error(errorMessage(err, '加载订单明细失败'))
        }
    }

    /** 详情里的明细行 → 可编辑行；明细没带 productId 时按款号+颜色+尺码回查商品 */
    const toDraft = (item: OmsOrderItem, index: number): DraftItem => {
        const hit =
            productBriefs.find((p) => p.id === item.productId) ??
            productBriefs.find(
                (p) =>
                    p.styleNo === item.styleNo &&
                    (p.color ?? '') === (item.color ?? '') &&
                    (p.size ?? '') === (item.size ?? '')
            )
        return {
            key: nextDraftKey() + index,
            productId: item.productId ?? hit?.id ?? null,
            styleNo: item.styleNo,
            productName: item.productName,
            color: item.color ?? hit?.color,
            size: item.size ?? hit?.size,
            price: item.price ?? hit?.price ?? 0,
            quantity: item.quantity
        }
    }

    const addOrderItem = () => {
        setOrderItems((prev) => [
            ...prev,
            { key: nextDraftKey(), productId: null, styleNo: '', productName: '', price: 0, quantity: 1 }
        ])
    }

    const removeOrderItem = (key: number) => {
        setOrderItems((prev) => prev.filter((item) => item.key !== key))
    }

    const patchOrderItem = (key: number, patch: Partial<DraftItem>) => {
        setOrderItems((prev) => prev.map((item) => (item.key === key ? { ...item, ...patch } : item)))
    }

    /** 选中商品：带出款号/品名/颜色/尺码，价格默认吊牌价 */
    const pickOrderItemProduct = (key: number, productId: number) => {
        const product = productBriefs.find((p) => p.id === productId)
        if (!product) {
            return
        }
        setOrderItems((prev) =>
            prev.map((item) =>
                item.key === key
                    ? {
                          ...item,
                          productId: product.id,
                          styleNo: product.styleNo,
                          productName: product.name,
                          color: product.color,
                          size: product.size,
                          price: product.price ?? item.price ?? 0
                      }
                    : item
            )
        )
    }

    const draftQty = orderItems.reduce((sum, item) => sum + (Number(item.quantity) || 0), 0)
    const draftAmount = orderItems.reduce(
        (sum, item) => sum + (Number(item.quantity) || 0) * (Number(item.price) || 0),
        0
    )

    const submitOrder = async (values: Partial<OmsOrder>) => {
        if (orderItems.length === 0) {
            message.warning('请至少添加一条订单明细')
            return
        }
        const items: OmsOrderItem[] = []
        for (const draft of orderItems) {
            if (!draft.productId) {
                message.warning(`明细「${draft.productName || draft.styleNo || '未选择商品'}」缺少商品，请重新选择`)
                return
            }
            if (!draft.quantity || draft.quantity < 1) {
                message.warning(`明细「${draft.productName}」的数量至少为 1`)
                return
            }
            items.push({
                productId: draft.productId,
                styleNo: draft.styleNo,
                productName: draft.productName,
                color: draft.color,
                size: draft.size,
                quantity: draft.quantity,
                price: draft.price
            })
        }
        try {
            if (editingOrder) {
                await api.put(`/oms/orders/${editingOrder.id}`, { ...values, id: editingOrder.id, items })
                message.success('订单已更新')
            } else {
                await api.post('/oms/orders', { ...values, items })
                message.success('订单已创建')
            }
            setOrderFormOpen(false)
            loadOrders()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '保存订单失败'))
        }
    }

    const removeOrder = async (row: OmsOrder) => {
        try {
            await api.delete(`/oms/orders/${row.id}`)
            message.success(`已删除订单「${row.orderNo}」`)
            loadOrders()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '删除订单失败'))
        }
    }

    // ---------------- 商品操作 ----------------

    const openProductCreate = () => {
        setEditingProduct(null)
        productForm.resetFields()
        productForm.setFieldsValue({ status: 'ON' })
        setProductFormOpen(true)
    }

    const openProductEdit = (row: OmsProduct) => {
        setEditingProduct(row)
        productForm.setFieldsValue({
            styleNo: row.styleNo,
            name: row.name,
            category: row.category,
            season: row.season,
            color: row.color,
            size: row.size,
            sku: row.sku,
            price: row.price,
            status: row.status,
            remark: row.remark
        })
        setProductFormOpen(true)
    }

    const submitProduct = async (values: Partial<OmsProduct>) => {
        try {
            if (editingProduct) {
                await api.put(`/oms/products/${editingProduct.id}`, { ...values, id: editingProduct.id })
                message.success('商品已更新')
            } else {
                await api.post('/oms/products', values)
                message.success('商品已创建')
            }
            setProductFormOpen(false)
            loadProducts()
            loadProductBriefs()
        } catch (err) {
            message.error(errorMessage(err, '保存商品失败'))
        }
    }

    const removeProduct = async (row: OmsProduct) => {
        try {
            await api.delete(`/oms/products/${row.id}`)
            message.success(`已删除款号「${row.styleNo}」`)
            loadProducts()
            loadProductBriefs()
        } catch (err) {
            message.error(errorMessage(err, '删除商品失败'))
        }
    }

    // ---------------- 详情抽屉 ----------------

    const openDetail = async (row: OmsOrder) => {
        setDetail(row)
        setDetailLoading(true)
        try {
            const res = await api.get<OmsOrder>(`/oms/orders/${row.id}`)
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

    // ---------------- 列定义 ----------------

    const orderColumns = [
        {
            title: '订单号',
            dataIndex: 'orderNo',
            width: 160,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: '渠道',
            dataIndex: 'channel',
            width: 100,
            align: 'center' as const,
            render: (value: string) => ChannelTag(value)
        },
        {
            title: '客户',
            dataIndex: 'customerName',
            width: 120,
            render: (value: string) => value || '—'
        },
        {
            title: '件数',
            dataIndex: 'totalQty',
            width: 80,
            align: 'right' as const,
            render: (value: number) => value?.toLocaleString() ?? '—'
        },
        {
            title: '金额（元）',
            dataIndex: 'totalAmount',
            width: 120,
            align: 'right' as const,
            render: (value: number) => <span style={{ fontWeight: 500 }}>{formatAmount(value)}</span>
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            align: 'center' as const,
            render: (value: string) => OrderStatusTag(value)
        },
        {
            // 三系统关联：OMS 后端按订单号聚合 TMS 运单（无运单/下游降级显示 —）
            title: '运单',
            dataIndex: 'tmsStatus',
            width: 110,
            align: 'center' as const,
            render: (value: string | null | undefined, row: OmsOrder) =>
                value ? (
                    <AntdTooltip
                        title={
                            row.tmsOrigin || row.tmsDestination
                                ? `${row.tmsOrigin ?? '—'} → ${row.tmsDestination ?? '—'}`
                                : undefined
                        }
                    >
                        <span>{TmsOrderStatusTag(value)}</span>
                    </AntdTooltip>
                ) : (
                    <span style={{ color: '#98A2B3' }}>—</span>
                )
        },
        {
            // 三系统关联：WMS OUT 记录合计件数
            title: '出库',
            dataIndex: 'outQty',
            width: 90,
            align: 'right' as const,
            render: (value: number | null | undefined) =>
                value != null ? (
                    <span style={{ fontWeight: 500, color: '#12B76A' }}>{value} 件</span>
                ) : (
                    <span style={{ color: '#98A2B3' }}>—</span>
                )
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
            width: 160,
            align: 'center' as const,
            render: (_: unknown, row: OmsOrder) => (
                <Space size={4}>
                    <Button
                        type="link"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={(event) => {
                            event.stopPropagation()
                            openOrderEdit(row)
                        }}
                    />
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

    const productColumns = [
        {
            title: '款号',
            dataIndex: 'styleNo',
            width: 120,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: '品名',
            dataIndex: 'name',
            width: 160,
            render: (value: string) => value || '—'
        },
        {
            title: '类目',
            dataIndex: 'category',
            width: 90,
            align: 'center' as const,
            render: (value: string) => CategoryTag(value)
        },
        {
            title: '颜色',
            dataIndex: 'color',
            width: 90,
            render: (value: string) => value || '—'
        },
        {
            title: '尺码',
            dataIndex: 'size',
            width: 90,
            render: (value: string) => value || '—'
        },
        {
            title: 'SKU',
            dataIndex: 'sku',
            width: 160,
            render: (value: string) => value || '—'
        },
        {
            title: '吊牌价（元）',
            dataIndex: 'price',
            width: 110,
            align: 'right' as const,
            render: (value: number | null | undefined) =>
                value != null ? <span style={{ fontWeight: 500 }}>{formatAmount(value)}</span> : '—'
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            align: 'center' as const,
            render: (value: string) => ProductStatusTag(value)
        },
        {
            title: '操作',
            width: 140,
            align: 'center' as const,
            render: (_: unknown, row: OmsProduct) => (
                <Space size={4}>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openProductEdit(row)} />
                    <Popconfirm
                        title={`删除款号「${row.styleNo}」？`}
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeProduct(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    // 明细编辑列
    const draftItemColumns = [
        {
            title: '商品（款号）',
            dataIndex: 'productId',
            width: 240,
            render: (_: unknown, row: DraftItem) => (
                <Select
                    size="small"
                    style={{ width: '100%' }}
                    placeholder="选择商品"
                    showSearch
                    optionFilterProp="label"
                    value={row.productId ?? undefined}
                    onChange={(value: number) => pickOrderItemProduct(row.key, value)}
                    options={productBriefs.map((p) => ({
                        value: p.id,
                        label: `${p.styleNo} · ${p.name}${p.color ? ` · ${p.color}` : ''}${p.size ? ` / ${p.size}` : ''}`
                    }))}
                />
            )
        },
        {
            title: '颜色/尺码',
            width: 110,
            render: (_: unknown, row: DraftItem) => (
                <span style={{ color: '#667085', fontSize: 12 }}>
                    {row.color || '—'} / {row.size || '—'}
                </span>
            )
        },
        {
            title: '数量',
            dataIndex: 'quantity',
            width: 90,
            render: (_: unknown, row: DraftItem) => (
                <InputNumber
                    size="small"
                    min={1}
                    style={{ width: '100%' }}
                    value={row.quantity}
                    onChange={(value) => patchOrderItem(row.key, { quantity: value != null ? Number(value) : 1 })}
                />
            )
        },
        {
            title: '单价（元）',
            dataIndex: 'price',
            width: 110,
            render: (_: unknown, row: DraftItem) => (
                <InputNumber
                    size="small"
                    min={0}
                    precision={2}
                    style={{ width: '100%' }}
                    value={row.price}
                    onChange={(value) => patchOrderItem(row.key, { price: value != null ? Number(value) : 0 })}
                />
            )
        },
        {
            title: '小计（元）',
            width: 110,
            align: 'right' as const,
            render: (_: unknown, row: DraftItem) => (
                <span style={{ fontWeight: 500 }}>{formatAmount((row.quantity || 0) * (row.price || 0))}</span>
            )
        },
        {
            title: '',
            width: 50,
            align: 'center' as const,
            render: (_: unknown, row: DraftItem) => (
                <Button
                    type="text"
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => removeOrderItem(row.key)}
                />
            )
        }
    ]

    // 看板图表数据
    const channelData = (dashboard?.orders.channelDistribution ?? []).map((item) => ({
        ...item,
        label: channelMeta(item.channel).label
    }))

    const statusData = (dashboard?.orders.statusDistribution ?? []).map((item) => ({
        ...item,
        label: orderStatusMeta(item.status).label
    }))

    const topStyles = dashboard?.topStyles ?? []

    const chartTooltipStyle = {
        borderRadius: 10,
        border: '1px solid #eaecf0',
        boxShadow: '0 4px 14px rgba(16,24,40,0.08)',
        fontSize: 12
    }

    // 订单明细（详情抽屉）
    const detailItemColumns = [
        {
            title: '款号',
            dataIndex: 'styleNo',
            width: 100,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: '品名',
            dataIndex: 'productName',
            render: (value: string) => value || '—'
        },
        {
            title: '颜色',
            dataIndex: 'color',
            width: 80,
            render: (value: string) => value || '—'
        },
        {
            title: '尺码',
            dataIndex: 'size',
            width: 70,
            render: (value: string) => value || '—'
        },
        {
            title: '数量',
            dataIndex: 'quantity',
            width: 70,
            align: 'right' as const,
            render: (value: number) => value?.toLocaleString() ?? '—'
        },
        {
            title: '单价',
            dataIndex: 'price',
            width: 90,
            align: 'right' as const,
            render: (value: number) => formatAmount(value)
        },
        {
            title: '小计',
            width: 100,
            align: 'right' as const,
            render: (_: unknown, row: OmsOrderItem) => (
                <span style={{ fontWeight: 500 }}>{formatAmount((row.quantity || 0) * (row.price || 0))}</span>
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
                            prefix={<ShoppingCartOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="待发货"
                            value={dashboard?.orders.toShip ?? 0}
                            prefix={<TruckOutlined style={{ color: '#f79009' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="待付款"
                            value={dashboard?.orders.unpaid ?? 0}
                            prefix={<WalletOutlined style={{ color: '#f04438' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="今日订单"
                            value={dashboard?.sales.todayOrderCount ?? 0}
                            prefix={<ThunderboltOutlined style={{ color: '#2e90fa' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="今日销售额"
                            value={dashboard?.sales.todayAmount ?? 0}
                            prefix={<RiseOutlined style={{ color: '#12b76a' }} />}
                            formatter={(value) => `¥ ${formatAmount(Number(value))}`}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="本月销售额"
                            value={dashboard?.sales.monthAmount ?? 0}
                            prefix={<FundOutlined style={{ color: '#8b5cf6' }} />}
                            formatter={(value) => `¥ ${formatAmount(Number(value))}`}
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
                                <ShoppingCartOutlined />
                                销售订单
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`销售订单 · 共 ${orderTotal} 条`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="订单状态"
                                            allowClear
                                            options={ORDER_STATUS_OPTIONS}
                                            value={orderStatus}
                                            onChange={(value) => {
                                                setOrderPage(1)
                                                setOrderStatus(value)
                                            }}
                                            style={{ width: 110 }}
                                        />
                                        <Select
                                            placeholder="销售渠道"
                                            allowClear
                                            options={CHANNEL_OPTIONS}
                                            value={orderChannel}
                                            onChange={(value) => {
                                                setOrderPage(1)
                                                setOrderChannel(value)
                                            }}
                                            style={{ width: 110 }}
                                        />
                                        <Input.Search
                                            placeholder="搜索订单号 / 客户"
                                            allowClear
                                            onSearch={(value) => {
                                                setOrderPage(1)
                                                setOrderKeyword(value)
                                            }}
                                            style={{ width: 200 }}
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
                                        onClick: () => openDetail(row),
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
                        key: 'products',
                        label: (
                            <span>
                                <TagsOutlined />
                                商品款号
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`商品款号 · 共 ${productTotal} 条`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="商品类目"
                                            allowClear
                                            options={CATEGORY_OPTIONS}
                                            value={productCategory}
                                            onChange={(value) => {
                                                setProductPage(1)
                                                setProductCategory(value)
                                            }}
                                            style={{ width: 110 }}
                                        />
                                        <Select
                                            placeholder="上下架状态"
                                            allowClear
                                            options={PRODUCT_STATUS_OPTIONS}
                                            value={productStatus}
                                            onChange={(value) => {
                                                setProductPage(1)
                                                setProductStatus(value)
                                            }}
                                            style={{ width: 110 }}
                                        />
                                        <Input.Search
                                            placeholder="搜索款号 / 品名"
                                            allowClear
                                            onSearch={(value) => {
                                                setProductPage(1)
                                                setProductKeyword(value)
                                            }}
                                            style={{ width: 200 }}
                                        />
                                        <Button onClick={loadProducts}>刷新</Button>
                                        <Button type="primary" icon={<PlusOutlined />} onClick={openProductCreate}>
                                            新建商品
                                        </Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={productLoading}
                                    columns={productColumns}
                                    dataSource={products}
                                    pagination={{
                                        current: productPage,
                                        pageSize: productSize,
                                        total: productTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setProductPage(nextPage)
                                            setProductSize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'stats',
                        label: (
                            <span>
                                <BarChartOutlined />
                                统计看板
                            </span>
                        ),
                        children: (
                            <Row gutter={[16, 16]}>
                                <Col xs={24} lg={12}>
                                    <Card
                                        className="table-card"
                                        title="渠道订单分布"
                                        extra={
                                            <Button size="small" onClick={loadDashboard}>
                                                刷新
                                            </Button>
                                        }
                                    >
                                        <ResponsiveContainer width="100%" height={240}>
                                            <BarChart data={channelData} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                                <CartesianGrid strokeDasharray="4 4" stroke="#eef0f4" vertical={false} />
                                                <XAxis
                                                    dataKey="label"
                                                    tickLine={false}
                                                    axisLine={false}
                                                    tick={{ fontSize: 12, fill: '#98a2b3' }}
                                                />
                                                <YAxis
                                                    allowDecimals={false}
                                                    tickLine={false}
                                                    axisLine={false}
                                                    tick={{ fontSize: 12, fill: '#98a2b3' }}
                                                />
                                                <Tooltip
                                                    cursor={{ fill: 'rgba(99,102,241,0.06)' }}
                                                    contentStyle={chartTooltipStyle}
                                                    labelStyle={{ color: '#667085' }}
                                                    formatter={(value: number, name: string) => [`${value} 单`, name]}
                                                />
                                                <Bar dataKey="count" name="订单数" fill={COLOR.chart[0]} radius={[6, 6, 0, 0]} maxBarSize={48} />
                                            </BarChart>
                                        </ResponsiveContainer>
                                        <div className="chart-legend">
                                            {channelData.map((entry) => (
                                                <div className="chart-legend-item" key={entry.channel}>
                                                    <span
                                                        className="chart-legend-dot"
                                                        style={{ background: COLOR.chart[0] }}
                                                    />
                                                    {entry.label} · {entry.count} 单 · ¥ {formatAmount(entry.amount)}
                                                </div>
                                            ))}
                                            {channelData.length === 0 ? (
                                                <span style={{ color: '#98a2b3', fontSize: 12 }}>暂无数据</span>
                                            ) : null}
                                        </div>
                                    </Card>
                                </Col>
                                <Col xs={24} lg={12}>
                                    <Card className="table-card" title="订单状态分布">
                                        <ResponsiveContainer width="100%" height={240}>
                                            <PieChart>
                                                <Pie
                                                    data={statusData}
                                                    dataKey="count"
                                                    nameKey="label"
                                                    innerRadius={62}
                                                    outerRadius={92}
                                                    paddingAngle={3}
                                                    stroke="#fff"
                                                    strokeWidth={2}
                                                >
                                                    {statusData.map((entry, index) => (
                                                        <Cell
                                                            key={entry.status}
                                                            fill={COLOR.chart[index % COLOR.chart.length]}
                                                        />
                                                    ))}
                                                </Pie>
                                                <Tooltip
                                                    contentStyle={chartTooltipStyle}
                                                    labelStyle={{ color: '#667085' }}
                                                    formatter={(value: number, name: string) => [`${value} 单`, name]}
                                                />
                                            </PieChart>
                                        </ResponsiveContainer>
                                        <div className="chart-legend">
                                            {statusData.map((entry, index) => (
                                                <div className="chart-legend-item" key={entry.status}>
                                                    <span
                                                        className="chart-legend-dot"
                                                        style={{ background: COLOR.chart[index % COLOR.chart.length] }}
                                                    />
                                                    {entry.label} · {entry.count} 单
                                                </div>
                                            ))}
                                            {statusData.length === 0 ? (
                                                <span style={{ color: '#98a2b3', fontSize: 12 }}>暂无数据</span>
                                            ) : null}
                                        </div>
                                    </Card>
                                </Col>
                                <Col xs={24}>
                                    <Card className="table-card" title={`热销款 TOP5 · 按销量排序`}>
                                        <Table
                                            rowKey="styleNo"
                                            pagination={false}
                                            dataSource={topStyles.slice(0, 5)}
                                            columns={[
                                                {
                                                    title: '排名',
                                                    width: 90,
                                                    align: 'center' as const,
                                                    render: (_: unknown, _row: unknown, index: number) => (
                                                        <Tag color={index === 0 ? 'gold' : index === 1 ? 'silver' : 'blue'}>
                                                            {index + 1}
                                                        </Tag>
                                                    )
                                                },
                                                {
                                                    title: '款号',
                                                    dataIndex: 'styleNo',
                                                    width: 140,
                                                    render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
                                                },
                                                {
                                                    title: '品名',
                                                    dataIndex: 'name',
                                                    render: (value: string) => value || '—'
                                                },
                                                {
                                                    title: '销量（件）',
                                                    dataIndex: 'qty',
                                                    width: 140,
                                                    align: 'right' as const,
                                                    render: (value: number) => value?.toLocaleString() ?? '—'
                                                },
                                                {
                                                    title: '销售额（元）',
                                                    dataIndex: 'amount',
                                                    width: 160,
                                                    align: 'right' as const,
                                                    render: (value: number) => (
                                                        <span style={{ fontWeight: 500 }}>{formatAmount(value)}</span>
                                                    )
                                                }
                                            ]}
                                        />
                                    </Card>
                                </Col>
                            </Row>
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
                width={760}
                destroyOnClose
            >
                <Form form={orderForm} layout="vertical" onFinish={submitOrder} style={{ marginTop: 16 }}>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item
                            name="channel"
                            label="销售渠道"
                            style={{ width: 200 }}
                            rules={[{ required: true, message: '请选择销售渠道' }]}
                        >
                            <Select options={CHANNEL_OPTIONS} placeholder="选择渠道" />
                        </Form.Item>
                        <Form.Item
                            name="customerName"
                            label="客户名称"
                            style={{ width: 260 }}
                            rules={[{ required: true, message: '请输入客户名称' }]}
                        >
                            <Input placeholder="如：王小姐" />
                        </Form.Item>
                        <Form.Item name="status" label="订单状态" style={{ width: 200 }}>
                            <Select options={ORDER_STATUS_OPTIONS} />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="phone" label="联系电话" style={{ width: 220 }}>
                            <Input placeholder="如：13800000007" />
                        </Form.Item>
                        <Form.Item name="address" label="收货地址" style={{ width: 420 }}>
                            <Input placeholder="如：上海市浦东新区 xx 路 1 号" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} maxLength={255} showCount placeholder="备注（选填）" />
                    </Form.Item>

                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                        <span style={{ fontWeight: 500 }}>订单明细</span>
                        <Button size="small" icon={<PlusOutlined />} onClick={addOrderItem}>
                            添加明细
                        </Button>
                    </div>
                    <Table
                        rowKey="key"
                        size="small"
                        pagination={false}
                        dataSource={orderItems}
                        columns={draftItemColumns}
                        locale={{
                            emptyText: (
                                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无明细，点击「添加明细」" />
                            )
                        }}
                        scroll={{ x: 700 }}
                    />
                    {orderItems.length > 0 ? (
                        <div style={{ marginTop: 8, textAlign: 'right', fontSize: 13, color: '#667085' }}>
                            合计 <b>{draftQty}</b> 件 · <b style={{ color: '#101828' }}>¥ {formatAmount(draftAmount)}</b>
                            <span style={{ color: '#98a2b3' }}>（提交后由服务端按明细汇总）</span>
                        </div>
                    ) : null}
                </Form>
            </Modal>

            {/* 商品表单 Modal */}
            <Modal
                title={editingProduct ? `编辑商品 · ${editingProduct.styleNo}` : '新建商品'}
                open={productFormOpen}
                onCancel={() => setProductFormOpen(false)}
                onOk={() => productForm.submit()}
                okText="保存"
                cancelText="取消"
                width={560}
                destroyOnClose
            >
                <Form form={productForm} layout="vertical" onFinish={submitProduct} style={{ marginTop: 16 }}>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item
                            name="styleNo"
                            label="款号"
                            style={{ width: 220 }}
                            rules={[{ required: true, message: '请输入款号' }]}
                        >
                            <Input placeholder="如：SH202601" />
                        </Form.Item>
                        <Form.Item
                            name="name"
                            label="品名"
                            style={{ width: 260 }}
                            rules={[{ required: true, message: '请输入品名' }]}
                        >
                            <Input placeholder="如：落肩针织开衫" />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="category" label="类目" style={{ width: 160 }}>
                            <Select options={CATEGORY_OPTIONS} placeholder="选择类目" allowClear />
                        </Form.Item>
                        <Form.Item name="season" label="季节" style={{ width: 140 }}>
                            <Input placeholder="如：2026春秋" />
                        </Form.Item>
                        <Form.Item name="status" label="状态" style={{ width: 140 }}>
                            <Select options={PRODUCT_STATUS_OPTIONS} />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="color" label="颜色" style={{ width: 160 }}>
                            <Input placeholder="如：燕麦白" />
                        </Form.Item>
                        <Form.Item name="size" label="尺码" style={{ width: 140 }}>
                            <Input placeholder="如：S/M/L" />
                        </Form.Item>
                        <Form.Item name="price" label="吊牌价（元）" style={{ width: 160 }}>
                            <InputNumber min={0} precision={2} style={{ width: '100%' }} placeholder="如：399" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="sku" label="SKU">
                        <Input placeholder="如：SH202601-OAT-M" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} maxLength={255} showCount placeholder="备注（选填）" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 订单详情抽屉 */}
            <Drawer
                title={
                    detail ? (
                        <Space>
                            <span>{detail.orderNo}</span>
                            {OrderStatusTag(detail.status)}
                            {ChannelTag(detail.channel)}
                        </Space>
                    ) : (
                        '详情'
                    )
                }
                open={!!detail}
                onClose={closeDetail}
                width={660}
                destroyOnClose
            >
                {detail ? (
                    <Spin spinning={detailLoading}>
                        <div style={{ display: 'flex', gap: 32, marginBottom: 16, flexWrap: 'wrap' }}>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>订单号</div>
                                <div style={{ fontSize: 14, fontWeight: 500 }}>{detail.orderNo}</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>渠道</div>
                                <div style={{ fontSize: 14 }}>{ChannelTag(detail.channel)}</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>状态</div>
                                <div style={{ fontSize: 14 }}>{OrderStatusTag(detail.status)}</div>
                            </div>
                        </div>
                        <div style={{ display: 'flex', gap: 32, marginBottom: 16, flexWrap: 'wrap' }}>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>客户</div>
                                <div style={{ fontSize: 14 }}>{detail.customerName || '—'}</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>电话</div>
                                <div style={{ fontSize: 14 }}>{detail.phone || '—'}</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>件数</div>
                                <div style={{ fontSize: 14 }}>{detail.totalQty?.toLocaleString() ?? '—'} 件</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>金额</div>
                                <div style={{ fontSize: 14, fontWeight: 500 }}>¥ {formatAmount(detail.totalAmount)}</div>
                            </div>
                        </div>
                        <div style={{ marginBottom: 16 }}>
                            <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>收货地址</div>
                            <div style={{ fontSize: 14 }}>{detail.address || '—'}</div>
                        </div>
                        <div style={{ marginBottom: 16 }}>
                            <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>备注</div>
                            <div style={{ fontSize: 14 }}>{detail.remark || '—'}</div>
                        </div>
                        <div style={{ display: 'flex', gap: 32, marginBottom: 16 }}>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>创建时间</div>
                                <div style={{ fontSize: 14 }}>{formatTime(detail.createdAt)}</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>更新时间</div>
                                <div style={{ fontSize: 14 }}>{formatTime(detail.updatedAt)}</div>
                            </div>
                        </div>

                        <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 8 }}>
                            订单明细 · {(detail.items ?? []).length} 条
                        </div>
                        <Table
                            rowKey={(row) => row.id ?? `${row.styleNo}-${row.color}-${row.size}`}
                            size="small"
                            pagination={false}
                            dataSource={detail.items ?? []}
                            columns={detailItemColumns}
                            scroll={{ x: 560 }}
                        />
                    </Spin>
                ) : (
                    <Empty description="暂无数据" />
                )}
            </Drawer>
        </div>
    )
}
