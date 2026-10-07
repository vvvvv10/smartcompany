import {
    DeleteOutlined,
    EditOutlined,
    MailOutlined,
    PhoneOutlined,
    PlusOutlined
} from '@ant-design/icons'
import {
    Alert,
    Badge,
    Button,
    Card,
    Drawer,
    Empty,
    Form,
    Input,
    Modal,
    Popconfirm,
    Select,
    Space,
    Spin,
    Tabs,
    Table,
    Tag,
    Tooltip,
    message
} from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import {
    Contact,
    ContactPayload,
    Customer,
    CustomerPayload,
    FollowUp,
    FollowUpPayload,
    Opportunity,
    OpportunityPayload,
    PageResult
} from '../../api/types'
import {
    CUSTOMER_LEVEL,
    CUSTOMER_STATUS,
    FOLLOW_UP_TYPE,
    OPPORTUNITY_STAGE,
    SOURCE_OPTIONS,
    STAGE_FLOW,
    LevelTag,
    StageTag,
    StatusTag,
    TypeTag,
    formatMoney,
    formatTime,
    stageMeta
} from './shared'

const STATUS_OPTIONS = Object.entries(CUSTOMER_STATUS).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const LEVEL_OPTIONS = Object.entries(CUSTOMER_LEVEL).map(([value, meta]) => ({
    value,
    label: meta.label
}))

const STAGE_OPTIONS = Object.entries(OPPORTUNITY_STAGE).map(([value, meta]) => ({
    value,
    label: meta.label
}))

export default function CustomersPage() {
    const [rows, setRows] = useState<Customer[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    const [keyword, setKeyword] = useState('')
    const [status, setStatus] = useState<string | undefined>()
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const [formOpen, setFormOpen] = useState(false)
    const [editing, setEditing] = useState<Customer | null>(null)
    const [form] = Form.useForm<CustomerPayload>()

    // 详情抽屉
    const [detail, setDetail] = useState<Customer | null>(null)
    const [contacts, setContacts] = useState<Contact[]>([])
    const [opportunities, setOpportunities] = useState<Opportunity[]>([])
    const [followUps, setFollowUps] = useState<FollowUp[]>([])
    const [detailLoading, setDetailLoading] = useState(false)
    const [tabKey, setTabKey] = useState('contacts')

    const [contactModalOpen, setContactModalOpen] = useState(false)
    const [editingContact, setEditingContact] = useState<Contact | null>(null)
    const [contactForm] = Form.useForm<ContactPayload>()

    const [followModalOpen, setFollowModalOpen] = useState(false)
    const [followForm] = Form.useForm<FollowUpPayload>()

    const [oppModalOpen, setOppModalOpen] = useState(false)
    const [editingOpp, setEditingOpp] = useState<Opportunity | null>(null)
    const [oppForm] = Form.useForm<OpportunityPayload>()

    const load = useCallback(async () => {
        setLoading(true)
        setError(null)
        try {
            const res = await api.get<PageResult<Customer>>('/crm/customers', {
                params: { page, size, keyword: keyword || undefined, status: status || undefined }
            })
            setRows(res.data.list)
            setTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载客户列表失败'))
        } finally {
            setLoading(false)
        }
    }, [page, size, keyword, status])

    useEffect(() => {
        load()
    }, [load])

    /** 打开/刷新详情抽屉：客户基本信息 + 三个子列表 */
    const openDetail = async (row: Customer) => {
        setDetail(row)
        setTabKey('contacts')
        setDetailLoading(true)
        try {
            const [detailRes, contactRes, oppRes, followRes] = await Promise.all([
                api.get<Customer>(`/crm/customers/${row.id}`),
                api.get<Contact[]>(`/crm/customers/${row.id}/contacts`),
                api.get<PageResult<Opportunity>>('/crm/opportunities', { params: { customerId: row.id, size: 100 } }),
                api.get<FollowUp[]>(`/crm/customers/${row.id}/follow-ups`)
            ])
            setDetail(detailRes.data)
            setContacts(contactRes.data)
            setOpportunities(oppRes.data.list)
            setFollowUps(followRes.data)
        } catch (err) {
            message.error(errorMessage(err, '加载客户详情失败'))
        } finally {
            setDetailLoading(false)
        }
    }

    const closeDetail = () => {
        setDetail(null)
        setContacts([])
        setOpportunities([])
        setFollowUps([])
    }

    const openCreate = () => {
        setEditing(null)
        form.resetFields()
        form.setFieldsValue({ level: 'NORMAL', status: 'POTENTIAL', source: '其他' })
        setFormOpen(true)
    }

    const openEdit = (row: Customer) => {
        setEditing(row)
        form.setFieldsValue({
            name: row.name,
            industry: row.industry,
            level: row.level,
            source: row.source,
            status: row.status,
            phone: row.phone,
            email: row.email,
            address: row.address,
            remark: row.remark
        })
        setFormOpen(true)
    }

    const submitCustomer = async (values: CustomerPayload) => {
        try {
            if (editing) {
                await api.put(`/crm/customers/${editing.id}`, { ...values, id: editing.id })
                message.success('客户已更新')
            } else {
                await api.post('/crm/customers', values)
                message.success('客户已创建')
            }
            setFormOpen(false)
            load()
            if (detail && editing && detail.id === editing.id) {
                openDetail(editing)
            }
        } catch (err) {
            message.error(errorMessage(err, '保存失败'))
        }
    }

    const removeCustomer = async (row: Customer) => {
        try {
            await api.delete(`/crm/customers/${row.id}`)
            message.success(`已删除「${row.name}」`)
            if (detail?.id === row.id) {
                closeDetail()
            }
            load()
        } catch (err) {
            message.error(errorMessage(err, '删除失败'))
        }
    }

    // ---- 联系人 ----
    const openContact = (row?: Contact) => {
        setEditingContact(row ?? null)
        contactForm.resetFields()
        if (row) {
            contactForm.setFieldsValue({
                name: row.name,
                position: row.position,
                phone: row.phone,
                email: row.email,
                isPrimary: row.isPrimary === 1
            })
        } else {
            contactForm.setFieldsValue({ isPrimary: false })
        }
        setContactModalOpen(true)
    }

    const submitContact = async (values: ContactPayload) => {
        if (!detail) return
        try {
            if (editingContact) {
                await api.put(`/crm/contacts/${editingContact.id}`, values)
                message.success('联系人已更新')
            } else {
                await api.post(`/crm/customers/${detail.id}/contacts`, values)
                message.success('联系人已添加')
            }
            setContactModalOpen(false)
            const [contactRes, detailRes] = await Promise.all([
                api.get<Contact[]>(`/crm/customers/${detail.id}/contacts`),
                api.get<Customer>(`/crm/customers/${detail.id}`)
            ])
            setContacts(contactRes.data)
            setDetail(detailRes.data)
        } catch (err) {
            message.error(errorMessage(err, '保存联系人失败'))
        }
    }

    const removeContact = async (row: Contact) => {
        if (!detail) return
        try {
            await api.delete(`/crm/contacts/${row.id}`)
            message.success('联系人已删除')
            const res = await api.get<Contact[]>(`/crm/customers/${detail.id}/contacts`)
            setContacts(res.data)
        } catch (err) {
            message.error(errorMessage(err, '删除失败'))
        }
    }

    // ---- 跟进 ----
    const openFollow = () => {
        followForm.resetFields()
        followForm.setFieldsValue({ type: 'CALL' })
        setFollowModalOpen(true)
    }

    const submitFollow = async (values: FollowUpPayload) => {
        if (!detail) return
        try {
            await api.post(`/crm/customers/${detail.id}/follow-ups`, {
                ...values,
                nextFollowAt: values.nextFollowAt ? values.nextFollowAt : null
            })
            message.success('跟进已记录')
            setFollowModalOpen(false)
            const res = await api.get<FollowUp[]>(`/crm/customers/${detail.id}/follow-ups`)
            setFollowUps(res.data)
        } catch (err) {
            message.error(errorMessage(err, '记录跟进失败'))
        }
    }

    const removeFollow = async (row: FollowUp) => {
        try {
            await api.delete(`/crm/follow-ups/${row.id}`)
            message.success('跟进记录已删除')
            setFollowUps((prev) => prev.filter((item) => item.id !== row.id))
        } catch (err) {
            message.error(errorMessage(err, '删除失败'))
        }
    }

    // ---- 商机 ----
    const openOpp = (row?: Opportunity) => {
        setEditingOpp(row ?? null)
        oppForm.resetFields()
        if (row) {
            oppForm.setFieldsValue({
                customerId: row.customerId,
                name: row.name,
                amount: row.amount,
                stage: row.stage,
                probability: row.probability,
                expectedCloseDate: row.expectedCloseDate,
                remark: row.remark
            })
        } else {
            oppForm.setFieldsValue({ customerId: detail?.id, stage: 'LEAD', probability: 20 })
        }
        setOppModalOpen(true)
    }

    const submitOpp = async (values: OpportunityPayload) => {
        try {
            if (editingOpp) {
                await api.put(`/crm/opportunities/${editingOpp.id}`, { ...values, id: editingOpp.id })
                message.success('商机已更新')
            } else {
                await api.post('/crm/opportunities', values)
                message.success('商机已创建')
            }
            setOppModalOpen(false)
            if (detail) {
                const res = await api.get<PageResult<Opportunity>>('/crm/opportunities', {
                    params: { customerId: detail.id, size: 100 }
                })
                setOpportunities(res.data.list)
            }
        } catch (err) {
            message.error(errorMessage(err, '保存商机失败'))
        }
    }

    const removeOpp = async (row: Opportunity) => {
        try {
            await api.delete(`/crm/opportunities/${row.id}`)
            message.success('商机已删除')
            setOpportunities((prev) => prev.filter((item) => item.id !== row.id))
        } catch (err) {
            message.error(errorMessage(err, '删除失败'))
        }
    }

    const moveStage = async (row: Opportunity, stage: string) => {
        try {
            await api.patch(`/crm/opportunities/${row.id}/stage`, { stage })
            message.success(`已推进到「${stageMeta(stage).label}」`)
            setOpportunities((prev) =>
                prev.map((item) =>
                    item.id === row.id
                        ? { ...item, stage, probability: stage === 'WON' ? 100 : stage === 'LOST' ? 0 : item.probability }
                        : item
                )
            )
        } catch (err) {
            message.error(errorMessage(err, '阶段流转失败'))
        }
    }

    const columns = [
        {
            title: '客户',
            dataIndex: 'name',
            width: 230,
            render: (_: string, row: Customer) => (
                <div className="user-cell">
                    <div
                        style={{
                            width: 36,
                            height: 36,
                            borderRadius: 10,
                            flex: 'none',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: '#fff',
                            fontSize: 14,
                            fontWeight: 600,
                            background: 'linear-gradient(135deg, #4f46e5 0%, #7c3aed 100%)'
                        }}
                    >
                        {row.name.slice(0, 1)}
                    </div>
                    <div className="user-cell-info">
                        <div className="user-cell-name">{row.name}</div>
                        <div className="user-cell-sub">
                            {row.industry || '未填行业'} · {row.source || '未知来源'}
                        </div>
                    </div>
                </div>
            )
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 96,
            align: 'center' as const,
            render: (value: string) => StatusTag(value)
        },
        {
            title: '等级',
            dataIndex: 'level',
            width: 90,
            align: 'center' as const,
            render: (value: string) => LevelTag(value)
        },
        {
            title: '联系人 / 商机',
            width: 130,
            align: 'center' as const,
            render: (_: unknown, row: Customer) => (
                <Space size={4}>
                    <Tag color="blue">{row.contactCount} 人</Tag>
                    <Tag color="purple">{row.opportunityCount} 个商机</Tag>
                </Space>
            )
        },
        {
            title: '联系方式',
            width: 170,
            render: (_: unknown, row: Customer) =>
                row.phone ? (
                    <span style={{ fontSize: 13, color: '#475467' }}>
                        <PhoneOutlined style={{ marginRight: 4, color: '#98a2b3' }} />
                        {row.phone}
                    </span>
                ) : (
                    <span style={{ color: '#d0d5dd' }}>—</span>
                )
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            width: 150,
            render: (value: string) => <span style={{ color: '#667085', fontSize: 13 }}>{formatTime(value)}</span>
        },
        {
            title: '操作',
            width: 180,
            align: 'center' as const,
            render: (_: unknown, row: Customer) => (
                <Space size={4}>
                    <Button type="link" size="small" onClick={() => openDetail(row)}>
                        详情
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(row)} />
                    <Popconfirm
                        title={`删除客户「${row.name}」？`}
                        description="将同时删除其联系人、商机与跟进记录"
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        cancelText="取消"
                        onConfirm={() => removeCustomer(row)}
                    >
                        <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                </Space>
            )
        }
    ]

    const detailTabs = [
        {
            key: 'contacts',
            label: `联系人 (${contacts.length})`,
            children: (
                <Spin spinning={detailLoading}>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                        <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openContact()}>
                            新建联系人
                        </Button>
                    </div>
                    {contacts.length === 0 ? (
                        <Empty description="还没有联系人" />
                    ) : (
                        <Table
                            rowKey="id"
                            size="small"
                            pagination={false}
                            dataSource={contacts}
                            columns={[
                                {
                                    title: '姓名',
                                    dataIndex: 'name',
                                    render: (value: string, row: Contact) => (
                                        <span>
                                            {value}
                                            {row.isPrimary === 1 ? (
                                                <Tag color="gold" style={{ marginLeft: 6 }}>
                                                    主要
                                                </Tag>
                                            ) : null}
                                        </span>
                                    )
                                },
                                { title: '职位', dataIndex: 'position', render: (v: string) => v || '—' },
                                { title: '电话', dataIndex: 'phone', render: (v: string) => v || '—' },
                                { title: '邮箱', dataIndex: 'email', render: (v: string) => v || '—' },
                                {
                                    title: '操作',
                                    width: 130,
                                    align: 'center' as const,
                                    render: (_: unknown, row: Contact) => (
                                        <Space size={4}>
                                            <Button type="link" size="small" onClick={() => openContact(row)}>
                                                编辑
                                            </Button>
                                            <Popconfirm
                                                title="删除该联系人？"
                                                okText="删除"
                                                okButtonProps={{ danger: true }}
                                                cancelText="取消"
                                                onConfirm={() => removeContact(row)}
                                            >
                                                <Button type="link" size="small" danger>
                                                    删除
                                                </Button>
                                            </Popconfirm>
                                        </Space>
                                    )
                                }
                            ]}
                        />
                    )}
                </Spin>
            )
        },
        {
            key: 'opportunities',
            label: `商机 (${opportunities.length})`,
            children: (
                <Spin spinning={detailLoading}>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                        <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openOpp()}>
                            新建商机
                        </Button>
                    </div>
                    {opportunities.length === 0 ? (
                        <Empty description="还没有商机" />
                    ) : (
                        <Table
                            rowKey="id"
                            size="small"
                            pagination={false}
                            dataSource={opportunities}
                            columns={[
                                { title: '商机', dataIndex: 'name' },
                                {
                                    title: '金额',
                                    dataIndex: 'amount',
                                    width: 110,
                                    align: 'right' as const,
                                    render: (value: number) => <b>{formatMoney(value)}</b>
                                },
                                {
                                    title: '阶段',
                                    dataIndex: 'stage',
                                    width: 96,
                                    render: (value: string) => StageTag(value)
                                },
                                {
                                    title: '赢率',
                                    dataIndex: 'probability',
                                    width: 80,
                                    render: (value: number) => `${value}%`
                                },
                                {
                                    title: '预计成交',
                                    dataIndex: 'expectedCloseDate',
                                    width: 110,
                                    render: (value: string | null) => value || '—'
                                },
                                {
                                    title: '操作',
                                    width: 170,
                                    align: 'center' as const,
                                    render: (_: unknown, row: Opportunity) => {
                                        const nextIndex = STAGE_FLOW.indexOf(row.stage) + 1
                                        const nextStage = STAGE_FLOW[nextIndex]
                                        return (
                                            <Space size={4}>
                                                {nextStage ? (
                                                    <Tooltip title={`推进到「${stageMeta(nextStage).label}」`}>
                                                        <Button
                                                            type="link"
                                                            size="small"
                                                            onClick={() => moveStage(row, nextStage)}
                                                        >
                                                            推进
                                                        </Button>
                                                    </Tooltip>
                                                ) : null}
                                                <Button type="link" size="small" onClick={() => openOpp(row)}>
                                                    编辑
                                                </Button>
                                                <Popconfirm
                                                    title="删除该商机？"
                                                    okText="删除"
                                                    okButtonProps={{ danger: true }}
                                                    cancelText="取消"
                                                    onConfirm={() => removeOpp(row)}
                                                >
                                                    <Button type="link" size="small" danger>
                                                        删除
                                                    </Button>
                                                </Popconfirm>
                                            </Space>
                                        )
                                    }
                                }
                            ]}
                        />
                    )}
                </Spin>
            )
        },
        {
            key: 'followUps',
            label: `跟进记录 (${followUps.length})`,
            children: (
                <Spin spinning={detailLoading}>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                        <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openFollow}>
                            记录跟进
                        </Button>
                    </div>
                    {followUps.length === 0 ? (
                        <Empty description="还没有跟进记录" />
                    ) : (
                        <Table
                            rowKey="id"
                            size="small"
                            pagination={false}
                            dataSource={followUps}
                            columns={[
                                {
                                    title: '类型',
                                    dataIndex: 'type',
                                    width: 86,
                                    render: (value: string) => TypeTag(value)
                                },
                                { title: '内容', dataIndex: 'content' },
                                {
                                    title: '下次跟进',
                                    dataIndex: 'nextFollowAt',
                                    width: 140,
                                    render: (value: string | null) => {
                                        if (!value) {
                                            return <span style={{ color: '#d0d5dd' }}>未安排</span>
                                        }
                                        const overdue = new Date(value).getTime() < Date.now()
                                        return (
                                            <span style={{ color: overdue ? '#f04438' : '#475467', fontSize: 13 }}>
                                                {formatTime(value)}
                                                {overdue ? ' ⚠' : ''}
                                            </span>
                                        )
                                    }
                                },
                                {
                                    title: '记录时间',
                                    dataIndex: 'createdAt',
                                    width: 140,
                                    render: (value: string) => (
                                        <span style={{ color: '#98a2b3', fontSize: 13 }}>{formatTime(value)}</span>
                                    )
                                },
                                {
                                    title: '操作',
                                    width: 80,
                                    align: 'center' as const,
                                    render: (_: unknown, row: FollowUp) => (
                                        <Popconfirm
                                            title="删除该跟进记录？"
                                            okText="删除"
                                            okButtonProps={{ danger: true }}
                                            cancelText="取消"
                                            onConfirm={() => removeFollow(row)}
                                        >
                                            <Button type="link" size="small" danger>
                                                删除
                                            </Button>
                                        </Popconfirm>
                                    )
                                }
                            ]}
                        />
                    )}
                </Spin>
            )
        }
    ]

    return (
        <>
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            <Card
                className="table-card"
                title={`客户列表 · 共 ${total} 个客户`}
                extra={
                    <Space>
                        <Select
                            placeholder="客户状态"
                            allowClear
                            options={STATUS_OPTIONS}
                            value={status}
                            onChange={(value) => {
                                setPage(1)
                                setStatus(value)
                            }}
                            style={{ width: 120 }}
                        />
                        <Input.Search
                            placeholder="搜索名称 / 电话 / 行业"
                            allowClear
                            onSearch={(value) => {
                                setPage(1)
                                setKeyword(value)
                            }}
                            style={{ width: 220 }}
                        />
                        <Button onClick={load}>刷新</Button>
                        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                            新建客户
                        </Button>
                    </Space>
                }
            >
                <Table
                    rowKey="id"
                    loading={loading}
                    columns={columns}
                    dataSource={rows}
                    onRow={(row) => ({ onClick: () => openDetail(row), style: { cursor: 'pointer' } })}
                    pagination={{
                        current: page,
                        pageSize: size,
                        total,
                        showSizeChanger: true,
                        showTotal: (count) => `共 ${count} 条`,
                        onChange: (nextPage, nextSize) => {
                            setPage(nextPage)
                            setSize(nextSize)
                        }
                    }}
                />
            </Card>

            {/* 新建 / 编辑客户 */}
            <Modal
                title={editing ? `编辑客户 · ${editing.name}` : '新建客户'}
                open={formOpen}
                onCancel={() => setFormOpen(false)}
                onOk={() => form.submit()}
                okText="保存"
                cancelText="取消"
                width={640}
                destroyOnClose
            >
                <Form form={form} layout="vertical" onFinish={submitCustomer} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="name"
                        label="客户名称"
                        rules={[
                            { required: true, message: '请输入客户名称' },
                            { max: 64, message: '最长 64 字' }
                        ]}
                    >
                        <Input placeholder="如：云启科技" />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="industry" label="行业" style={{ width: 180 }}>
                            <Input placeholder="如：互联网" />
                        </Form.Item>
                        <Form.Item name="level" label="等级" style={{ width: 140 }}>
                            <Select options={LEVEL_OPTIONS} />
                        </Form.Item>
                        <Form.Item name="status" label="状态" style={{ width: 140 }}>
                            <Select options={STATUS_OPTIONS} />
                        </Form.Item>
                    </Space>
                    <Form.Item name="source" label="来源">
                        <Select options={SOURCE_OPTIONS.map((value) => ({ value, label: value }))} />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="phone" label="电话" style={{ width: 220 }}>
                            <Input placeholder="如：021-66881000" />
                        </Form.Item>
                        <Form.Item name="email" label="邮箱" style={{ width: 260 }}>
                            <Input placeholder="如：user13@example.com" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="address" label="地址">
                        <Input placeholder="详细地址" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} maxLength={255} showCount placeholder="备注（选填）" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 客户详情抽屉 */}
            <Drawer
                title={
                    detail ? (
                        <Space>
                            <span>{detail.name}</span>
                            {StatusTag(detail.status)}
                            {LevelTag(detail.level)}
                        </Space>
                    ) : (
                        '客户详情'
                    )
                }
                open={!!detail}
                onClose={closeDetail}
                width={760}
                destroyOnClose
                extra={
                    detail ? (
                        <Space>
                            <Badge status="processing" text={`ID ${detail.id}`} />
                            <Button
                                size="small"
                                icon={<EditOutlined />}
                                onClick={() => openEdit(detail)}
                            >
                                编辑
                            </Button>
                        </Space>
                    ) : null
                }
            >
                {detail ? (
                    <>
                        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', marginBottom: 8 }}>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3' }}>联系方式</div>
                                <div style={{ fontSize: 13, color: '#344054' }}>
                                    {detail.phone ? (
                                        <span style={{ marginRight: 12 }}>
                                            <PhoneOutlined style={{ color: '#98a2b3', marginRight: 4 }} />
                                            {detail.phone}
                                        </span>
                                    ) : null}
                                    {detail.email ? (
                                        <span>
                                            <MailOutlined style={{ color: '#98a2b3', marginRight: 4 }} />
                                            {detail.email}
                                        </span>
                                    ) : null}
                                    {!detail.phone && !detail.email ? <span style={{ color: '#d0d5dd' }}>未填写</span> : null}
                                </div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3' }}>来源 / 行业</div>
                                <div style={{ fontSize: 13, color: '#344054' }}>
                                    {detail.source || '未知'} · {detail.industry || '未填'}
                                </div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3' }}>归属人</div>
                                <div style={{ fontSize: 13, color: '#344054' }}>{detail.ownerName}</div>
                            </div>
                            <div>
                                <div style={{ fontSize: 12, color: '#98a2b3' }}>创建时间</div>
                                <div style={{ fontSize: 13, color: '#344054' }}>{formatTime(detail.createdAt)}</div>
                            </div>
                        </div>
                        {detail.address ? (
                            <div style={{ fontSize: 13, color: '#475467', marginBottom: 8 }}>📍 {detail.address}</div>
                        ) : null}
                        {detail.remark ? (
                            <Alert
                                type="info"
                                showIcon
                                message={<span style={{ fontSize: 13 }}>{detail.remark}</span>}
                                style={{ marginBottom: 12 }}
                            />
                        ) : null}
                        <Tabs activeKey={tabKey} onChange={setTabKey} items={detailTabs} />
                    </>
                ) : null}
            </Drawer>

            {/* 联系人 */}
            <Modal
                title={editingContact ? '编辑联系人' : '新建联系人'}
                open={contactModalOpen}
                onCancel={() => setContactModalOpen(false)}
                onOk={() => contactForm.submit()}
                okText="保存"
                cancelText="取消"
                width={460}
                destroyOnClose
            >
                <Form form={contactForm} layout="vertical" onFinish={submitContact} style={{ marginTop: 16 }}>
                    <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
                        <Input placeholder="联系人姓名" />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="position" label="职位" style={{ width: 180 }}>
                            <Input placeholder="如：采购总监" />
                        </Form.Item>
                        <Form.Item name="phone" label="电话" style={{ width: 180 }}>
                            <Input />
                        </Form.Item>
                    </Space>
                    <Form.Item name="email" label="邮箱">
                        <Input />
                    </Form.Item>
                    <Form.Item name="isPrimary" label="主要联系人" valuePropName="checked">
                        <Select
                            options={[
                                { value: true, label: '是' },
                                { value: false, label: '否' }
                            ]}
                        />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 跟进 */}
            <Modal
                title="记录跟进"
                open={followModalOpen}
                onCancel={() => setFollowModalOpen(false)}
                onOk={() => followForm.submit()}
                okText="保存"
                cancelText="取消"
                width={460}
                destroyOnClose
            >
                <Form form={followForm} layout="vertical" onFinish={submitFollow} style={{ marginTop: 16 }}>
                    <Form.Item name="type" label="跟进方式">
                        <Select
                            options={Object.entries(FOLLOW_UP_TYPE).map(([value, meta]) => ({
                                value,
                                label: meta.label
                            }))}
                        />
                    </Form.Item>
                    <Form.Item
                        name="content"
                        label="跟进内容"
                        rules={[{ required: true, message: '请输入跟进内容' }]}
                    >
                        <Input.TextArea rows={3} maxLength={1000} showCount placeholder="沟通了什么、结论是什么" />
                    </Form.Item>
                    <Form.Item name="nextFollowAt" label="下次跟进时间" extra="留空表示暂不安排">
                        <Input placeholder="如：2026-10-08 10:00 或 2026-10-08" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 商机 */}
            <Modal
                title={editingOpp ? `编辑商机 · ${editingOpp.name}` : '新建商机'}
                open={oppModalOpen}
                onCancel={() => setOppModalOpen(false)}
                onOk={() => oppForm.submit()}
                okText="保存"
                cancelText="取消"
                width={520}
                destroyOnClose
            >
                <Form form={oppForm} layout="vertical" onFinish={submitOpp} style={{ marginTop: 16 }}>
                    <Form.Item name="customerId" label="所属客户" hidden={!editingOpp && !detail} rules={[{ required: true, message: '请选择客户' }]}>
                        {editingOpp || detail ? (
                            <Select
                                placeholder="选择客户"
                                showSearch
                                optionFilterProp="label"
                                options={rows.map((item) => ({ value: item.id, label: item.name }))}
                                disabled={!!detail && !editingOpp}
                            />
                        ) : (
                            <Select placeholder="选择客户" options={[]} />
                        )}
                    </Form.Item>
                    <Form.Item name="name" label="商机名称" rules={[{ required: true, message: '请输入商机名称' }]}>
                        <Input placeholder="如：平台年费续约" />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                        <Form.Item name="amount" label="金额（元）" style={{ width: 160 }}>
                            <Input type="number" min={0} placeholder="0.00" />
                        </Form.Item>
                        <Form.Item name="stage" label="阶段" style={{ width: 140 }}>
                            <Select options={STAGE_OPTIONS} />
                        </Form.Item>
                        <Form.Item name="probability" label="赢率 %" style={{ width: 110 }}>
                            <Input type="number" min={0} max={100} />
                        </Form.Item>
                    </Space>
                    <Form.Item name="expectedCloseDate" label="预计成交日期" extra="格式 yyyy-MM-dd，留空表示未定">
                        <Input placeholder="2026-12-31" />
                    </Form.Item>
                    <Form.Item name="remark" label="备注">
                        <Input.TextArea rows={2} maxLength={255} showCount />
                    </Form.Item>
                </Form>
            </Modal>
        </>
    )
}
