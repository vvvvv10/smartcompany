import {
    AccountBookOutlined,
    ClockCircleOutlined,
    FileTextOutlined,
    RiseOutlined,
    ShopOutlined,
    TeamOutlined,
    ThunderboltOutlined,
    UserAddOutlined
} from '@ant-design/icons'
import { Alert, Card, Col, List, Result, Row, Spin, Statistic } from 'antd'
import { ReactNode, useEffect, useState } from 'react'
import {
    Bar,
    BarChart,
    CartesianGrid,
    Cell,
    Legend,
    Pie,
    PieChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis
} from 'recharts'
import { api, errorMessage } from '../../api/client'
import type { CrmDashboard as CrmDashboardData, CrmNameCount, CrmRecentItem } from '../../api/types'
import { COLOR } from '../../theme'
import { formatMoney, formatTime, stageMeta, TypeTag } from './shared'

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
    suffix?: string
    icon: ReactNode
    color: string
    bg: string
    hint?: string
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

/** 占比文案，分母为 0 时显示 – */
function percent(part: number, whole: number): string {
    return whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : '–'
}

const chartTooltip = {
    contentStyle: {
        borderRadius: 10,
        border: '1px solid #eaecf0',
        boxShadow: '0 4px 14px rgba(16,24,40,0.08)',
        fontSize: 12
    },
    labelStyle: { color: '#667085' }
}

export default function CrmDashboard() {
    const [data, setData] = useState<CrmDashboardData | null>(null)
    const [sources, setSources] = useState<CrmNameCount[]>([])
    const [recent, setRecent] = useState<{ followUps: CrmRecentItem[]; customers: CrmRecentItem[] } | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        // 三个接口互相独立，任何一个失败只影响对应区块，但看板主数据失败就整体报错
        Promise.all([
            api.get<CrmDashboardData>('/crm/dashboard'),
            api.get<CrmNameCount[]>('/crm/dashboard/sources'),
            api.get<{ followUps: CrmRecentItem[]; customers: CrmRecentItem[] }>('/crm/dashboard/recent')
        ])
            .then(([summaryRes, sourcesRes, recentRes]) => {
                setData(summaryRes.data)
                setSources(sourcesRes.data)
                setRecent(recentRes.data)
            })
            .catch((err) => setError(errorMessage(err, '加载 CRM 看板失败')))
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
        return <Alert type="error" showIcon message={error} />
    }

    if (!data) {
        return null
    }

    const { customers, opportunities, followUps } = data
    const stageData = opportunities.stages.map((item) => ({
        ...item,
        label: stageMeta(item.stage).label
    }))
    const sourceData = sources.map((item) => ({ name: item.name, count: item.count }))

    return (
        <>
            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="客户总数"
                        value={customers.total}
                        icon={<TeamOutlined />}
                        color="#4f46e5"
                        bg="#eef2ff"
                        hint="累计建档"
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="本月新增客户"
                        value={customers.monthNew}
                        icon={<UserAddOutlined />}
                        color="#2e90fa"
                        bg="#eff8ff"
                        hint={`占总量 ${percent(customers.monthNew, customers.total)}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="跟进中客户"
                        value={customers.following}
                        icon={<RiseOutlined />}
                        color="#f79009"
                        bg="#fffaeb"
                        hint={`占总量 ${percent(customers.following, customers.total)}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="成交客户"
                        value={customers.deal}
                        icon={<ShopOutlined />}
                        color="#12b76a"
                        bg="#ecfdf3"
                        hint={`成交率 ${percent(customers.deal, customers.total)}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="进行中商机"
                        value={opportunities.active}
                        icon={<ThunderboltOutlined />}
                        color="#8b5cf6"
                        bg="#f5f3ff"
                        hint={`总金额 ${formatMoney(opportunities.amount)}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="逾期未跟进"
                        value={followUps.overdue}
                        icon={<ClockCircleOutlined />}
                        color={followUps.overdue > 0 ? '#f04438' : '#12b76a'}
                        bg={followUps.overdue > 0 ? '#fef3f2' : '#ecfdf3'}
                        hint={followUps.overdue > 0 ? '需要尽快处理' : '全部已跟进'}
                    />
                </Col>
            </Row>

            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={14}>
                    <Card
                        className="chart-card"
                        title="商机阶段分布"
                        extra={<span>{stageData.length} 个阶段</span>}
                        style={{ height: '100%' }}
                    >
                        <ResponsiveContainer width="100%" height={252}>
                            <BarChart data={stageData} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
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
                                    cursor={{ fill: 'rgba(79,70,229,0.05)' }}
                                    {...chartTooltip}
                                    formatter={(value: number, _name, item) => {
                                        const amount =
                                            item && item.payload ? Number(item.payload.amount) : 0
                                        return [`${value} 个 · ${formatMoney(amount)}`, '商机']
                                    }}
                                />
                                <Bar dataKey="count" radius={[6, 6, 0, 0]} maxBarSize={56}>
                                    {stageData.map((entry, index) => (
                                        <Cell key={entry.stage} fill={COLOR.chart[index % COLOR.chart.length]} />
                                    ))}
                                </Bar>
                            </BarChart>
                        </ResponsiveContainer>
                        <div className="chart-stats">
                            <Statistic
                                title="商机总金额"
                                value={formatMoney(opportunities.amount)}
                                valueStyle={{ fontSize: 18 }}
                            />
                            <Statistic
                                title="赢单金额"
                                value={formatMoney(opportunities.winAmount)}
                                valueStyle={{ fontSize: 18, color: '#12b76a' }}
                            />
                            <Statistic
                                title="跟进记录"
                                value={followUps.total}
                                suffix="条"
                                valueStyle={{ fontSize: 18 }}
                            />
                        </div>
                    </Card>
                </Col>
                <Col xs={24} lg={10}>
                    <Card
                        className="chart-card"
                        title="客户来源分布"
                        extra={<span>{sourceData.length} 个来源</span>}
                        style={{ height: '100%' }}
                    >
                        {sourceData.length === 0 ? (
                            <Result icon={<FileTextOutlined />} title="暂无客户数据" subTitle="先去「客户管理」新建一个客户吧" />
                        ) : (
                            <>
                                <ResponsiveContainer width="100%" height={200}>
                                    <PieChart>
                                        <Pie
                                            data={sourceData}
                                            dataKey="count"
                                            nameKey="name"
                                            innerRadius={54}
                                            outerRadius={80}
                                            paddingAngle={3}
                                            stroke="#fff"
                                            strokeWidth={2}
                                        >
                                            {sourceData.map((entry, index) => (
                                                <Cell
                                                    key={entry.name}
                                                    fill={COLOR.chart[index % COLOR.chart.length]}
                                                />
                                            ))}
                                        </Pie>
                                        <Tooltip {...chartTooltip} formatter={(value: number) => [`${value} 个客户`, '数量']} />
                                    </PieChart>
                                </ResponsiveContainer>
                                <div className="chart-legend">
                                    {sourceData.map((entry, index) => (
                                        <div className="chart-legend-item" key={entry.name}>
                                            <span
                                                className="chart-legend-dot"
                                                style={{ background: COLOR.chart[index % COLOR.chart.length] }}
                                            />
                                            {entry.name} · {entry.count}
                                        </div>
                                    ))}
                                </div>
                            </>
                        )}
                    </Card>
                </Col>
            </Row>

            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={12}>
                    <Card
                        className="chart-card"
                        title="最近跟进动态"
                        extra={<span>共 {recent?.followUps.length ?? 0} 条</span>}
                        style={{ height: '100%' }}
                    >
                        <List
                            size="small"
                            dataSource={recent?.followUps ?? []}
                            locale={{ emptyText: '还没有跟进记录' }}
                            renderItem={(item) => (
                                <List.Item>
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
                                                <FileTextOutlined />
                                            </div>
                                        }
                                        title={
                                            <span style={{ fontSize: 13 }}>
                                                {item.name} {TypeTag(item.type)}
                                            </span>
                                        }
                                        description={
                                            <span style={{ fontSize: 12, color: '#98a2b3' }}>
                                                {formatTime(item.createdAt)}
                                            </span>
                                        }
                                    />
                                </List.Item>
                            )}
                        />
                    </Card>
                </Col>
                <Col xs={24} lg={12}>
                    <Card
                        className="chart-card"
                        title="最新客户"
                        extra={<span>客户总数 {customers.total}</span>}
                        style={{ height: '100%' }}
                    >
                        <List
                            size="small"
                            dataSource={recent?.customers ?? []}
                            locale={{ emptyText: '还没有客户，去「客户管理」新建一个' }}
                            renderItem={(item) => (
                                <List.Item>
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
                                        title={<span style={{ fontSize: 13 }}>{item.name}</span>}
                                        description={
                                            <span style={{ fontSize: 12, color: '#98a2b3' }}>
                                                {formatTime(item.createdAt)}
                                            </span>
                                        }
                                    />
                                </List.Item>
                            )}
                        />
                    </Card>
                </Col>
            </Row>
        </>
    )
}
