import {
    CheckCircleOutlined,
    LineChartOutlined,
    RiseOutlined,
    StopOutlined,
    TeamOutlined,
    ThunderboltOutlined
} from '@ant-design/icons'
import { Alert, Card, Col, Result, Row, Spin } from 'antd'
import { ReactNode, useEffect, useState } from 'react'
import {
    Area,
    AreaChart,
    CartesianGrid,
    Cell,
    Pie,
    PieChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis
} from 'recharts'
import { api, errorMessage } from '../api/client'
import { Dashboard as DashboardData } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { COLOR } from '../theme'

function StatCard({
    label,
    value,
    icon,
    color,
    bg,
    hint
}: {
    label: string
    value: number
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
                <div className="stat-value">{value}</div>
                {hint && <div className="stat-hint">{hint}</div>}
            </div>
        </div>
    )
}

export default function Dashboard() {
    const { hasPermission, profile, roles } = useAuth()
    const canView = hasPermission('dashboard:view')
    const [data, setData] = useState<DashboardData | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        if (!canView) {
            setLoading(false)
            return
        }
        api
            .get<DashboardData>('/admin/dashboard?days=14')
            .then((res) => setData(res.data))
            .catch((err) => setError(errorMessage(err, '加载看板失败')))
            .finally(() => setLoading(false))
    }, [canView])

    if (loading) {
        return (
            <div className="center-screen" style={{ minHeight: 320 }}>
                <Spin size="large" />
            </div>
        )
    }

    if (!canView) {
        return <Result status="403" title="403" subTitle="运营数据需要 dashboard:view 权限点，可先看「我的身份」" />
    }

    if (error) {
        return <Alert type="error" showIcon message={error} />
    }

    if (!data) {
        return null
    }

    const { totals, roleDistribution, trend } = data
    const totalRoles = roleDistribution.reduce((sum, item) => sum + item.count, 0)
    const chartData = trend.map((point) => ({ ...point, label: point.date.slice(5) }))
    const trendSum = trend.reduce((sum, point) => sum + point.registrations, 0)
    const trendPeak = trend.reduce((max, point) => Math.max(max, point.registrations), 0)
    const percent = (part: number, whole: number) =>
        whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : '–'
    const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })

    return (
        <>
            <div className="hero">
                <div style={{ position: 'relative', zIndex: 1 }}>
                    <h2 className="hero-title">欢迎回来，{profile?.nickname || profile?.account}</h2>
                    <p className="hero-desc">
                        {today} · 当前角色 {roles.join(' / ') || '无'}
                    </p>
                </div>
                <div className="hero-meta">
                    <div className="hero-meta-item">
                        <div className="hero-meta-value">{roleDistribution.length}</div>
                        <div className="hero-meta-label">角色种类</div>
                    </div>
                    <div className="hero-meta-item">
                        <div className="hero-meta-value">{totals.weekRegistrations}</div>
                        <div className="hero-meta-label">近 7 天新增</div>
                    </div>
                    <div className="hero-meta-item">
                        <div className="hero-meta-value">{(totals.weekRegistrations / 7).toFixed(1)}</div>
                        <div className="hero-meta-label">日均新增</div>
                    </div>
                </div>
            </div>

            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="用户总数"
                        value={totals.totalUsers}
                        icon={<TeamOutlined />}
                        color="#4f46e5"
                        bg="#eef2ff"
                        hint="注册账号"
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="启用中"
                        value={totals.activeUsers}
                        icon={<CheckCircleOutlined />}
                        color="#12b76a"
                        bg="#ecfdf3"
                        hint={`${percent(totals.activeUsers, totals.totalUsers)} 启用率`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="已禁用"
                        value={totals.disabledUsers}
                        icon={<StopOutlined />}
                        color="#f04438"
                        bg="#fef3f2"
                        hint={`${percent(totals.disabledUsers, totals.totalUsers)} 占比`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="今日新增"
                        value={totals.todayRegistrations}
                        icon={<RiseOutlined />}
                        color="#2e90fa"
                        bg="#eff8ff"
                        hint={`日均 ${(totals.weekRegistrations / 7).toFixed(1)}`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="角色授权"
                        value={totalRoles}
                        icon={<LineChartOutlined />}
                        color="#8b5cf6"
                        bg="#f5f3ff"
                        hint={`${roleDistribution.length} 个角色分布`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="近 30 天活跃"
                        value={totals.monthActiveUsers}
                        icon={<ThunderboltOutlined />}
                        color="#f79009"
                        bg="#fffaeb"
                        hint={`${percent(totals.monthActiveUsers, totals.totalUsers)} 活跃率`}
                    />
                </Col>
            </Row>

            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={16}>
                    <Card
                        className="chart-card"
                        title="近 14 天注册趋势"
                        extra={
                            <span>
                                14 天合计 <b>{trendSum}</b> · 峰值 <b>{trendPeak}</b> 人
                            </span>
                        }
                        style={{ height: '100%' }}
                    >
                        <ResponsiveContainer width="100%" height={252}>
                            <AreaChart data={chartData} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                <defs>
                                    <linearGradient id="regFill" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="0%" stopColor={COLOR.chart[0]} stopOpacity={0.28} />
                                        <stop offset="100%" stopColor={COLOR.chart[0]} stopOpacity={0.02} />
                                    </linearGradient>
                                </defs>
                                <CartesianGrid strokeDasharray="4 4" stroke="#eef0f4" vertical={false} />
                                <XAxis
                                    dataKey="label"
                                    tickLine={false}
                                    axisLine={false}
                                    interval={1}
                                    tick={{ fontSize: 12, fill: '#98a2b3' }}
                                />
                                <YAxis
                                    allowDecimals={false}
                                    tickLine={false}
                                    axisLine={false}
                                    tick={{ fontSize: 12, fill: '#98a2b3' }}
                                />
                                <Tooltip
                                    contentStyle={{
                                        borderRadius: 10,
                                        border: '1px solid #eaecf0',
                                        boxShadow: '0 4px 14px rgba(16,24,40,0.08)',
                                        fontSize: 12
                                    }}
                                    labelStyle={{ color: '#667085' }}
                                    formatter={(value: number) => [`${value} 人`, '注册']}
                                />
                                <Area
                                    type="monotone"
                                    dataKey="registrations"
                                    stroke={COLOR.chart[0]}
                                    strokeWidth={2}
                                    fill="url(#regFill)"
                                    dot={{ r: 3, strokeWidth: 2, stroke: '#fff', fill: COLOR.chart[0] }}
                                    activeDot={{ r: 5 }}
                                />
                            </AreaChart>
                        </ResponsiveContainer>
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                    <Card
                        className="chart-card"
                        title="角色分布"
                        extra={<span>{roleDistribution.length} 个角色</span>}
                        style={{ height: '100%' }}
                    >
                        <div style={{ position: 'relative' }}>
                            <ResponsiveContainer width="100%" height={252}>
                                <PieChart>
                                    <Pie
                                        data={roleDistribution}
                                        dataKey="count"
                                        nameKey="roleCode"
                                        innerRadius={62}
                                        outerRadius={88}
                                        paddingAngle={3}
                                        stroke="#fff"
                                        strokeWidth={2}
                                    >
                                        {roleDistribution.map((entry, index) => (
                                            <Cell key={entry.roleCode} fill={COLOR.chart[index % COLOR.chart.length]} />
                                        ))}
                                    </Pie>
                                    <Tooltip
                                        contentStyle={{
                                            borderRadius: 10,
                                            border: '1px solid #eaecf0',
                                            boxShadow: '0 4px 14px rgba(16,24,40,0.08)',
                                            fontSize: 12
                                        }}
                                        formatter={(value: number, name: string) => [`${value} 个账号`, name]}
                                    />
                                </PieChart>
                            </ResponsiveContainer>
                            <div
                                style={{
                                    position: 'absolute',
                                    inset: 0,
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    pointerEvents: 'none'
                                }}
                            >
                                <div className="pie-center">
                                    <div className="pie-center-value">{totalRoles}</div>
                                    <div className="pie-center-label">角色授权</div>
                                </div>
                            </div>
                        </div>
                        <div className="chart-legend">
                            {roleDistribution.map((entry, index) => (
                                <div className="chart-legend-item" key={entry.roleCode}>
                                    <span
                                        className="chart-legend-dot"
                                        style={{ background: COLOR.chart[index % COLOR.chart.length] }}
                                    />
                                    {entry.roleCode} · {entry.count}
                                </div>
                            ))}
                        </div>
                    </Card>
                </Col>
            </Row>
        </>
    )
}
