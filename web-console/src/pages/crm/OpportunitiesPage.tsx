import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Form, Input, Modal, Select, Space, Table, Tag, Tooltip, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../../api/client'
import { Customer, Opportunity, OpportunityPayload, PageResult } from '../../api/types'
import { OPPORTUNITY_STAGE, STAGE_FLOW, StageTag, formatMoney, formatTime, stageMeta } from './shared'

const STAGE_ORDER = ['LEAD', 'PROPOSAL', 'NEGOTIATION', 'WON', 'LOST']

const STAGE_OPTIONS = Object.entries(OPPORTUNITY_STAGE).map(([value, meta]) => ({
    value,
    label: meta.label
}))

/** 阶段看板卡片颜色 */
const STAGE_STYLE: Record<string, { header: string; light: string }> = {
    LEAD: { header: '#667085', light: '#f2f4f7' },
    PROPOSAL: { header: '#4f46e5', light: '#eef2ff' },
    NEGOTIATION: { header: '#2e90fa', light: '#eff8ff' },
    WON: { header: '#12b76a', light: '#ecfdf3' },
    LOST: { header: '#f04438', light: '#fef3f2' }
}

export default function OpportunitiesPage() {
    const [rows, setRows] = useState<Opportunity[]>([])
    const [customers, setCustomers] = useState<Customer[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    const [stage, setStage] = useState<string | undefined>()
    const [keyword, setKeyword] = useState('')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const [formOpen, setFormOpen] = useState(false)
    const [editing, setEditing] = useState<Opportunity | null>(null)
    const [form] = Form.useForm<OpportunityPayload>()

    const load = useCallback(async () => {
        setLoading(true)
        setError(null)
        try {
            const res = await api.get<PageResult<Opportunity>>('/crm/opportunities', {
                params: { page, size, stage: stage || undefined, keyword: keyword || undefined }
            })
            setRows(res.data.list)
            setTotal(res.data.total)
        } catch (err) {
            setError(errorMessage(err, '加载商机列表失败'))
        } finally {
            setLoading(false)
        }
    }, [page, size, stage, keyword])

    useEffect(() => {
        load()
    }, [load])

    // 客户下拉只加载一次（接口上限 500 条）
    useEffect(() => {
        api
            .get<Customer[]>('/crm/customers/brief')
            .then((res) => setCustomers(res.data))
            .catch(() => message.warning('客户列表加载失败，新建商机时可能无法选择客户'))
    }, [])

    const openCreate = () => {
        setEditing(null)
        form.resetFields()
        form.setFieldsValue({ stage: 'LEAD', probability: 20 })
        setFormOpen(true)
    }

    const openEdit = (row: Opportunity) => {
        setEditing(row)
        form.setFieldsValue({
            customerId: row.customerId,
            name: row.name,
            amount: row.amount,
            stage: row.stage,
            probability: row.probability,
            expectedCloseDate: row.expectedCloseDate,
            remark: row.remark
        })
        setFormOpen(true)
    }

    const submit = async (values: OpportunityPayload) => {
        try {
            if (editing) {
                await api.put(`/crm/opportunities/${editing.id}`, { ...values, id: editing.id })
                message.success('商机已更新')
            } else {
                await api.post('/crm/opportunities', values)
                message.success('商机已创建')
            }
            setFormOpen(false)
            load()
        } catch (err) {
            message.error(errorMessage(err, '保存失败'))
        }
    }

    const remove = async (row: Opportunity) => {
        try {
            await api.delete(`/crm/opportunities/${row.id}`)
            message.success(`已删除「${row.name}」`)
            load()
        } catch (err) {
            message.error(errorMessage(err, '删除失败'))
        }
    }

    const moveStage = async (row: Opportunity, nextStage: string) => {
        try {
            await api.patch(`/crm/opportunities/${row.id}/stage`, { stage: nextStage })
            message.success(`「${row.name}」已流转到「${stageMeta(nextStage).label}」`)
            load()
        } catch (err) {
            message.error(errorMessage(err, '阶段流转失败'))
        }
    }

    /** 按阶段分桶，看板用（每次拉前 200 条做展示，够演示规模） */
    const [board, setBoard] = useState<Opportunity[]>([])
    const [boardLoading, setBoardLoading] = useState(false)
    const loadBoard = useCallback(async () => {
        setBoardLoading(true)
        try {
            const res = await api.get<PageResult<Opportunity>>('/crm/opportunities', {
                params: { page: 1, size: 200 }
            })
            setBoard(res.data.list)
        } catch {
            // 看板加载失败不打断表格；表格自己会报错
        } finally {
            setBoardLoading(false)
        }
    }, [])

    useEffect(() => {
        loadBoard()
    }, [loadBoard])

    const columns = [
        {
            title: '商机',
            dataIndex: 'name',
            width: 220,
            render: (_: string, row: Opportunity) => (
                <div className="user-cell-info">
                    <div className="user-cell-name">{row.name}</div>
                    <div className="user-cell-sub">{row.customerName || `客户 #${row.customerId}`}</div>
                </div>
            )
        },
        {
            title: '金额',
            dataIndex: 'amount',
            width: 130,
            align: 'right' as const,
            render: (value: number) => <b style={{ color: '#344054' }}>{formatMoney(value)}</b>
        },
        {
            title: '阶段',
            dataIndex: 'stage',
            width: 100,
            align: 'center' as const,
            render: (value: string) => StageTag(value)
        },
        {
            title: '赢率',
            dataIndex: 'probability',
            width: 120,
            render: (value: number) => (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <div
                        style={{
                            width: 64,
                            height: 6,
                            borderRadius: 3,
                            background: '#eaecf0',
                            overflow: 'hidden'
                        }}
                    >
                        <div
                            style={{
                                width: `${value}%`,
                                height: '100%',
                                background: value >= 70 ? '#12b76a' : value >= 40 ? '#2e90fa' : '#f79009'
                            }}
                        />
                    </div>
                    <span style={{ fontSize: 12, color: '#667085' }}>{value}%</span>
                </div>
            )
        },
        {
            title: '预计成交',
            dataIndex: 'expectedCloseDate',
            width: 120,
            render: (value: string | null) => value || <span style={{ color: '#d0d5dd' }}>未定</span>
        },
        {
            title: '更新时间',
            dataIndex: 'updatedAt',
            width: 150,
            render: (value: string) => <span style={{ color: '#667085', fontSize: 13 }}>{formatTime(value)}</span>
        },
        {
            title: '操作',
            width: 200,
            align: 'center' as const,
            render: (_: unknown, row: Opportunity) => {
                const nextStage = STAGE_FLOW[STAGE_FLOW.indexOf(row.stage) + 1]
                const canLost = row.stage !== 'LOST' && row.stage !== 'WON'
                return (
                    <Space size={4}>
                        {nextStage ? (
                            <Tooltip title={`推进到「${stageMeta(nextStage).label}」`}>
                                <Button type="link" size="small" onClick={() => moveStage(row, nextStage)}>
                                    推进
                                </Button>
                            </Tooltip>
                        ) : null}
                        {canLost ? (
                            <Button type="link" size="small" danger onClick={() => moveStage(row, 'LOST')}>
                                标记输单
                            </Button>
                        ) : null}
                        <Button type="link" size="small" onClick={() => openEdit(row)}>
                            编辑
                        </Button>
                        <Tooltip title="删除商机">
                            <Button type="link" size="small" danger onClick={() => remove(row)}>
                                删除
                            </Button>
                        </Tooltip>
                    </Space>
                )
            }
        }
    ]

    const boardColumns = STAGE_ORDER.map((stageKey) => {
        const items = board.filter((item) => item.stage === stageKey)
        const sum = items.reduce((acc, item) => acc + Number(item.amount || 0), 0)
        const style = STAGE_STYLE[stageKey]
        return (
            <div key={stageKey} style={{ flex: '1 1 0', minWidth: 170 }}>
                <div
                    style={{
                        background: style.light,
                        borderRadius: 10,
                        padding: '8px 10px',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        marginBottom: 8
                    }}
                >
                    <Tag color={stageMeta(stageKey).color} style={{ margin: 0 }}>
                        {stageMeta(stageKey).label}
                    </Tag>
                    <span style={{ fontSize: 12, color: style.header, fontWeight: 600 }}>
                        {items.length} 个 · {formatMoney(sum)}
                    </span>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 320, overflowY: 'auto' }}>
                    {items.length === 0 ? (
                        <div style={{ fontSize: 12, color: '#d0d5dd', textAlign: 'center', padding: '12px 0' }}>
                            暂无商机
                        </div>
                    ) : (
                        items.map((item) => (
                            <Card
                                key={item.id}
                                size="small"
                                styles={{ body: { padding: 10 } }}
                                hoverable
                                onClick={() => openEdit(item)}
                            >
                                <div style={{ fontSize: 13, fontWeight: 600, color: '#344054' }}>{item.name}</div>
                                <div style={{ fontSize: 12, color: '#98a2b3', margin: '2px 0 6px' }}>
                                    {item.customerName || `客户 #${item.customerId}`}
                                </div>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                    <span style={{ fontSize: 13, color: '#4f46e5', fontWeight: 600 }}>
                                        {formatMoney(item.amount)}
                                    </span>
                                    <span style={{ fontSize: 11, color: '#98a2b3' }}>{item.probability}%</span>
                                </div>
                            </Card>
                        ))
                    )}
                </div>
            </div>
        )
    })

    return (
        <>
            {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

            <Card
                className="table-card"
                title="商机看板 · 按阶段流转"
                extra={
                    <Button icon={<ReloadOutlined />} onClick={loadBoard} loading={boardLoading}>
                        刷新看板
                    </Button>
                }
                styles={{ body: { display: 'flex', gap: 12, overflowX: 'auto' } }}
            >
                {board.length === 0 && !boardLoading ? (
                    <Empty description="暂无商机，点击右下「新建商机」创建" style={{ width: '100%' }} />
                ) : (
                    boardColumns
                )}
            </Card>

            <Card
                className="table-card"
                style={{ marginTop: 16 }}
                title={`商机列表 · 共 ${total} 条`}
                extra={
                    <Space>
                        <Select
                            placeholder="全部阶段"
                            allowClear
                            options={STAGE_OPTIONS}
                            value={stage}
                            onChange={(value) => {
                                setPage(1)
                                setStage(value)
                            }}
                            style={{ width: 130 }}
                        />
                        <Input.Search
                            placeholder="搜索商机或客户"
                            allowClear
                            onSearch={(value) => {
                                setPage(1)
                                setKeyword(value)
                            }}
                            style={{ width: 200 }}
                        />
                        <Button onClick={load}>刷新</Button>
                        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                            新建商机
                        </Button>
                    </Space>
                }
            >
                <Table
                    rowKey="id"
                    loading={loading}
                    columns={columns}
                    dataSource={rows}
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

            <Modal
                title={editing ? `编辑商机 · ${editing.name}` : '新建商机'}
                open={formOpen}
                onCancel={() => setFormOpen(false)}
                onOk={() => form.submit()}
                okText="保存"
                cancelText="取消"
                width={520}
                destroyOnClose
            >
                <Form form={form} layout="vertical" onFinish={submit} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="customerId"
                        label="所属客户"
                        rules={[{ required: true, message: '请选择客户' }]}
                    >
                        <Select
                            placeholder="选择客户"
                            showSearch
                            optionFilterProp="label"
                            options={customers.map((item) => ({ value: item.id, label: item.name }))}
                        />
                    </Form.Item>
                    <Form.Item name="name" label="商机名称" rules={[{ required: true, message: '请输入商机名称' }]}>
                        <Input placeholder="如：云启-平台年费续约" />
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
