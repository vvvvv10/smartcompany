import {
    ApiOutlined,
    CheckCircleOutlined,
    CloudServerOutlined,
    HddOutlined,
    ReloadOutlined,
    SafetyCertificateOutlined,
    ThunderboltOutlined
} from '@ant-design/icons'
import { Alert, Button, Card, Col, Empty, List, message, Result, Row, Space, Spin, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReactNode, useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { GatewayRoute, GatewayService, GatewayWorkbench } from '../api/types'
import { useAuth } from '../auth/AuthContext'

function StatCard({
    label,
    value,
    icon,
    color,
    bg,
    hint
}: {
    label: string
    value: number | string
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
                <div className="stat-value">{value}</div>
                {hint && <div className="stat-hint">{hint}</div>}
            </div>
        </div>
    )
}

/** 单位换算：字节 → GB，保留一位小数 */
const toGb = (bytes: number | null) => (bytes == null ? null : bytes / 1024 ** 3)

/**
 * 网关工作台：只读地看清楚「哪些请求会被路由到哪、是否有限流、目标服务还活着吗」。
 *
 * 数据全部来自后端聚合接口（Nacos 配置 + 网关 actuator + 注册中心），
 * 前端不做任何二次请求，保证页面只有一个 loading 与一份事实。
 */
export default function GatewayWorkbench() {
    const { profile } = useAuth()
    const [data, setData] = useState<GatewayWorkbench | null>(null)
    const [loading, setLoading] = useState(true)
    const [refreshing, setRefreshing] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const load = useCallback((initial: boolean) => {
        if (initial) {
            setLoading(true)
        } else {
            setRefreshing(true)
        }
        api
            .get<GatewayWorkbench>('/admin/gateway/workbench')
            .then((res) => {
                setData(res.data)
                setError(null)
            })
            .catch((err) => {
                // 首屏失败才整页报错；「刷新」失败保留已渲染的数据，只弹个提示
                if (initial) {
                    setError(errorMessage(err, '加载网关工作台失败'))
                } else {
                    message.error(errorMessage(err, '刷新失败，仍显示上次数据'))
                }
            })
            .finally(() => {
                setLoading(false)
                setRefreshing(false)
            })
    }, [])

    useEffect(() => {
        load(true)
    }, [load])

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

    const { gateway: gw, routes, services, whitelist } = data
    const lbRoutes = routes.filter((route) => route.lb)
    const rateLimited = routes.filter((route) => route.replenishRate != null)
    const healthyServices = services.filter((service) => service.registered)
    const upComponents = gw.components.filter((component) => component.status === 'UP')

    const freeGb = toGb(gw.diskFree)
    const totalGb = toGb(gw.diskTotal)
    const usedPercent =
        gw.diskTotal && gw.diskFree != null
            ? Math.round(((gw.diskTotal - gw.diskFree) / gw.diskTotal) * 100)
            : null
    const updated = new Date(data.generatedAt).toLocaleTimeString('zh-CN', { hour12: false })

    const columns: ColumnsType<GatewayRoute> = [
        {
            title: '路由',
            key: 'route',
            width: 130,
            render: (_: unknown, route: GatewayRoute) => (
                <div>
                    <div style={{ fontSize: 13, fontWeight: 600, color: '#101828' }}>{route.id}</div>
                    <div className="stat-hint">order {route.order}</div>
                </div>
            )
        },
        {
            title: '目标',
            key: 'target',
            width: 165,
            render: (_: unknown, route: GatewayRoute) => (
                <span className="gw-code gw-code-block" title={route.uri || undefined}>
                    {route.uri || '–'}
                </span>
            )
        },
        {
            title: '匹配路径',
            key: 'path',
            render: (_: unknown, route: GatewayRoute) => (
                <span className="gw-code gw-code-block">
                    {(route.path ?? route.predicates.join(' & ')) || '全部'}
                </span>
            )
        },
        {
            title: '限流',
            key: 'rate',
            width: 110,
            render: (_: unknown, route: GatewayRoute) =>
                route.replenishRate != null ? (
                    <div>
                        <div style={{ fontSize: 13, fontWeight: 600, color: '#344054' }}>
                            {route.replenishRate} / {route.burstCapacity ?? '–'}
                        </div>
                        <div className="stat-hint">补充 / 突发</div>
                    </div>
                ) : (
                    <span style={{ color: '#98a2b3' }}>—</span>
                )
        },
        {
            title: '目标状态',
            key: 'status',
            width: 100,
            render: (_: unknown, route: GatewayRoute) =>
                !route.lb ? (
                    <Tag color="blue">直连</Tag>
                ) : route.registered ? (
                    <Tag color="success">{route.instances} 实例</Tag>
                ) : (
                    <Tag color="error">未注册</Tag>
                )
        }
    ]

    return (
        <div className="fade-in">
            <div className="perm-hero">
                <div>
                    <h2 className="perm-hero-title">网关工作台</h2>
                    <p className="perm-hero-desc">
                        {profile?.nickname || profile?.account}，路由与白名单以 Nacos 配置中心为唯一事实来源，运行健康取自网关 actuator · 更新于 {updated}
                    </p>
                </div>
                <div className="perm-hero-meta">
                    <div className="perm-hero-item">
                        <div className="perm-hero-value">{gw.status}</div>
                        <div className="perm-hero-label">网关状态</div>
                    </div>
                    <div className="perm-hero-item">
                        <div className="perm-hero-value">{routes.length}</div>
                        <div className="perm-hero-label">路由规则</div>
                    </div>
                    <div className="perm-hero-item">
                        <div className="perm-hero-value">{services.length}</div>
                        <div className="perm-hero-label">注册服务</div>
                    </div>
                </div>
            </div>

            {!gw.reachable && (
                <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="网关健康信息暂不可达"
                    description="路由与白名单仍来自 Nacos 配置中心、服务列表仍来自注册中心，仅健康组件一栏为空。"
                />
            )}

            <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="路由规则"
                        value={routes.length}
                        icon={<ApiOutlined />}
                        color="#4f46e5"
                        bg="#eef2ff"
                        hint={`${lbRoutes.length} 条走注册中心`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="注册服务"
                        value={services.length}
                        icon={<CloudServerOutlined />}
                        color="#2e90fa"
                        bg="#eff8ff"
                        hint={`${healthyServices.length} 个有健康实例`}
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="限流规则"
                        value={rateLimited.length}
                        icon={<ThunderboltOutlined />}
                        color="#f79009"
                        bg="#fffaeb"
                        hint="按登录主体限流"
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="免鉴权路径"
                        value={whitelist.length}
                        icon={<SafetyCertificateOutlined />}
                        color="#8b5cf6"
                        bg="#f5f3ff"
                        hint="无需令牌直达"
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="磁盘可用"
                        value={freeGb == null ? '–' : `${freeGb.toFixed(1)} GB`}
                        icon={<HddOutlined />}
                        color="#12b76a"
                        bg="#ecfdf3"
                        hint={
                            usedPercent == null
                                ? '健康信息不可达'
                                : `已用 ${usedPercent}% / 共 ${totalGb == null ? '–' : totalGb.toFixed(1)} GB`
                        }
                    />
                </Col>
                <Col xs={12} sm={8} lg={8} xxl={4}>
                    <StatCard
                        label="健康组件"
                        value={`${upComponents.length}/${gw.components.length}`}
                        icon={<CheckCircleOutlined />}
                        color={upComponents.length === gw.components.length ? '#12b76a' : '#f04438'}
                        bg={upComponents.length === gw.components.length ? '#ecfdf3' : '#fef3f2'}
                        hint={gw.redisVersion ? `Redis ${gw.redisVersion}` : '运行组件检查'}
                    />
                </Col>
            </Row>

            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={16}>
                    <Card
                        className="chart-card"
                        title="路由明细"
                        extra={
                            <Space size={12}>
                                <span>{routes.length} 条</span>
                                <Button
                                    size="small"
                                    icon={<ReloadOutlined />}
                                    loading={refreshing}
                                    onClick={() => load(false)}
                                >
                                    刷新
                                </Button>
                            </Space>
                        }
                        styles={{ body: { padding: '4px 0 0' } }}
                    >
                        <Table
                            size="small"
                            rowKey="id"
                            columns={columns}
                            dataSource={routes}
                            pagination={false}
                            scroll={{ x: 760 }}
                        />
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                    <Card
                        className="chart-card"
                        title="服务发现"
                        extra={<span>{services.length} 个服务</span>}
                        style={{ height: '100%' }}
                    >
                        {services.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty
                                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                                    description="注册中心暂时查不到服务"
                                />
                            </div>
                        ) : (
                            <List
                                size="small"
                                dataSource={services}
                                renderItem={(service: GatewayService) => (
                                    <List.Item key={service.name}>
                                        <List.Item.Meta
                                            title={
                                                <Space size={8} wrap>
                                                    <span style={{ fontSize: 13, fontWeight: 500 }}>
                                                        {service.name}
                                                    </span>
                                                    <Tag
                                                        color={service.registered ? 'success' : 'error'}
                                                        style={{ marginInlineEnd: 0 }}
                                                    >
                                                        {service.instances} 个实例
                                                    </Tag>
                                                </Space>
                                            }
                                            description={
                                                <span className="gw-code gw-code-block">
                                                    {service.endpoints.join(', ') || '暂无健康实例'}
                                                </span>
                                            }
                                        />
                                    </List.Item>
                                )}
                            />
                        )}
                    </Card>
                </Col>
            </Row>

            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
                <Col xs={24} lg={14}>
                    <Card
                        className="chart-card"
                        title="网关运行健康"
                        extra={
                            <span>
                                {upComponents.length} / {gw.components.length} 正常
                            </span>
                        }
                        style={{ height: '100%' }}
                    >
                        {gw.components.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty
                                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                                    description="健康信息不可达，稍后点「刷新」重试"
                                />
                            </div>
                        ) : (
                            <div className="gw-health-grid">
                                {gw.components.map((component) => {
                                    const up = component.status === 'UP'
                                    return (
                                        <div className="gw-health-item" key={component.name}>
                                            <span
                                                className="gw-health-dot"
                                                style={{ background: up ? '#12b76a' : '#f04438' }}
                                            />
                                            <span className="gw-health-name" title={component.name}>
                                                {component.name}
                                            </span>
                                            <span
                                                className="gw-health-status"
                                                style={{ color: up ? '#12b76a' : '#f04438' }}
                                            >
                                                {component.status}
                                            </span>
                                        </div>
                                    )
                                })}
                            </div>
                        )}
                        <div className="gw-facts">
                            <div className="gw-fact">
                                <div className="gw-fact-label">网关状态</div>
                                <div
                                    className="gw-fact-value"
                                    style={{ color: gw.reachable ? '#12b76a' : '#f04438' }}
                                >
                                    {gw.status}
                                </div>
                            </div>
                            <div className="gw-fact">
                                <div className="gw-fact-label">磁盘可用</div>
                                <div className="gw-fact-value">
                                    {freeGb == null ? '–' : `${freeGb.toFixed(1)} GB`}
                                </div>
                            </div>
                            <div className="gw-fact">
                                <div className="gw-fact-label">磁盘已用</div>
                                <div className="gw-fact-value">
                                    {usedPercent == null ? '–' : `${usedPercent}%`}
                                </div>
                            </div>
                            <div className="gw-fact">
                                <div className="gw-fact-label">Redis</div>
                                <div className="gw-fact-value">
                                    {gw.redisVersion || gw.redisStatus || '–'}
                                </div>
                            </div>
                        </div>
                    </Card>
                </Col>
                <Col xs={24} lg={10}>
                    <Card
                        className="chart-card"
                        title="免鉴权白名单"
                        extra={<span>{whitelist.length} 条路径</span>}
                        style={{ height: '100%' }}
                    >
                        {whitelist.length === 0 ? (
                            <div style={{ padding: '28px 0', textAlign: 'center' }}>
                                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有配置白名单" />
                            </div>
                        ) : (
                            <>
                                <div>
                                    {whitelist.map((path) => (
                                        <span className="gw-code" key={path} style={{ margin: '0 6px 6px 0' }}>
                                            {path}
                                        </span>
                                    ))}
                                </div>
                                <div className="section-note">
                                    这些路径由网关 AuthGlobalFilter 直接放行（登录、注册、验证码与健康检查）；其余 <b>/api/**</b> 都必须带 Bearer 令牌，内部身份头也只在网关注入。
                                </div>
                            </>
                        )}
                    </Card>
                </Col>
            </Row>
        </div>
    )
}
