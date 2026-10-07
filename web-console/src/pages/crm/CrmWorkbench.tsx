import {
    CheckCircleOutlined,
    ClockCircleOutlined,
    CustomerServiceOutlined,
    RiseOutlined,
    ShopOutlined,
    TeamOutlined,
    ThunderboltOutlined,
    UserAddOutlined
} from '@ant-design/icons'
import { Button, Card, Col, Empty, List, Result, Row, Space, Spin, Statistic, Tag, Typography } from 'antd'
import { ReactNode, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../../api/client'
import type { CrmWorkbench } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { COLOR } from '../../theme'
import { formatMoney, formatTime, LevelTag, StageTag, StatusTag, TypeTag } from './shared'

function StatCard({
    label,
    value,
    suffix,
    icon,
    color,
    bg,
    hint
}: {
    label: string
    value: number | string
    suffix?: ReactNode
    icon: ReactNode
    color: string
    bg: string
    hint?: ReactNode
}) {
    return (
        <div className="stat-card">
            <div className="stat-icon" style={{ background: bg, color }}>
                {icon}
            </div>
            <div className="stat-info">
                <div className="stat-label">{label}</div>
                <div className="stat-value">
                    {value}
                    {suffix ? <span style={{ fontSize: 13, color: '#98a2b3', marginLeft: 4 }}>{suffix}</span> : null}
                </div>
                {hint && <div className="stat-hint">{hint}</div>}
            </div>
        </div>
    )
}

function greeting(): string {
    const hour = new Date().getHours()
    if (hour < 6) return '夜深了'
    if (hour < 12) return '早上好'
    if (hour < 14) return '中午好'
    if (hour < 18) return '下午好'
    return '晚上好'
}

/**
 * CRM 个人工作台：只看与我相关的数据（客户 owner_id、跟进 creator_id 过滤）。
 * 核心是"今天我该跟进谁"——待办清单放在最显眼的位置。
 */
export default function CrmWorkbench() {
    const { profile } = useAuth()
    const navigate = useNavigate()
    const [data, setData] = useState<CrmWorkbench | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        api
            .get<CrmWorkbench>('/crm/workbench')
            .then((res) => setData(res.data))
            .catch((err) => setError(errorMessage(err, '加载工作台失败')))
            .finally(() => setLoading(false))
    }, [])

    if (loading) {
        return (
            <div className="center-screen" style={{ minHeight: 320 }}>
                <Spin size="large" />
            </div>
        )
    }
    if (error) {
        return <Result status="error" title="加载失败" subTitle={error} />
    }
    if (!data) return null

    const { stats, todos, opportunities, customers, followUps } = data
    const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })

    return (
        <div className="fade-in">
            {/* 问候 + 今日待办摘要 */}
            <div className="perm-hero">
                <div>
                    <h2 className="perm-hero-title">
                        {greeting()}，{profile?.nickname || profile?.account}
                    </h2>
                    <p className="perm-hero-desc">
                        {today} · 这里只显示与你相关的客户、商机和跟进，先处理「今日待办」再看商机进度。
                    </p>
                </div>
                <div className="perm-hero-meta">
                    <div className="perm-hero-item">
                        <div className="perm-hero-value">{stats.overdue}</div>
                        <div className="perm-hero-label">已逾期</div>
                    </div>
                    <div className="perm-hero-item">
                        <div className="perm-hero-value">{stats.today}</div>
                        <div className="perm-hero-label">今日待跟进</div>
                    </div>
                    <div className="perm-hero-item">
                        <div className="perm-hero-value">{stats.upcoming}</div>
                        <div className="perm-hero-label">未来 7 天</div>
                    </div>
                </div>
            </div>
            {/* 我的概览指标 */}
            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="我的客户"
                        value={stats.customers}
                        icon={<TeamOutlined />}
                        color="#4f46e5"
                        bg="#eef2ff"
                        hint={`近 7 天新增 ${stats.weekNew}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="跟进中"
                        value={stats.following}
                        icon={<RiseOutlined />}
                        color="#f79009"
                        bg="#fffaeb"
                        hint={`成交 ${stats.deal} 个`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="进行中商机"
                        value={stats.oppActive}
                        icon={<ThunderboltOutlined />}
                        color="#8b5cf6"
                        bg="#f5f3ff"
                        hint={`在手金额 ${formatMoney(stats.oppAmount)}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="赢单金额"
                        value={formatMoney(stats.winAmount)}
                        icon={<ShopOutlined />}
                        color="#12b76a"
                        bg="#ecfdf3"
                        hint="历史累计"
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="待跟进"
                        value={stats.today + stats.upcoming}
                        icon={<ClockCircleOutlined />}
                        color={stats.overdue > 0 ? '#f04438' : '#2e90fa'}
                        bg={stats.overdue > 0 ? '#fef3f2' : '#eff8ff'}
                        suffix="未来 7 天"
                        hint={
                            stats.overdue > 0
                                ? `${stats.overdue} 条已逾期，优先处理`
                                : stats.today > 0
                                    ? `其中 ${stats.today} 条就在今天`
                                    : '没有积压，保持住'
                        }
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="本月新增客户"
                        value={stats.weekNew}
                        icon={<UserAddOutlined />}
                        color="#2e90fa"
                        bg="#eff8ff"
                        hint="近 7 天建档"
                    />
                </Col>
            </Row>

            {/* 今日待办 + 我的商机 */}
            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={14}>
                    <Card
                        className="chart-card"
                        title={
                            <span>
                                跟进待办
                                {stats.overdue > 0 && (
                                    <Tag color="error" style={{ marginInlineStart: 8 }}>
                                        {stats.overdue} 条逾期
                                    </Tag>
                                )}
                                <Tag color={stats.today > 0 ? 'orange' : 'default'} style={{ marginInlineStart: 6 }}>
                                    今日 {stats.today}
                                </Tag>
                                <Tag color={stats.upcoming > 0 ? 'blue' : 'default'} style={{ marginInlineStart: 6 }}>
                                    未来 7 天 {stats.upcoming}
                                </Tag>
                            </span>
                        }
                        extra={
                            <Button size="small" onClick={() => navigate('/crm/customers')}>
                                去客户管理
                            </Button>
                        }
                        style={{ height: '100%' }}
                    >
                        {todos.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty
                                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                                    description={
                                        <Typography.Text type="secondary">
                                            未来 7 天没有待跟进的客户，去「客户管理」建个客户或补条跟进吧
                                        </Typography.Text>
                                    }
                                />
                            </div>
                        ) : (
                            <List
                                size="small"
                                dataSource={todos}
                                renderItem={(item) => {
                                    const overdue = item.due === 'OVERDUE'
                                    const today = item.due === 'TODAY'
                                    return (
                                    <List.Item
                                        key={item.id}
                                        actions={[
                                            <Button
                                                key="go"
                                                size="small"
                                                type="link"
                                                onClick={() => navigate('/crm/customers')}
                                            >
                                                查看客户
                                            </Button>
                                        ]}
                                    >
                                        <List.Item.Meta
                                            avatar={
                                                <div
                                                    style={{
                                                        width: 34,
                                                        height: 34,
                                                        borderRadius: 10,
                                                        background: overdue ? '#fef3f2' : today ? '#fffaeb' : '#eef2ff',
                                                        color: overdue ? '#f04438' : today ? '#f79009' : '#4f46e5',
                                                        display: 'flex',
                                                        alignItems: 'center',
                                                        justifyContent: 'center'
                                                    }}
                                                >
                                                    <ClockCircleOutlined />
                                                </div>
                                            }
                                            title={
                                                <Space size={8} wrap>
                                                    <span style={{ fontSize: 13, fontWeight: 500 }}>
                                                        {item.customerName}
                                                    </span>
                                                    {TypeTag(item.type)}
                                                    {overdue && <Tag color="error">逾期</Tag>}
                                                    {today && <Tag color="orange">今天</Tag>}
                                                    <Typography.Text
                                                        style={{
                                                            fontSize: 12,
                                                            color: overdue
                                                                ? '#f04438'
                                                                : today
                                                                    ? '#f79009'
                                                                    : '#98a2b3'
                                                        }}
                                                    >
                                                        {formatTime(item.nextFollowAt)}
                                                    </Typography.Text>
                                                </Space>
                                            }
                                            description={
                                                <Typography.Text
                                                    type="secondary"
                                                    style={{ fontSize: 12 }}
                                                    ellipsis
                                                >
                                                    {item.content}
                                                </Typography.Text>
                                            }
                                        />
                                    </List.Item>
                                    )
                                }}
                            />
                        )}
                    </Card>
                </Col>
                <Col xs={24} lg={10}>
                    <Card
                        className="chart-card"
                        title="我的进行中商机"
                        extra={<span>{formatMoney(stats.oppAmount)}</span>}
                        style={{ height: '100%' }}
                    >
                        {opportunities.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty
                                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                                    description={
                                        <Typography.Text type="secondary">
                                            还没有进行中的商机
                                        </Typography.Text>
                                    }
                                />
                            </div>
                        ) : (
                            <List
                                size="small"
                                dataSource={opportunities}
                                renderItem={(item) => (
                                    <List.Item key={item.id}>
                                        <List.Item.Meta
                                            avatar={
                                                <div
                                                    style={{
                                                        width: 34,
                                                        height: 34,
                                                        borderRadius: 10,
                                                        background: '#f5f3ff',
                                                        color: '#8b5cf6',
                                                        display: 'flex',
                                                        alignItems: 'center',
                                                        justifyContent: 'center'
                                                    }}
                                                >
                                                    <ThunderboltOutlined />
                                                </div>
                                            }
                                            title={
                                                <Space size={8} wrap>
                                                    <span style={{ fontSize: 13, fontWeight: 500 }}>
                                                        {item.name}
                                                    </span>
                                                    {StageTag(item.stage)}
                                                </Space>
                                            }
                                            description={
                                                <Space size={10} wrap style={{ fontSize: 12, color: '#98a2b3' }}>
                                                    <span>{item.customerName}</span>
                                                    <span style={{ color: '#475467', fontWeight: 500 }}>
                                                        {formatMoney(item.amount)}
                                                    </span>
                                                    <span>赢率 {item.probability}%</span>
                                                    {item.expectedCloseDate && (
                                                        <span>预计 {item.expectedCloseDate}</span>
                                                    )}
                                                </Space>
                                            }
                                        />
                                    </List.Item>
                                )}
                            />
                        )}
                    </Card>
                </Col>
            </Row>

            {/* 我的客户 + 我的跟进动态 */}
            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={12}>
                    <Card
                        className="chart-card"
                        title="我最近建档的客户"
                        extra={<span>共 {stats.customers} 个</span>}
                        style={{ height: '100%' }}
                    >
                        {customers.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty
                                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                                    description={
                                        <Typography.Text type="secondary">
                                            你还没有归属客户，新建客户后会出现在这里
                                        </Typography.Text>
                                    }
                                />
                            </div>
                        ) : (
                            <List
                                size="small"
                                dataSource={customers}
                                renderItem={(item) => (
                                    <List.Item key={item.id}>
                                        <List.Item.Meta
                                            avatar={
                                                <div
                                                    style={{
                                                        width: 34,
                                                        height: 34,
                                                        borderRadius: 10,
                                                        background: '#ecfdf3',
                                                        color: '#12b76a',
                                                        display: 'flex',
                                                        alignItems: 'center',
                                                        justifyContent: 'center',
                                                        fontWeight: 600
                                                    }}
                                                >
                                                    {item.name.slice(0, 1)}
                                                </div>
                                            }
                                            title={
                                                <Space size={8} wrap>
                                                    <span style={{ fontSize: 13, fontWeight: 500 }}>
                                                        {item.name}
                                                    </span>
                                                    {StatusTag(item.status)}
                                                    {LevelTag(item.level)}
                                                </Space>
                                            }
                                            description={
                                                <Typography.Text
                                                    type="secondary"
                                                    style={{ fontSize: 12 }}
                                                >
                                                    建档于 {formatTime(item.createdAt)}
                                                </Typography.Text>
                                            }
                                        />
                                    </List.Item>
                                )}
                            />
                        )}
                    </Card>
                </Col>
                <Col xs={24} lg={12}>
                    <Card
                        className="chart-card"
                        title="我的跟进动态"
                        extra={<span>共 {followUps.length} 条</span>}
                        style={{ height: '100%' }}
                    >
                        {followUps.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty
                                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                                    description={
                                        <Typography.Text type="secondary">
                                            还没有跟进记录，跟进客户后这里会留下痕迹
                                        </Typography.Text>
                                    }
                                />
                            </div>
                        ) : (
                            <List
                                size="small"
                                dataSource={followUps}
                                renderItem={(item) => (
                                    <List.Item key={`${item.customerName}-${item.createdAt}`}>
                                        <List.Item.Meta
                                            avatar={
                                                <div
                                                    style={{
                                                        width: 34,
                                                        height: 34,
                                                        borderRadius: 10,
                                                        background: '#eef2ff',
                                                        color: '#4f46e5',
                                                        display: 'flex',
                                                        alignItems: 'center',
                                                        justifyContent: 'center'
                                                    }}
                                                >
                                                    <CustomerServiceOutlined />
                                                </div>
                                            }
                                            title={
                                                <Space size={8} wrap>
                                                    <span style={{ fontSize: 13, fontWeight: 500 }}>
                                                        {item.customerName}
                                                    </span>
                                                    {TypeTag(item.type)}
                                                </Space>
                                            }
                                            description={
                                                <Space direction="vertical" size={0}>
                                                    <Typography.Text
                                                        type="secondary"
                                                        style={{ fontSize: 12 }}
                                                        ellipsis
                                                    >
                                                        {item.content}
                                                    </Typography.Text>
                                                    <Typography.Text
                                                        style={{ fontSize: 12, color: '#98a2b3' }}
                                                    >
                                                        {formatTime(item.createdAt)}
                                                    </Typography.Text>
                                                </Space>
                                            }
                                        />
                                    </List.Item>
                                )}
                            />
                        )}
                    </Card>
                </Col>
            </Row>

            {/* 团队大盘快捷入口 */}
            <Card className="chart-card" style={{ marginTop: 16 }} styles={{ body: { padding: '14px 20px' } }}>
                <div
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 16,
                        flexWrap: 'wrap'
                    }}
                >
                    <Space size={24} wrap>
                        <Statistic
                            title="团队商机总金额"
                            value={formatMoney(stats.oppAmount)}
                            valueStyle={{ fontSize: 18, color: COLOR.chart[0] }}
                        />
                        <Statistic
                            title="我的赢单金额"
                            value={formatMoney(stats.winAmount)}
                            valueStyle={{ fontSize: 18, color: '#12b76a' }}
                        />
                        <Statistic
                            title="我的客户"
                            value={stats.customers}
                            suffix="个"
                            valueStyle={{ fontSize: 18 }}
                        />
                    </Space>
                    <Space>
                        <Button onClick={() => navigate('/crm/opportunities')}>
                            <ShopOutlined /> 商机管理
                        </Button>
                        <Button type="primary" onClick={() => navigate('/crm/customers')}>
                            <CheckCircleOutlined /> 客户管理
                        </Button>
                    </Space>
                </div>
            </Card>
        </div>
    )
}
