import {
    AlertOutlined,
    BankOutlined,
    FileProtectOutlined,
    PlusOutlined,
    ShopOutlined,
    StarOutlined,
    UserOutlined
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
    Progress,
    Rate,
    Row,
    Select,
    Space,
    Spin,
    Statistic,
    Table,
    Tabs,
    Tag,
    Typography,
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import type {
    IntlCrmDashboard,
    IntlCustomer,
    IntlPartner,
    IntlTicket,
    PageResult
} from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import {
    COUNTRY_OPTIONS,
    CUSTOMER_TYPE,
    PARTNER_TYPE,
    TICKET_CATEGORY,
    TICKET_SEVERITY,
    TICKET_STATUS,
    TicketCategoryTag,
    TicketStatusTag,
    customerTypeMeta,
    dash,
    formatMoney,
    formatUtc
} from './shared'

const TICKET_STATUS_OPTIONS = Object.entries(TICKET_STATUS).map(([value, m]) => ({ value, label: m.label }))
const TICKET_CATEGORY_OPTIONS = Object.entries(TICKET_CATEGORY).map(([value, m]) => ({ value, label: m.label }))
const TICKET_SEVERITY_OPTIONS = Object.entries(TICKET_SEVERITY).map(([value, m]) => ({ value, label: m.label }))
const PARTNER_TYPE_OPTIONS = Object.entries(PARTNER_TYPE).map(([value, m]) => ({ value, label: m.label }))
const CUSTOMER_TYPE_OPTIONS = Object.entries(CUSTOMER_TYPE).map(([value, m]) => ({ value, label: m.label }))

/**
 * 国际 CRM 页（/intl/crm）——客户 / 渠道商 / 异常工单三个页签。
 *
 * 报价单与合同**不在这里**：它们连同运价卡、汇率、对账一起是 M3 的范围
 * （见计划 §10 M3）。硬塞进来会出现「有报价但不能算价、有金额但没有账期来源」
 * 的半截状态，比没有更糟。
 *
 * 授信两列**禁止跨币种相加**：看板按 default_currency 分组给数，
 * 这里也只显示客户自己的默认币种那一份。
 */
export default function IntlCrmPage() {
    const { hasPermission } = useAuth()
    const canEdit = hasPermission('crm:intl:edit')
    const [tab, setTab] = useState('customers')
    const [error, setError] = useState<string | null>(null)
    const [dashboard, setDashboard] = useState<IntlCrmDashboard | null>(null)

    // 客户
    const [customers, setCustomers] = useState<IntlCustomer[]>([])
    const [customerTotal, setCustomerTotal] = useState(0)
    const [customerPage, setCustomerPage] = useState(1)
    const [customerKeyword, setCustomerKeyword] = useState('')
    const [customerLoading, setCustomerLoading] = useState(false)
    const [customerDetail, setCustomerDetail] = useState<IntlCustomer | null>(null)

    // 渠道商
    const [partners, setPartners] = useState<IntlPartner[]>([])
    const [partnerTotal, setPartnerTotal] = useState(0)
    const [partnerPage, setPartnerPage] = useState(1)
    const [partnerKeyword, setPartnerKeyword] = useState('')
    const [partnerType, setPartnerType] = useState<string | undefined>()
    const [partnerLoading, setPartnerLoading] = useState(false)

    // 工单
    const [tickets, setTickets] = useState<IntlTicket[]>([])
    const [ticketTotal, setTicketTotal] = useState(0)
    const [ticketPage, setTicketPage] = useState(1)
    const [ticketKeyword, setTicketKeyword] = useState('')
    const [ticketStatus, setTicketStatus] = useState<string | undefined>()
    const [ticketCategory, setTicketCategory] = useState<string | undefined>()
    const [ticketSeverity, setTicketSeverity] = useState<string | undefined>()
    const [ticketLoading, setTicketLoading] = useState(false)
    const [ticketDetail, setTicketDetail] = useState<IntlTicket | null>(null)

    // 表单
    const [ticketFormOpen, setTicketFormOpen] = useState(false)
    const [ticketForm] = Form.useForm<Record<string, unknown>>()
    const [ticketStatusOpen, setTicketStatusOpen] = useState(false)
    const [ticketStatusTarget, setTicketStatusTarget] = useState<IntlTicket | null>(null)
    const [ticketStatusForm] = Form.useForm<{ status: string; resolution?: string }>()
    const [partnerFormOpen, setPartnerFormOpen] = useState(false)
    const [partnerForm] = Form.useForm<Record<string, unknown>>()

    const loadDashboard = useCallback(async () => {
        try {
            const res = await api.get<IntlCrmDashboard>('/crm/intl/dashboard')
            setDashboard(res.data)
        } catch (err) {
            setError(errorMessage(err, '加载国际 CRM 看板失败'))
        }
    }, [])

    const loadCustomers = useCallback(async () => {
        setCustomerLoading(true)
        try {
            const res = await api.get<PageResult<IntlCustomer>>('/crm/customers', {
                params: { page: customerPage, size: 10, keyword: customerKeyword || undefined }
            })
            setCustomers(res.data.list as IntlCustomer[])
            setCustomerTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载客户列表失败'))
        } finally {
            setCustomerLoading(false)
        }
    }, [customerPage, customerKeyword])

    const loadPartners = useCallback(async () => {
        setPartnerLoading(true)
        try {
            const res = await api.get<PageResult<IntlPartner>>('/crm/intl/partners', {
                params: {
                    page: partnerPage,
                    size: 10,
                    keyword: partnerKeyword || undefined,
                    partnerType: partnerType || undefined
                }
            })
            setPartners(res.data.list)
            setPartnerTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载渠道商失败'))
        } finally {
            setPartnerLoading(false)
        }
    }, [partnerPage, partnerKeyword, partnerType])

    const loadTickets = useCallback(async () => {
        setTicketLoading(true)
        try {
            const res = await api.get<PageResult<IntlTicket>>('/crm/intl/tickets', {
                params: {
                    page: ticketPage,
                    size: 10,
                    keyword: ticketKeyword || undefined,
                    status: ticketStatus || undefined,
                    category: ticketCategory || undefined,
                    severity: ticketSeverity || undefined
                }
            })
            setTickets(res.data.list)
            setTicketTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载异常工单失败'))
        } finally {
            setTicketLoading(false)
        }
    }, [ticketPage, ticketKeyword, ticketStatus, ticketCategory, ticketSeverity])

    useEffect(() => {
        loadDashboard()
    }, [loadDashboard])

    useEffect(() => {
        if (tab === 'customers') {
            loadCustomers()
        } else if (tab === 'partners') {
            loadPartners()
        } else if (tab === 'tickets') {
            loadTickets()
        }
    }, [tab, loadCustomers, loadPartners, loadTickets])

    const openCustomer = async (row: IntlCustomer) => {
        try {
            const res = await api.get<IntlCustomer>(`/crm/customers/${row.id}`)
            setCustomerDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载客户详情失败'))
        }
    }

    const openTicketDetail = async (row: IntlTicket) => {
        try {
            const res = await api.get<IntlTicket>(`/crm/intl/tickets/${row.id}`)
            setTicketDetail(res.data)
        } catch (err) {
            message.error(errorMessage(err, '加载工单详情失败'))
        }
    }

    const openTicketStatus = (row: IntlTicket) => {
        setTicketStatusTarget(row)
        ticketStatusForm.resetFields()
        ticketStatusForm.setFieldsValue({ status: row.status === 'OPEN' ? 'FOLLOWING' : 'RESOLVED' })
        setTicketStatusOpen(true)
    }

    const submitTicketStatus = async (values: { status: string; resolution?: string }) => {
        if (!ticketStatusTarget) {
            return
        }
        try {
            await api.patch(`/crm/intl/tickets/${ticketStatusTarget.id}/status`, values)
            message.success('工单状态已推进')
            setTicketStatusOpen(false)
            loadTickets()
            loadDashboard()
            if (ticketDetail?.id === ticketStatusTarget.id) {
                setTicketDetail(null)
            }
        } catch (err) {
            message.error(errorMessage(err, '推进工单状态失败'))
        }
    }

    const createTicket = async (values: Record<string, unknown>) => {
        try {
            await api.post('/crm/intl/tickets', values)
            message.success('工单已创建')
            setTicketFormOpen(false)
            loadTickets()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '创建工单失败'))
        }
    }

    const deletePartner = async (row: IntlPartner) => {
        try {
            await api.delete(`/crm/intl/partners/${row.id}`)
            message.success('渠道商已删除')
            loadPartners()
            loadDashboard()
        } catch (err) {
            message.error(errorMessage(err, '删除渠道商失败'))
        }
    }

    const customerColumns = [
        {
            title: '客户名称',
            dataIndex: 'name',
            width: 240,
            render: (v: string) => (
                <Typography.Text style={{ fontWeight: 500 }} copyable={{ text: v }}>
                    {v}
                </Typography.Text>
            )
        },
        {
            title: '类型',
            dataIndex: 'customerType',
            width: 100,
            render: (v?: string) => {
                const m = customerTypeMeta(v)
                return <Tag color={m.color}>{m.label}</Tag>
            }
        },
        { title: '国家', dataIndex: 'country', width: 80, render: (v?: string) => dash(v) },
        { title: '分级', dataIndex: 'level', width: 90 },
        { title: '状态', dataIndex: 'status', width: 90 },
        { title: '账期', dataIndex: 'paymentTermDays', width: 100, align: 'right' as const, render: (v?: number) => (v == null ? dash(v) : `${v} 天`) },
        {
            title: '授信已用 / 总额',
            width: 200,
            render: (_: unknown, row: IntlCustomer) => {
                if (row.creditLimit == null || row.creditLimit === 0) {
                    return dash(null)
                }
                const pct = Math.min(100, Math.round(((row.creditUsed ?? 0) / row.creditLimit) * 100))
                return (
                    <div>
                        <span style={{ color: pct >= 90 ? '#f04438' : '#475467', fontSize: 12 }}>
                            {formatMoney(row.creditUsed, row.defaultCurrency)} /{' '}
                            {formatMoney(row.creditLimit, row.defaultCurrency)}
                        </span>
                        <Progress percent={pct} size="small" showInfo={false} />
                    </div>
                )
            }
        },
        { title: '币种', dataIndex: 'defaultCurrency', width: 80, render: (v?: string) => dash(v) },
        { title: '税号', dataIndex: 'taxNo', width: 170, render: (v?: string) => dash(v) },
        {
            title: '操作',
            width: 90,
            align: 'center' as const,
            render: (_: unknown, row: IntlCustomer) => (
                <Button type="link" size="small" onClick={() => openCustomer(row)}>
                    详情
                </Button>
            )
        }
    ]

    const partnerColumns = [
        { title: '编号', dataIndex: 'partnerCode', width: 130, render: (v: string) => v },
        { title: '名称', dataIndex: 'partnerName', width: 190, render: (v: string) => v },
        {
            title: '类型',
            dataIndex: 'partnerType',
            width: 120,
            render: (v: string) => {
                const m = PARTNER_TYPE[v] ?? { label: v, color: 'default' }
                return <Tag color={m.color}>{m.label}</Tag>
            }
        },
        { title: '国家', dataIndex: 'country', width: 80, render: (v?: string) => dash(v) },
        { title: '联系人', dataIndex: 'contactName', width: 110, render: (v?: string) => dash(v) },
        { title: '联系电话', dataIndex: 'contactPhone', width: 140, render: (v?: string) => dash(v) },
        { title: '结算币种', dataIndex: 'currency', width: 90, render: (v?: string) => dash(v) },
        { title: '账期', dataIndex: 'paymentTermDays', width: 100, align: 'right' as const, render: (v?: number) => (v == null ? dash(v) : `${v} 天`) },
        {
            title: '我方在其处授信',
            width: 160,
            render: (_: unknown, row: IntlPartner) => {
                if (row.creditLimit == null || row.creditLimit === 0) {
                    return dash(null)
                }
                return (
                    <span style={{ fontSize: 12 }}>
                        {formatMoney(row.creditUsed, row.currency)} / {formatMoney(row.creditLimit, row.currency)}
                    </span>
                )
            }
        },
        {
            title: '评级',
            dataIndex: 'rating',
            width: 130,
            render: (v?: number) =>
                v ? <Rate disabled value={v} style={{ fontSize: 13 }} /> : dash(null)
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (v: string) => (
                <Tag color={v === 'ACTIVE' ? 'success' : 'default'}>{v === 'ACTIVE' ? '合作中' : '已停用'}</Tag>
            )
        },
        {
            title: '操作',
            width: 90,
            align: 'center' as const,
            render: (_: unknown, row: IntlPartner) =>
                canEdit ? (
                    <Popconfirm
                        title={`删除渠道商「${row.partnerName}」？`}
                        okText="删除"
                        cancelText="取消"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => deletePartner(row)}
                    >
                        <Button type="link" size="small" danger>
                            删除
                        </Button>
                    </Popconfirm>
                ) : (
                    dash(null)
                )
        }
    ]

    const ticketColumns = [
        {
            title: '工单号',
            dataIndex: 'ticketNo',
            width: 155,
            render: (v: string) => (
                <Typography.Text copyable={{ text: v }} style={{ fontWeight: 500 }}>
                    {v}
                </Typography.Text>
            )
        },
        { title: '客户', dataIndex: 'customerName', width: 200, render: (v?: string) => dash(v) },
        { title: '出口子单', dataIndex: 'orderNo', width: 145, render: (v?: string) => dash(v) },
        { title: '运单号', dataIndex: 'shipmentNo', width: 150, render: (v?: string) => dash(v) },
        { title: '类别', dataIndex: 'category', width: 110, render: (v: string) => <TicketCategoryTag code={v} /> },
        {
            title: '严重度',
            dataIndex: 'severity',
            width: 90,
            render: (v: string) => {
                const m = TICKET_SEVERITY[v] ?? { label: v, color: 'default' }
                return <Tag color={m.color}>{m.label}</Tag>
            }
        },
        { title: '标题', dataIndex: 'title', width: 230, ellipsis: true },
        { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <TicketStatusTag code={v} /> },
        { title: '跟进人', dataIndex: 'ownerName', width: 100, render: (v?: string) => dash(v) },
        {
            title: 'SLA 到期（UTC）',
            dataIndex: 'dueAt',
            width: 155,
            render: (v: string | null | undefined, row: IntlTicket) => (
                <span style={{ color: row.overdue ? '#f04438' : '#667085', fontSize: 13 }}>
                    {formatUtc(v)}
                    {row.overdue ? '（逾期）' : ''}
                </span>
            )
        },
        {
            title: '操作',
            width: 130,
            align: 'center' as const,
            render: (_: unknown, row: IntlTicket) => (
                <Space size={2}>
                    <Button type="link" size="small" onClick={() => openTicketDetail(row)}>
                        详情
                    </Button>
                    {canEdit && row.status !== 'CLOSED' ? (
                        <Button type="link" size="small" onClick={() => openTicketStatus(row)}>
                            推进状态
                        </Button>
                    ) : null}
                </Space>
            )
        }
    ]

    return (
        <div className="fade-in">
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="客户总数"
                            value={dashboard?.customerTotal ?? 0}
                            prefix={<UserOutlined style={{ color: '#4f46e5' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="境外客户"
                            value={dashboard?.overseaCustomer ?? 0}
                            prefix={<BankOutlined style={{ color: '#2e90fa' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="合作渠道商"
                            value={dashboard?.partnerTotal ?? 0}
                            prefix={<ShopOutlined style={{ color: '#12b76a' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="待处理工单"
                            value={dashboard?.ticketOpen ?? 0}
                            prefix={<FileProtectOutlined style={{ color: '#f79009' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="逾期工单"
                            value={dashboard?.ticketOverdue ?? 0}
                            prefix={<AlertOutlined style={{ color: '#f04438' }} />}
                        />
                    </Card>
                </Col>
                <Col xs={12} sm={8} lg={4}>
                    <Card className="stat-card">
                        <Statistic
                            title="授信已用"
                            value={dashboard?.creditUsedByCurrency?.[0]?.amount ?? 0}
                            prefix={<StarOutlined style={{ color: '#8b5cf6' }} />}
                            suffix={
                                <span style={{ fontSize: 13 }}>
                                    {dashboard?.creditUsedByCurrency?.[0]?.currency ?? 'USD'}
                                </span>
                            }
                        />
                    </Card>
                </Col>
            </Row>

            {dashboard && dashboard.creditUsedByCurrency.length > 1 ? (
                <Alert
                    style={{ marginTop: 12 }}
                    type="info"
                    showIcon
                    message={`授信按币种分列（禁止跨币种相加）：${dashboard.creditUsedByCurrency
                        .map((c) => `${c.currency} ${formatMoney(c.amount, c.currency)}`)
                        .join('、')}`}
                />
            ) : null}

            <Tabs
                activeKey={tab}
                onChange={setTab}
                style={{ marginTop: 16 }}
                items={[
                    {
                        key: 'customers',
                        label: (
                            <span>
                                <UserOutlined />
                                客户
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`出口客户 · 共 ${customerTotal} 家（含国际属性：类型/国家/账期/授信）`}
                                extra={
                                    <Space>
                                        <Input.Search
                                            placeholder="客户名称 / 电话 / 行业"
                                            allowClear
                                            style={{ width: 240 }}
                                            onSearch={(v) => {
                                                setCustomerPage(1)
                                                setCustomerKeyword(v)
                                            }}
                                        />
                                        <Button onClick={loadCustomers}>刷新</Button>
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={customerLoading}
                                    columns={customerColumns}
                                    dataSource={customers}
                                    scroll={{ x: 1400 }}
                                    locale={{ emptyText: <Empty description="没有符合条件的客户" /> }}
                                    pagination={{
                                        current: customerPage,
                                        pageSize: 10,
                                        total: customerTotal,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p) => setCustomerPage(p)
                                    }}
                                    onRow={(row) => ({
                                        onClick: () => openCustomer(row),
                                        style: { cursor: 'pointer' }
                                    })}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'partners',
                        label: (
                            <span>
                                <ShopOutlined />
                                渠道商
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`货代与渠道商 · 共 ${partnerTotal} 家（合作关系侧主数据）`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="渠道商类型"
                                            allowClear
                                            style={{ width: 150 }}
                                            options={PARTNER_TYPE_OPTIONS}
                                            value={partnerType}
                                            onChange={(v) => {
                                                setPartnerPage(1)
                                                setPartnerType(v)
                                            }}
                                        />
                                        <Input.Search
                                            placeholder="编号 / 名称 / 联系人"
                                            allowClear
                                            style={{ width: 220 }}
                                            onSearch={(v) => {
                                                setPartnerPage(1)
                                                setPartnerKeyword(v)
                                            }}
                                        />
                                        <Button onClick={loadPartners}>刷新</Button>
                                        {canEdit ? (
                                            <Button
                                                type="primary"
                                                icon={<PlusOutlined />}
                                                onClick={() => {
                                                    partnerForm.resetFields()
                                                    setPartnerFormOpen(true)
                                                }}
                                            >
                                                新增渠道商
                                            </Button>
                                        ) : null}
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={partnerLoading}
                                    columns={partnerColumns}
                                    dataSource={partners}
                                    scroll={{ x: 1600 }}
                                    locale={{ emptyText: <Empty description="还没有渠道商" /> }}
                                    pagination={{
                                        current: partnerPage,
                                        pageSize: 10,
                                        total: partnerTotal,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p) => setPartnerPage(p)
                                    }}
                                />
                            </Card>
                        )
                    },
                    {
                        key: 'tickets',
                        label: (
                            <span>
                                <FileProtectOutlined />
                                异常工单
                            </span>
                        ),
                        children: (
                            <Card
                                className="table-card"
                                title={`出口异常工单 · 共 ${ticketTotal} 条`}
                                extra={
                                    <Space wrap>
                                        <Select
                                            placeholder="工单状态"
                                            allowClear
                                            style={{ width: 120 }}
                                            options={TICKET_STATUS_OPTIONS}
                                            value={ticketStatus}
                                            onChange={(v) => {
                                                setTicketPage(1)
                                                setTicketStatus(v)
                                            }}
                                        />
                                        <Select
                                            placeholder="类别"
                                            allowClear
                                            style={{ width: 130 }}
                                            options={TICKET_CATEGORY_OPTIONS}
                                            value={ticketCategory}
                                            onChange={(v) => {
                                                setTicketPage(1)
                                                setTicketCategory(v)
                                            }}
                                        />
                                        <Select
                                            placeholder="严重度"
                                            allowClear
                                            style={{ width: 110 }}
                                            options={TICKET_SEVERITY_OPTIONS}
                                            value={ticketSeverity}
                                            onChange={(v) => {
                                                setTicketPage(1)
                                                setTicketSeverity(v)
                                            }}
                                        />
                                        <Input.Search
                                            placeholder="工单号 / 标题 / 客户"
                                            allowClear
                                            style={{ width: 220 }}
                                            onSearch={(v) => {
                                                setTicketPage(1)
                                                setTicketKeyword(v)
                                            }}
                                        />
                                        <Button onClick={loadTickets}>刷新</Button>
                                        {canEdit ? (
                                            <Button
                                                type="primary"
                                                icon={<PlusOutlined />}
                                                onClick={() => {
                                                    ticketForm.resetFields()
                                                    ticketForm.setFieldsValue({
                                                        category: 'DELAY',
                                                        severity: 'MEDIUM'
                                                    })
                                                    setTicketFormOpen(true)
                                                }}
                                            >
                                                新建工单
                                            </Button>
                                        ) : null}
                                    </Space>
                                }
                            >
                                <Table
                                    rowKey="id"
                                    loading={ticketLoading}
                                    columns={ticketColumns}
                                    dataSource={tickets}
                                    scroll={{ x: 1600 }}
                                    locale={{ emptyText: <Empty description="没有符合条件的工单" /> }}
                                    pagination={{
                                        current: ticketPage,
                                        pageSize: 10,
                                        total: ticketTotal,
                                        showTotal: (n) => `共 ${n} 条`,
                                        onChange: (p) => setTicketPage(p)
                                    }}
                                    onRow={(row) => ({
                                        onClick: () => openTicketDetail(row),
                                        style: { cursor: 'pointer' }
                                    })}
                                />
                            </Card>
                        )
                    }
                ]}
            />

            {/* 客户详情（含账期与授信） */}
            <Drawer
                title={customerDetail ? `客户详情 · ${customerDetail.name}` : '客户详情'}
                open={!!customerDetail}
                onClose={() => setCustomerDetail(null)}
                width={720}
                destroyOnClose
            >
                {customerDetail ? (
                    <Space direction="vertical" style={{ width: '100%' }} size={12}>
                        <Descriptions column={2} size="small" bordered>
                            <Descriptions.Item label="客户类型">
                                <Tag color={customerTypeMeta(customerDetail.customerType).color}>
                                    {customerTypeMeta(customerDetail.customerType).label}
                                </Tag>
                            </Descriptions.Item>
                            <Descriptions.Item label="国家">
                                {dash(customerDetail.country)}
                            </Descriptions.Item>
                            <Descriptions.Item label="分级">{dash(customerDetail.level)}</Descriptions.Item>
                            <Descriptions.Item label="状态">{dash(customerDetail.status)}</Descriptions.Item>
                            <Descriptions.Item label="税号" span={2}>
                                {dash(customerDetail.taxNo)}
                            </Descriptions.Item>
                            <Descriptions.Item label="IOSS 编号">
                                {dash(customerDetail.iossNo)}
                            </Descriptions.Item>
                            <Descriptions.Item label="默认币种">
                                {dash(customerDetail.defaultCurrency)}
                            </Descriptions.Item>
                        </Descriptions>
                        <Card size="small" title="账期与授信">
                            <Descriptions column={2} size="small">
                                <Descriptions.Item label="账期">
                                    {customerDetail.paymentTermDays == null
                                        ? dash(null)
                                        : customerDetail.paymentTermDays === 0
                                          ? '款到发货'
                                          : `${customerDetail.paymentTermDays} 天`}
                                </Descriptions.Item>
                                <Descriptions.Item label="归属人">
                                    {dash(customerDetail.ownerName)}
                                </Descriptions.Item>
                                <Descriptions.Item label="授信总额" span={2}>
                                    {formatMoney(
                                        customerDetail.creditLimit,
                                        customerDetail.defaultCurrency ?? 'USD'
                                    )}
                                </Descriptions.Item>
                                <Descriptions.Item label="已用额度" span={2}>
                                    <Progress
                                        percent={
                                            customerDetail.creditLimit
                                                ? Math.min(
                                                      100,
                                                      Math.round(
                                                          ((customerDetail.creditUsed ?? 0) /
                                                              customerDetail.creditLimit) *
                                                              100
                                                      )
                                                  )
                                                : 0
                                        }
                                        strokeColor={
                                            customerDetail.creditLimit &&
                                            (customerDetail.creditUsed ?? 0) > customerDetail.creditLimit
                                                ? '#f04438'
                                                : '#4f46e5'
                                        }
                                    />
                                    <span style={{ fontSize: 12, color: '#667085' }}>
                                        {formatMoney(customerDetail.creditUsed, customerDetail.defaultCurrency)}
                                    </span>
                                </Descriptions.Item>
                            </Descriptions>
                            <Alert
                                style={{ marginTop: 8 }}
                                type="info"
                                showIcon
                                message="授信「已用」在 M1 只做展示与人工维护；自动累加要等 M3 的对账域（确认账单时才有累加时点）。"
                            />
                        </Card>
                    </Space>
                ) : (
                    <Empty description="没有选中客户" />
                )}
            </Drawer>

            {/* 工单详情 */}
            <Drawer
                title={ticketDetail ? `工单详情 · ${ticketDetail.ticketNo}` : '工单详情'}
                open={!!ticketDetail}
                onClose={() => setTicketDetail(null)}
                width={720}
                destroyOnClose
            >
                {ticketDetail ? (
                    <Space direction="vertical" style={{ width: '100%' }} size={12}>
                        <Descriptions column={2} size="small" bordered>
                            <Descriptions.Item label="客户">{dash(ticketDetail.customerName)}</Descriptions.Item>
                            <Descriptions.Item label="跟进人">{dash(ticketDetail.ownerName)}</Descriptions.Item>
                            <Descriptions.Item label="出口子单">{dash(ticketDetail.orderNo)}</Descriptions.Item>
                            <Descriptions.Item label="运单号">{dash(ticketDetail.shipmentNo)}</Descriptions.Item>
                            <Descriptions.Item label="类别">
                                <TicketCategoryTag code={ticketDetail.category} />
                            </Descriptions.Item>
                            <Descriptions.Item label="状态">
                                <TicketStatusTag code={ticketDetail.status} />
                            </Descriptions.Item>
                            <Descriptions.Item label="SLA 到期（UTC）" span={2}>
                                <span style={{ color: ticketDetail.overdue ? '#f04438' : undefined }}>
                                    {formatUtc(ticketDetail.dueAt)}
                                    {ticketDetail.overdue ? '（已逾期）' : ''}
                                </span>
                            </Descriptions.Item>
                            <Descriptions.Item label="标题" span={2}>
                                {ticketDetail.title}
                            </Descriptions.Item>
                            <Descriptions.Item label="问题描述" span={2}>
                                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                                    {dash(ticketDetail.detail)}
                                </Typography.Paragraph>
                            </Descriptions.Item>
                            <Descriptions.Item label="处理结果" span={2}>
                                {dash(ticketDetail.resolution)}
                            </Descriptions.Item>
                        </Descriptions>
                        {canEdit && ticketDetail.status !== 'CLOSED' ? (
                            <Button type="primary" onClick={() => openTicketStatus(ticketDetail)}>
                                推进状态
                            </Button>
                        ) : null}
                    </Space>
                ) : (
                    <Empty description="没有选中工单" />
                )}
            </Drawer>

            {/* 新建工单 */}
            <Modal
                title="新建异常工单"
                open={ticketFormOpen}
                onCancel={() => setTicketFormOpen(false)}
                onOk={() => ticketForm.submit()}
                okText="创建"
                cancelText="取消"
                width={620}
                destroyOnClose
            >
                <Form form={ticketForm} layout="vertical" onFinish={createTicket}>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="orderNo" label="出口子单号" style={{ width: 220 }}>
                            <Input placeholder="EXP20261010001" />
                        </Form.Item>
                        <Form.Item name="shipmentNo" label="国际运单号" style={{ width: 220 }}>
                            <Input placeholder="SHP20261010001" />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="category" label="类别" style={{ width: 220 }}>
                            <Select options={TICKET_CATEGORY_OPTIONS} />
                        </Form.Item>
                        <Form.Item name="severity" label="严重度" style={{ width: 160 }}>
                            <Select options={TICKET_SEVERITY_OPTIONS} />
                        </Form.Item>
                    </Space>
                    <Form.Item
                        name="title"
                        label="标题"
                        rules={[{ required: true, message: '请填写标题' }]}
                    >
                        <Input placeholder="例：FDA 查验待回复" />
                    </Form.Item>
                    <Form.Item name="detail" label="问题描述">
                        <Input.TextArea rows={3} />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="customerName" label="客户名称" style={{ width: 280 }}>
                            <Input />
                        </Form.Item>
                        <Form.Item
                            name="dueAt"
                            label="SLA 到期（UTC）"
                            style={{ width: 240 }}
                        >
                            <Input placeholder="2026-10-12T02:00:00" />
                        </Form.Item>
                    </Space>
                </Form>
            </Modal>

            {/* 推进工单状态 */}
            <Modal
                title={ticketStatusTarget ? `推进工单状态 · ${ticketStatusTarget.ticketNo}` : '推进工单状态'}
                open={ticketStatusOpen}
                onCancel={() => setTicketStatusOpen(false)}
                onOk={() => ticketStatusForm.submit()}
                okText="保存"
                cancelText="取消"
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="标记「已解决」必须填处理结果——没有结果的「已解决」等于没解决。"
                />
                <Form form={ticketStatusForm} layout="vertical" onFinish={submitTicketStatus}>
                    <Form.Item name="status" label="目标状态" rules={[{ required: true, message: '请选择状态' }]}>
                        <Select options={TICKET_STATUS_OPTIONS} />
                    </Form.Item>
                    <Form.Item noStyle shouldUpdate={(prev, next) => prev.status !== next.status}>
                        {({ getFieldValue }) =>
                            getFieldValue('status') === 'RESOLVED' ? (
                                <Form.Item
                                    name="resolution"
                                    label="处理结果"
                                    rules={[{ required: true, message: '必须填处理结果' }]}
                                >
                                    <Input.TextArea rows={3} />
                                </Form.Item>
                            ) : null
                        }
                    </Form.Item>
                </Form>
            </Modal>

            {/* 新增渠道商 */}
            <Modal
                title="新增渠道商"
                open={partnerFormOpen}
                onCancel={() => setPartnerFormOpen(false)}
                onOk={() => partnerForm.submit()}
                okText="创建"
                cancelText="取消"
                width={620}
                destroyOnClose
            >
                <Form
                    form={partnerForm}
                    layout="vertical"
                    onFinish={async (values: Record<string, unknown>) => {
                        try {
                            await api.post('/crm/intl/partners', values)
                            message.success('渠道商已新增')
                            setPartnerFormOpen(false)
                            loadPartners()
                            loadDashboard()
                        } catch (err) {
                            message.error(errorMessage(err, '新增渠道商失败'))
                        }
                    }}
                >
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item
                            name="partnerCode"
                            label="渠道商编号"
                            style={{ width: 200 }}
                            rules={[{ required: true, message: '必填' }]}
                        >
                            <Input placeholder="LAZYLION" />
                        </Form.Item>
                        <Form.Item
                            name="partnerName"
                            label="名称"
                            style={{ width: 280 }}
                            rules={[{ required: true, message: '必填' }]}
                        >
                            <Input />
                        </Form.Item>
                        <Form.Item name="partnerType" label="类型" style={{ width: 170 }}>
                            <Select options={PARTNER_TYPE_OPTIONS} />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="contactName" label="联系人" style={{ width: 180 }}>
                            <Input />
                        </Form.Item>
                        <Form.Item name="contactPhone" label="联系电话" style={{ width: 180 }}>
                            <Input />
                        </Form.Item>
                        <Form.Item name="contactEmail" label="邮箱" style={{ width: 240 }}>
                            <Input />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="country" label="国家" style={{ width: 180 }}>
                            <Select options={COUNTRY_OPTIONS} />
                        </Form.Item>
                        <Form.Item name="currency" label="结算币种" style={{ width: 150 }}>
                            <Select options={[{ value: 'USD' }, { value: 'CNY' }, { value: 'EUR' }]} />
                        </Form.Item>
                        <Form.Item name="paymentTermDays" label="账期（天）" style={{ width: 140 }}>
                            <InputNumber min={0} max={180} style={{ width: '100%' }} />
                        </Form.Item>
                        <Form.Item name="rating" label="评级 1~5" style={{ width: 140 }}>
                            <InputNumber min={1} max={5} style={{ width: '100%' }} />
                        </Form.Item>
                    </Space>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="creditLimit" label="我方在其处授信" style={{ width: 200 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                        <Form.Item name="creditUsed" label="已用额度" style={{ width: 200 }}>
                            <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                    </Space>
                </Form>
            </Modal>
        </div>
    )
}
