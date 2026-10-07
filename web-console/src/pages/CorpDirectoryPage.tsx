import { useEffect, useMemo, useState } from 'react'
import { Alert, Card, Empty, Input, List, Skeleton, Tabs, Tag, Typography } from 'antd'
import { api } from '../api/client'
import { CorpDept, CorpDirectory, CorpProvider } from '../api/types'

/**
 * 企业通讯录（只读面板）：钉钉 / 企业微信两个 provider 并列展示。
 *
 * 数据来自 user-center 实时代理（GET /api/admin/corp/tree）——谁配了凭据谁给真数据，
 * 没配的 provider configured=false，只提示去「集成配置」页接入，不空转请求。
 * 权限口径与「用户管理」一致（user:list）。
 */
export default function CorpDirectoryPage() {
    const [directory, setDirectory] = useState<CorpDirectory | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [keyword, setKeyword] = useState('')

    useEffect(() => {
        let alive = true
        api
            .get<CorpDirectory>('/admin/corp/tree')
            .then((res) => {
                if (alive) setDirectory(res.data)
            })
            .catch((err) => {
                if (alive) setError(err?.response?.data?.message || '企业通讯录拉取失败（检查凭据配置与网络）')
            })
            .finally(() => {
                if (alive) setLoading(false)
            })
        return () => {
            alive = false
        }
    }, [])

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <Card>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
                    <div>
                        <div style={{ fontWeight: 600 }}>企业通讯录（只读）</div>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            新建用户勾选「同步到企业通讯录」即按手机号拉人/建人；这里看到的是两家平台的实时组织
                        </Typography.Text>
                    </div>
                    <Input.Search
                        allowClear
                        placeholder="搜部门 / 成员"
                        style={{ width: 240 }}
                        onChange={(e) => setKeyword(e.target.value)}
                    />
                </div>
                {error && <Alert type="warning" showIcon message={error} style={{ marginTop: 12 }} />}
            </Card>

            <Card loading={loading}>
                {loading ? (
                    <Skeleton active paragraph={{ rows: 6 }} />
                ) : (
                    <Tabs
                        items={(directory?.providers ?? []).map((p) => ({
                            key: p.code,
                            label: p.name,
                            children: <ProviderPane provider={p} keyword={keyword} />
                        }))}
                    />
                )}
            </Card>
        </div>
    )
}

function ProviderPane({ provider, keyword }: { provider: CorpProvider; keyword: string }) {
    // 未配置：直接亮出去哪配，比「暂无数据」可操作得多
    if (!provider.configured) {
        return (
            <Alert
                type="info"
                showIcon
                message={`${provider.name}未接入`}
                description={
                    provider.code === 'dingding'
                        ? '管理员在「集成配置」页填写 AppKey / AppSecret 后即可接入'
                        : '管理员在「集成配置」页填写 CorpID / CorpSecret 后即可接入'
                }
            />
        )
    }

    // 关键词同时命中部门名与成员名，命中的部门整组保留
    const filtered = useMemo(() => {
        const kw = keyword.trim()
        if (!kw) return provider.depts
        const hitDept = (d: CorpDept): CorpDept | null => {
            const children = (d.children ?? []).map(hitDept).filter(Boolean) as CorpDept[]
            const members = (d.members ?? []).filter((m) => m.name.includes(kw) || (m.title || '').includes(kw))
            if (d.name.includes(kw) || members.length > 0 || children.length > 0) {
                return { ...d, members: d.name.includes(kw) && members.length === 0 ? d.members ?? [] : members, children }
            }
            return null
        }
        return provider.depts.map(hitDept).filter(Boolean) as CorpDept[]
    }, [provider.depts, keyword])

    const renderDept = (d: CorpDept, depth = 0): React.ReactNode => (
        <div key={d.id} style={{ marginLeft: depth === 0 ? 0 : 24, marginTop: depth === 0 ? 0 : 12 }}>
            <Typography.Text strong style={{ fontSize: depth === 0 ? 15 : 14 }}>
                {d.name}
            </Typography.Text>
            <Tag style={{ marginInlineStart: 8 }}>{(d.members ?? []).length} 人</Tag>
            {(d.members ?? []).length > 0 && (
                <List
                    size="small"
                    style={{ marginTop: 6, background: '#fafafa', borderRadius: 8, padding: '4px 12px' }}
                    dataSource={d.members}
                    renderItem={(m) => (
                        <List.Item style={{ padding: '4px 0', border: 'none' }}>
                            <span>{m.name}</span>
                            {m.title ? (
                                <Tag color="blue" style={{ marginInlineStart: 8 }}>
                                    {m.title}
                                </Tag>
                            ) : null}
                            <Typography.Text type="secondary" style={{ marginInlineStart: 8, fontSize: 12 }}>
                                {m.userid}
                            </Typography.Text>
                        </List.Item>
                    )}
                />
            )}
            {(d.children ?? []).map((c) => renderDept(c, depth + 1))}
        </div>
    )

    const memberCount = (nodes: CorpDept[]): number =>
        nodes.reduce((sum, d) => sum + (d.members?.length ?? 0) + memberCount(d.children ?? []), 0)

    if (filtered.length === 0) {
        return <Empty description={keyword ? '没有匹配的部门或成员' : '钉钉侧暂无组织数据'} />
    }
    return (
        <>
            <Typography.Text type="secondary">
                共 {filtered.length} 个根部门 · {memberCount(filtered)} 名成员
            </Typography.Text>
            <div style={{ marginTop: 12 }}>{filtered.map((d) => renderDept(d))}</div>
        </>
    )
}
