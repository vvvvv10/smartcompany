import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Popconfirm, Space, Tag, Typography, message } from 'antd'
import { api, errorMessage } from '../api/client'
import { IntegrationProvider, IntegrationTestResult } from '../api/types'

/**
 * 集成配置（role:manage）：钉钉 / 企业微信凭据的可视化维护。
 *
 * 凭据的唯一存放处就是 integration_settings 表——本页是唯一的配置入口，
 * 保存即写库、provider 每次取凭据先查库、token 按凭据指纹失效——热生效，
 * 不用重建容器，也不再读服务器环境变量。
 *
 * 安全口径：明文只进不出——表单落库、回显一律脱敏（AppKey 露头尾 4 位，
 * Secret 只露长度）。「清除凭据」= 删库行，该平台回到未接入状态。
 */
export default function IntegrationsPage() {
    const [providers, setProviders] = useState<IntegrationProvider[]>([])
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    // 表单草稿：code -> { key, secret }；留空字段保存时不动该项
    const [drafts, setDrafts] = useState<Record<string, { key: string; secret: string }>>({})
    const [saving, setSaving] = useState('')
    const [testing, setTesting] = useState('')
    const [resets, setResets] = useState('')
    const [testResult, setTestResult] = useState<Record<string, IntegrationTestResult>>({})

    const load = () => {
        setLoading(true)
        api
            .get<IntegrationProvider[]>('/admin/integrations')
            .then((res) => {
                setProviders(res.data)
                setError('')
            })
            .catch((err) => setError(errorMessage(err, '集成配置拉取失败')))
            .finally(() => setLoading(false))
    }

    useEffect(() => {
        load()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    const patch = (code: string, patchValue: Partial<{ key: string; secret: string }>) =>
        setDrafts((prev) => ({
            ...prev,
            [code]: { key: '', secret: '', ...prev[code], ...patchValue }
        }))

    const apply = (code: string, next: IntegrationProvider) => {
        setProviders((prev) => prev.map((p) => (p.code === code ? next : p)))
        // 配置变了，旧的测试结论作废
        setTestResult((prev) => {
            const next = { ...prev }
            delete next[code]
            return next
        })
        patch(code, { key: '', secret: '' })
    }

    const save = async (code: string) => {
        const draft = drafts[code] ?? { key: '', secret: '' }
        setSaving(code)
        try {
            const res = await api.put<IntegrationProvider>(`/admin/integrations/${code}`, draft)
            apply(code, res.data)
            message.success('已保存，立即生效（无需重建容器）')
        } catch (err) {
            message.error(errorMessage(err, '保存失败'))
        } finally {
            setSaving('')
        }
    }

    const test = async (code: string) => {
        setTesting(code)
        try {
            const res = await api.post<IntegrationTestResult>(`/admin/integrations/${code}/test`)
            setTestResult((prev) => ({ ...prev, [code]: res.data }))
        } catch (err) {
            message.error(errorMessage(err, '测试失败'))
        } finally {
            setTesting('')
        }
    }

    const reset = async (code: string) => {
        setResets(code)
        try {
            const res = await api.delete<IntegrationProvider>(`/admin/integrations/${code}`)
            apply(code, res.data)
            message.success('已清除凭据，该平台回到未接入')
        } catch (err) {
            message.error(errorMessage(err, '清除失败'))
        } finally {
            setResets('')
        }
    }

    const sourceText: Record<string, string> = {
        db: '页面配置（数据库）',
        none: '未配置'
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <Alert
                type="info"
                showIcon
                message="凭据只保存在本页（数据库）——保存即热生效，不用重建容器"
                description="明文只进不出：这里回显的都是脱敏值。改完可点「测试连接」拿当前值换一次 token 验证。权限：role:manage。"
            />
            {error && <Alert type="warning" showIcon message={error} />}
            <Card loading={loading}>
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                    {providers.map((p) => {
                        const draft = drafts[p.code] ?? { key: '', secret: '' }
                        const dirty = draft.key.trim() !== '' || draft.secret.trim() !== ''
                        const result = testResult[p.code]
                        return (
                            <Card
                                key={p.code}
                                type="inner"
                                title={
                                    <Space>
                                        <span style={{ fontWeight: 600 }}>{p.name}</span>
                                        <Tag color={p.configured ? 'green' : 'default'}>
                                            {p.configured ? '已接入' : '未接入'}
                                        </Tag>
                                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                            来源：{sourceText[p.source] ?? p.source}
                                        </Typography.Text>
                                    </Space>
                                }
                                extra={
                                    <Space>
                                        <Button size="small" loading={testing === p.code} onClick={() => void test(p.code)}>
                                            测试连接
                                        </Button>
                                        <Popconfirm
                                            title="清除凭据，该平台回到未接入？"
                                            okText="清除"
                                            cancelText="取消"
                                            onConfirm={() => void reset(p.code)}
                                        >
                                            <Button size="small" danger loading={resets === p.code}>
                                                清除凭据
                                            </Button>
                                        </Popconfirm>
                                    </Space>
                                }
                            >
                                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                        当前 {p.keyLabel}：{p.maskedKey}　·　{p.secretLabel}：{p.maskedSecret}
                                    </Typography.Text>
                                    <Space.Compact style={{ width: '100%' }}>
                                        <Input
                                            placeholder={`修改 ${p.keyLabel}（当前 ${p.maskedKey}，留空不改）`}
                                            value={draft.key}
                                            onChange={(e) => patch(p.code, { key: e.target.value })}
                                        />
                                        <Input.Password
                                            placeholder={`修改 ${p.secretLabel}（当前 ${p.maskedSecret === '未设置' ? '未设置' : '已设置'}，留空不改）`}
                                            value={draft.secret}
                                            onChange={(e) => patch(p.code, { secret: e.target.value })}
                                        />
                                        <Button
                                            type="primary"
                                            loading={saving === p.code}
                                            disabled={!dirty}
                                            onClick={() => void save(p.code)}
                                        >
                                            保存
                                        </Button>
                                    </Space.Compact>
                                    {result && (
                                        <Alert
                                            type={result.ok ? 'success' : 'error'}
                                            showIcon
                                            message={result.message}
                                            style={{ marginTop: 4 }}
                                        />
                                    )}
                                </Space>
                            </Card>
                        )
                    })}
                </Space>
            </Card>
        </div>
    )
}
