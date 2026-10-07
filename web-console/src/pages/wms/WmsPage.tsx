import {
    ContainerOutlined,
    DeleteOutlined,
    EditOutlined,
    InboxOutlined,
    PlusOutlined,
    SwapOutlined
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
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import type { PageResult, WmsDashboard, WmsInventory, WmsStockRecord, WmsWarehouse } from '../../api/types'
import {
    STOCK_TYPE,
    WAREHOUSE_STATUS,
    StockTypeTag,
    WarehouseStatusTag,
    formatTime,
    stockTypeMeta,
    warehouseStatusMeta
} from './shared'

const WAREHOUSE_STATUS_OPTIONS = Object.entries(WAREHOUSE_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const STOCK_TYPE_OPTIONS = Object.entries(STOCK_TYPE).map(([value, meta]) => ({
    value,
    label: meta.label
}))

export default function WmsPage() {
    const [tab, setTab] = useState('warehouses')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    // 看板数据
    const [dashboard, setDashboard] = useState<WmsDashboard | null>(null)

    // 仓库列表
    const [warehouses, setWarehouses] = useState<WmsWarehouse[]>([])
    const [warehouseTotal, setWarehouseTotal] = useState(0)
    const [warehousePage, setWarehousePage] = useState(1)
    const [warehouseSize, setWarehouseSize] = useState(10)
    const [warehouseKeyword, setWarehouseKeyword] = useState('')
    const [warehouseLoading, setWarehouseLoading] = useState(false)

    // 库存列表
    const [inventory, setInventory] = useState<WmsInventory[]>([])
    const [inventoryTotal, setInventoryTotal] = useState(0)
    const [inventoryPage, setInventoryPage] = useState(1)
    const [inventorySize, setInventorySize] = useState(10)
    const [inventoryKeyword, setInventoryKeyword] = useState('')
    const [inventoryLoading, setInventoryLoading] = useState(false)

    // 出入库记录
    const [stockRecords, setStockRecords] = useState<WmsStockRecord[]>([])
    const [stockRecordTotal, setStockRecordTotal] = useState(0)
    const [stockRecordPage, setStockRecordPage] = useState(1)
    const [stockRecordSize, setStockRecordSize] = useState(10)
    const [stockRecordLoading, setStockRecordLoading] = useState(false)

    // 仓库表单
    const [warehouseFormOpen, setWarehouseFormOpen] = useState(false)
    const [editingWarehouse, setEditingWarehouse] = useState<WmsWarehouse | null>(null)
    const [warehouseForm] = Form.useForm<Partial<WmsWarehouse>>()

    // 库存表单
    const [inventoryFormOpen, setInventoryFormOpen] = useState(false)
    const [editingInventory, setEditingInventory] = useState<WmsInventory | null>(null)
    const [inventoryForm] = Form.useForm<Partial<WmsInventory>>()

    // 出入库操作
    const [stockOpOpen, setStockOpOpen] = useState(false)
    const [stockOpType, setStockOpType] = useState<'IN' | 'OUT'>('IN')
    const [stockOpInventory, setStockOpInventory] = useState<WmsInventory | null>(null)
    const [stockOpForm] = Form.useForm<{ quantity: number; remark?: string }>()

    // 详情抽屉
    const [detail, setDetail] = useState<WmsWarehouse | WmsInventory | WmsStockRecord | null>(null)
    const [detailType, setDetailType] = useState<'warehouse' | 'inventory' | 'stockRecord'>('warehouse')
    const [detailLoading, setDetailLoading] = useState(false)

    // 加载看板
    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<WmsDashboard>('/wms/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载 WMS 看板失败'))
        }
    }, [])

    // 加载仓库列表
    const loadWarehouses = useCallback(async () => {
        setWarehouseLoading(true)
        try {
            const res = await api.get<PageResult<WmsWarehouse>>('/wms/warehouses', {
                params: {
                    page: warehousePage,
                    size: warehouseSize,
                    keyword: warehouseKeyword || undefined
                }
            })
            setWarehouses(res.data.list)
            setWarehouseTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载仓库列表失败'))
        } finally {
            setWarehouseLoading(false)
        }
    }, [warehousePage, warehouseSize, warehouseKeyword])

    // 加载库存列表
    const loadInventory = useCallback(async () => {
        setInventoryLoading(true)
        try {
            const res = await api.get<PageResult<WmsInventory>>('/wms/inventory', {
                params: {
                    page: inventoryPage,
                    size: inventorySize,
                    keyword: inventoryKeyword || undefined
                }
            })
            setInventory(res.data.list)
            setInventoryTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载库存列表失败'))
        } finally {
            setInventoryLoading(false)
        }
    }, [inventoryPage, inventorySize, inventoryKeyword])

    // 加载出入库记录
    const loadStockRecords = useCallback(async () => {
        setStockRecordLoading(true)
        try {
            const res = await api.get<PageResult<WmsStockRecord>>('/wms/stock-records', {
                params: {
                    page: stockRecordPage,
                    size: stockRecordSize
                }
            })
            setStockRecords(res.data.list)
            setStockRecordTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载出入库记录失败'))
        } finally {
            setStockRecordLoading(false)
        }
    }, [stockRecordPage, stockRecordSize])

    useEffect(() => {
        loadDashboard()
    }, [loadDashboard])

    useEffect(() => {
        if (tab === 'warehouses') {
            loadWarehouses()
        } else if (tab === 'inventory') {
            loadInventory()
        } else if (tab === 'stock-records') {
            loadStockRecords()
        }
    }, [tab, loadWarehouses, loadInventory, loadStockRecords])

    // 仓库操作
    const openWarehouseCreate = () => {
        setEditingWarehouse(null)
        warehouseForm.resetFields()
        warehouseForm.setFieldsValue({ status: 'ACTIVE' })
        setWarehouseFormOpen(true)
    }

    const openWarehouseEdit = (row: WmsWarehouse) => {
        setEditingWarehouse(row)
        warehouseForm.setFieldsValue({
            name: row.name,
            location: row.location,
            capacity: row.capacity,
            status: row.status
        })
        setWarehouseFormOpen(true)
    }

    const submitWarehouse = async (values: Partial<WmsWarehouse>) => {
        try {
            if (editingWarehouse) {
                await api.put(`/wms/warehouses/${editingWarehouse.id}`, { ...values, id: editingWarehouse.id })
                message.success('仓库已更新')
            } else {
                await api.post('/wms/warehouses', values)
                message.success('仓库已创建')
            }
            setWarehouseFormOpen(false)
            loadWarehouses()
        } catch (err) {
            message.error(errorMessage(err, '保存仓库失败'))
        }
    }

    const removeWarehouse = async (row: WmsWarehouse) => {
        try {
            await api.delete(`/wms/warehouses/${row.id}`)
            message.success(`已删除仓库「${row.name}」`)
            loadWarehouses()
        } catch (err) {
            message.error(errorMessage(err, '删除仓库失败'))
        }
    }

    // 库存操作
    const openInventoryCreate = () => {
        setEditingInventory(null)
        inventoryForm.resetFields()
        setInventoryFormOpen(true)
    }

    const openInventoryEdit = (row: WmsInventory) => {
        setEditingInventory(row)
        inventoryForm.setFieldsValue({
            warehouseName: row.warehouseName,
            productName: row.productName,
            sku: row.sku,
            quantity: row.quantity
        })
        setInventoryFormOpen(true)
    }

    const submitInventory = async (values: Partial<WmsInventory>) => {
        try {
            if (editingInventory) {
                await api.put(`/wms/inventory/${editingInventory.id}`, { ...values, id: editingInventory.id })
                message.success('库存已更新')
            } else {
                await api.post('/wms/inventory', values)
                message.success('库存已创建')
            }
            setInventoryFormOpen(false)
            loadInventory()
        } catch (err) {
            message.error(errorMessage(err, '保存库存失败'))
        }
    }

    const removeInventory = async (row: WmsInventory) => {
        try {
            await api.delete(`/wms/inventory/${row.id}`)
            message.success(`已删除库存「${row.productName}」`)
            loadInventory()
        } catch (err) {
            message.error(errorMessage(err, '删除库存失败'))
        }
    }

    // 出入库操作
    const openStockOp = (row: WmsInventory, type: 'IN' | 'OUT') => {
        setStockOpInventory(row)
        setStockOpType(type)
        stockOpForm.resetFields()
        stockOpForm.setFieldsValue({ quantity: 1 })
        setStockOpOpen(true)
    }

    const submitStockOp = async (values: { quantity: number; remark?: string }) => {
        if (!stockOpInventory) return
        try {
            await api.post('/wms/stock-records', {
                inventoryId: stockOpInventory.id,
                type: stockOpType,
                quantity: values.quantity,
                remark: values.remark
            })
            message.success(`${stockOpType === 'IN' ? '入库' : '出库'}成功`)
            setStockOpOpen(false)
            loadInventory()
            loadStockRecords()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '操作失败'))
        }
    }

    // 详情抽屉
    const openDetail = async (
        row: WmsWarehouse | WmsInventory | WmsStockRecord,
        type: 'warehouse' | 'inventory' | 'stockRecord'
    ) => {
        setDetail(row)
        setDetailType(type)
        setDetailLoading(true)
        try {
            let res
            if (type === 'warehouse') {
                res = await api.get<WmsWarehouse>(`/wms/warehouses/${row.id}`)
            } else if (type === 'inventory') {
                res = await api.get<WmsInventory>(`/wms/inventory/${row.id}`)
            } else {
                res = await api.get<WmsStockRecord>(`/wms/stock-records/${row.id}`)
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

    // 仓库列
    const warehouseColumns = [
        {
            title: '名称',
            dataIndex: 'name',
            width: 160,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: '位置',
            dataIndex: 'location',
            width: 180,
            render: (value: string) => value || '—'
        },
        {
            title: '容量',
            dataIndex: 'capacity',
            width: 100,
            align: 'right' as const,
            render: (value: number) => value?.toLocaleString() ?? '—'
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            align: 'center' as const,
            render: (value: string) => WarehouseStatusTag(value)
        },
        {
            title: '操作',
            width: 180,
            align: 'center' as const,
            render: (_: unknown, row: WmsWarehouse) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openDetail(row, 'warehouse')}>
                        详情
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openWarehouseEdit(row)} />
                    <Popconfirm
                        title={`删除仓库「${row.name}」？`}
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeWarehouse(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    // 库存列
    const inventoryColumns = [
        {
            title: '仓库',
            dataIndex: 'warehouseName',
            width: 140,
            render: (value: string) => value || '—'
        },
        {
            title: '商品',
            dataIndex: 'productName',
            width: 160,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            title: 'SKU',
            dataIndex: 'sku',
            width: 140,
            render: (value: string) => value || '—'
        },
        {
            title: '数量',
            dataIndex: 'quantity',
            width: 100,
            align: 'right' as const,
            render: (value: number) => {
                const isLow = dashboard && value <= 10
                return (
                    <span style={{ color: isLow ? '#f04438' : undefined, fontWeight: isLow ? 500 : undefined }}>
                        {value?.toLocaleString() ?? '—'}
                        {isLow ? ' ⚠' : ''}
                    </span>
                )
            }
        },
        {
            title: '操作',
            width: 220,
            align: 'center' as const,
            render: (_: unknown, row: WmsInventory) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openDetail(row, 'inventory')}>
                        详情
                    </Button>
                    <Button
                        type="link"
                        size="small"
                        icon={<PlusOutlined />}
                        onClick={() => openStockOp(row, 'IN')}
                    >
                        入库
                    </Button>
                    <Button
                        type="link"
                        size="small"
                        icon={<SwapOutlined />}
                        onClick={() => openStockOp(row, 'OUT')}
                    >
                        出库
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openInventoryEdit(row)} />
                    <Popconfirm
                        title={`删除库存「${row.productName}」？`}
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeInventory(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    // 出入库记录列
    const stockRecordColumns = [
        {
            title: '仓库',
            dataIndex: 'warehouseName',
            width: 140,
            render: (value: string) => value || '—'
        },
        {
            title: '商品',
            dataIndex: 'productName',
            width: 160,
            render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>
        },
        {
            // 三系统关联：OMS 发货联动自动写入的订单号（手工记录为空）
            title: '订单号',
            dataIndex: 'orderNo',
            width: 150,
            render: (value: string | null | undefined) => value || '—'
        },
        {
            title: '类型',
            dataIndex: 'type',
            width: 100,
            align: 'center' as const,
            render: (value: string) => StockTypeTag(value)
        },
        {
            title: '数量',
            dataIndex: 'quantity',
            width: 100,
            align: 'right' as const,
            render: (value: number) => value?.toLocaleString() ?? '—'
        },
        {
            title: '时间',
            dataIndex: 'createdAt',
            width: 150,
            render: (value: string) => (
                <span style={{ color: '#667085', fontSize: 13 }}>{formatTime(value)}</span>
            )
        },
        {
            title: '操作',
            width: 100,
            align: 'center' as const,
            render: (_: unknown, row: WmsStockRecord) => (
                <Button type="link" size="small" onClick={() => openDetail(row, 'stockRecord')}>
                    详情
                </Button>
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
                            title="仓库总数"
                            value={warehouses.length}
                            prefix={<ContainerOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="商品种类"
                            value={dashboard?.inventory.totalProducts ?? 0}
                            prefix={<InboxOutlined style={{ color: '#2e90fa' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="库存总量"
                            value={dashboard?.inventory.totalQuantity ?? 0}
                            prefix={<InboxOutlined style={{ color: '#12b76a' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="低库存预警"
                            value={dashboard?.inventory.lowStock ?? 0}
                            prefix={<InboxOutlined style={{ color: '#f04438' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="累计入库"
                            value={dashboard?.stockRecords.totalIn ?? 0}
                            prefix={<SwapOutlined style={{ color: '#12b76a' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="累计出库"
                            value={dashboard?.stockRecords.totalOut ?? 0}
                            prefix={<SwapOutlined style={{ color: '#f79009' }} />}
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
                        key: 'warehouses',
                        label: (
                            <span>
                                <ContainerOutlined />
                                仓库列表
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`仓库列表 · 共 ${warehouseTotal} 个`}
                                extra={
                                    <Space>
                                        <Input.Search
                                            placeholder="搜索仓库名称"
                                            allowClear
                                            onSearch={(value) => {
                                                setWarehousePage(1)
                                                setWarehouseKeyword(value)
                                            }}
                                            style={{ width: 220 }}
                                        />
                                        <Button onClick={loadWarehouses}>刷新</Button>
                                        <Button type="primary" icon={<PlusOutlined />} onClick={openWarehouseCreate}>
                                            新建仓库
                                        </Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={warehouseLoading}
                                    columns={warehouseColumns}
                                    dataSource={warehouses}
                                    onRow={(row) => ({
                                        onClick: () => openDetail(row, 'warehouse'),
                                        style: { cursor: 'pointer' }
                                    })}
                                    pagination={{
                                        current: warehousePage,
                                        pageSize: warehouseSize,
                                        total: warehouseTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setWarehousePage(nextPage)
                                            setWarehouseSize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'inventory',
                        label: (
                            <span>
                                <InboxOutlined />
                                库存列表
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`库存列表 · 共 ${inventoryTotal} 条`}
                                extra={
                                    <Space>
                                        <Input.Search
                                            placeholder="搜索商品名称"
                                            allowClear
                                            onSearch={(value) => {
                                                setInventoryPage(1)
                                                setInventoryKeyword(value)
                                            }}
                                            style={{ width: 220 }}
                                        />
                                        <Button onClick={loadInventory}>刷新</Button>
                                        <Button type="primary" icon={<PlusOutlined />} onClick={openInventoryCreate}>
                                            新建库存
                                        </Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={inventoryLoading}
                                    columns={inventoryColumns}
                                    dataSource={inventory}
                                    onRow={(row) => ({
                                        onClick: () => openDetail(row, 'inventory'),
                                        style: { cursor: 'pointer' }
                                    })}
                                    pagination={{
                                        current: inventoryPage,
                                        pageSize: inventorySize,
                                        total: inventoryTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setInventoryPage(nextPage)
                                            setInventorySize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'stock-records',
                        label: (
                            <span>
                                <SwapOutlined />
                                出入库记录
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`出入库记录 · 共 ${stockRecordTotal} 条`}
                                extra={
                                    <Space>
                                        <Button onClick={loadStockRecords}>刷新</Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={stockRecordLoading}
                                    columns={stockRecordColumns}
                                    dataSource={stockRecords}
                                    onRow={(row) => ({
                                        onClick: () => openDetail(row, 'stockRecord'),
                                        style: { cursor: 'pointer' }
                                    })}
                                    pagination={{
                                        current: stockRecordPage,
                                        pageSize: stockRecordSize,
                                        total: stockRecordTotal,
                                        showSizeChanger: true,
                                        showTotal: (count) => `共 ${count} 条`,
                                        onChange: (nextPage, nextSize) => {
                                            setStockRecordPage(nextPage)
                                            setStockRecordSize(nextSize)
                                        }
                                    }}
                                />
                            </Card>
                        )
                    }
                ]}
            />

            {/* 仓库表单 Modal */}
            <Modal
                title={editingWarehouse ? `编辑仓库 · ${editingWarehouse.name}` : '新建仓库'}
                open={warehouseFormOpen}
                onCancel={() => setWarehouseFormOpen(false)}
                onOk={() => warehouseForm.submit()}
                okText="保存"
                cancelText="取消"
                width={480}
                destroyOnClose
            >
                <Form form={warehouseForm} layout="vertical" onFinish={submitWarehouse} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="name"
                        label="仓库名称"
                        rules={[{ required: true, message: '请输入仓库名称' }]}
                    >
                        <Input placeholder="如：上海中心仓" />
                    </Form.Item>
                    <Form.Item name="location" label="位置">
                        <Input placeholder="如：上海市浦东新区" />
                    </Form.Item>
                    <Form.Item name="capacity" label="容量">
                        <InputNumber min={0} style={{ width: '100%' }} placeholder="如：10000" />
                    </Form.Item>
                    <Form.Item name="status" label="状态">
                        <Select options={WAREHOUSE_STATUS_OPTIONS} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 库存表单 Modal */}
            <Modal
                title={editingInventory ? `编辑库存 · ${editingInventory.productName}` : '新建库存'}
                open={inventoryFormOpen}
                onCancel={() => setInventoryFormOpen(false)}
                onOk={() => inventoryForm.submit()}
                okText="保存"
                cancelText="取消"
                width={480}
                destroyOnClose
            >
                <Form form={inventoryForm} layout="vertical" onFinish={submitInventory} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="warehouseName"
                        label="仓库"
                        rules={[{ required: true, message: '请输入仓库名称' }]}
                    >
                        <Input placeholder="如：上海中心仓" />
                    </Form.Item>
                    <Form.Item
                        name="productName"
                        label="商品名称"
                        rules={[{ required: true, message: '请输入商品名称' }]}
                    >
                        <Input placeholder="如：iPhone 15 Pro" />
                    </Form.Item>
                    <Form.Item name="sku" label="SKU">
                        <Input placeholder="如：IP15P-256-BLACK" />
                    </Form.Item>
                    <Form.Item name="quantity" label="数量">
                        <InputNumber min={0} style={{ width: '100%' }} placeholder="如：100" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 出入库操作 Modal */}
            <Modal
                title={`${stockOpType === 'IN' ? '入库' : '出库'} · ${stockOpInventory?.productName ?? ''}`}
                open={stockOpOpen}
                onCancel={() => setStockOpOpen(false)}
                onOk={() => stockOpForm.submit()}
                okText="确认"
                cancelText="取消"
                width={400}
                destroyOnClose
            >
                <Form form={stockOpForm} layout="vertical" onFinish={submitStockOp} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="quantity"
                        label="数量"
                        rules={[{ required: true, message: '请输入数量' }]}
                    >
                        <InputNumber min={1} style={{ width: '100%' }} placeholder="请输入数量" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={3} maxLength={255} showCount placeholder="备注（选填）" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 详情抽屉 */}
            <Drawer
                title={
                    detail ? (
                        <Space>
                            <span>
                                {detailType === 'warehouse' && (detail as WmsWarehouse).name}
                                {detailType === 'inventory' && (detail as WmsInventory).productName}
                                {detailType === 'stockRecord' && (detail as WmsStockRecord).productName}
                            </span>
                            {detailType === 'warehouse' && WarehouseStatusTag((detail as WmsWarehouse).status)}
                            {detailType === 'stockRecord' && StockTypeTag((detail as WmsStockRecord).type)}
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
                        {detailType === 'warehouse' && (
                            <div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>仓库名称</div>
                                    <div style={{ fontSize: 14, fontWeight: 500 }}>
                                        {(detail as WmsWarehouse).name}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>位置</div>
                                    <div style={{ fontSize: 14 }}>{(detail as WmsWarehouse).location || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>容量</div>
                                    <div style={{ fontSize: 14 }}>
                                        {(detail as WmsWarehouse).capacity?.toLocaleString() ?? '—'}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>状态</div>
                                    <div style={{ fontSize: 14 }}>
                                        {WarehouseStatusTag((detail as WmsWarehouse).status)}
                                    </div>
                                </div>
                            </div>
                        )}
                        {detailType === 'inventory' && (
                            <div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>仓库</div>
                                    <div style={{ fontSize: 14 }}>{(detail as WmsInventory).warehouseName || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>商品名称</div>
                                    <div style={{ fontSize: 14, fontWeight: 500 }}>
                                        {(detail as WmsInventory).productName}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>SKU</div>
                                    <div style={{ fontSize: 14 }}>{(detail as WmsInventory).sku || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>数量</div>
                                    <div style={{ fontSize: 14 }}>
                                        {(detail as WmsInventory).quantity?.toLocaleString() ?? '—'}
                                    </div>
                                </div>
                            </div>
                        )}
                        {detailType === 'stockRecord' && (
                            <div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>仓库</div>
                                    <div style={{ fontSize: 14 }}>{(detail as WmsStockRecord).warehouseName || '—'}</div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>商品名称</div>
                                    <div style={{ fontSize: 14, fontWeight: 500 }}>
                                        {(detail as WmsStockRecord).productName}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>类型</div>
                                    <div style={{ fontSize: 14 }}>
                                        {StockTypeTag((detail as WmsStockRecord).type)}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>数量</div>
                                    <div style={{ fontSize: 14 }}>
                                        {(detail as WmsStockRecord).quantity?.toLocaleString() ?? '—'}
                                    </div>
                                </div>
                                <div style={{ marginBottom: 16 }}>
                                    <div style={{ fontSize: 12, color: '#98a2b3', marginBottom: 4 }}>时间</div>
                                    <div style={{ fontSize: 14 }}>{formatTime((detail as WmsStockRecord).createdAt)}</div>
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
