import { Alert, Button, Card, Descriptions, Divider, Form, Input, Modal, Popconfirm, Space, Tag, Tooltip, Typography, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'
import { ApiErrorBody, JoinResult, NicknameRequestRow, OrganizationMembership } from '../api/types'
import { useAuth } from '../auth/AuthContext'

const CHANNEL_LABEL: Record<string, string> = {
    DINGTALK: '企业钉钉',
    WECOM: '企业微信'
}

export default function ProfilePage() {
    const { profile, roles, hasPermission, reload } = useAuth()
    const [nicknameModal, setNicknameModal] = useState(false)
    const [nicknameSaving, setNicknameSaving] = useState(false)
    const [nicknameForm] = Form.useForm<{ nickname: string }>()
    // 花名变更要审批，这里挂的是「我的最新一条申请」，用来显示待审批/已驳回状态
    const [myRequest, setMyRequest] = useState<NicknameRequestRow | null>(null)

    const [memberships, setMemberships] = useState<OrganizationMembership[]>([])
    const [joining, setJoining] = useState<string | null>(null)
    const [loadingMemberships, setLoadingMemberships] = useState(false)

    const loadMemberships = useCallback(async () => {
        setLoadingMemberships(true)
        try {
            const res = await api.get<{ memberships: OrganizationMembership[] }>('/users/me/organizations')
            setMemberships(res.data.memberships)
        } catch {
            // 组织绑定加载失败不阻断身份展示，静默即可
        } finally {
            setLoadingMemberships(false)
        }
    }, [])

    useEffect(() => {
        loadMemberships()
    }, [loadMemberships])

    /**
     * 拉我的最新申请。后端没申请时回 {code:'not_found'}，靠有没有 id 区分——
     * 这是「无记录」与「请求失败」的分界，失败静默即可（拿不到状态不影响改名入口）。
     */
    const loadMyRequest = useCallback(async () => {
        try {
            const res = await api.get('/users/me/nickname/request')
            const data = res.data as Partial<NicknameRequestRow> & Partial<ApiErrorBody>
            setMyRequest(data.id ? (data as NicknameRequestRow) : null)
        } catch {
            setMyRequest(null)
        }
    }, [])

    // 花名可能在别处被改（管理员批准了申请），申请状态也要跟着刷新——
    // 否则会出现「新花名已生效 + 还挂着待审批标签」这种自相矛盾的组合。
    // profile 还没加载完时 nickname 是 undefined，加载完会再触发一次，多一次轻查询而已。
    useEffect(() => {
        loadMyRequest()
    }, [loadMyRequest, profile?.nickname])

    // 花名可能在别处被改（管理员批准了申请、管理员在后台建号改号），
    // 每次进这个页面重取一次 /users/me —— 否则 SPA 内跳转回来仍显示内存里的旧花名，
    // 看起来就像审批没生效。reload 只在成功时 setProfile，不碰 loading，不会闪。
    useEffect(() => {
        reload()
    }, [reload])

    if (!profile) {
        return null
    }

    const initial = (profile.nickname || profile.account).slice(0, 1)

    /**
     * 提交花名变更申请。不直接改名——审批通过前 users.nickname 不动，
     * 身份对外仍是当前花名。
     */
    const submitNickname = async (values: { nickname: string }) => {
        setNicknameSaving(true)
        try {
            await api.post('/users/me/nickname/requests', { nickname: values.nickname.trim() })
            message.success('申请已提交，管理员批准后生效')
            setNicknameModal(false)
            await loadMyRequest()
        } catch (err) {
            message.error(errorMessage(err, '提交申请失败'))
        } finally {
            setNicknameSaving(false)
        }
    }

    const join = async (channel: string) => {
        setJoining(channel)
        try {
            const res = await api.post<JoinResult>(`/users/me/organizations/${channel}`)
            if (res.data.status === 'JOINED') {
                message.success(`已${res.data.mock ? '（mock）' : ''}加入${CHANNEL_LABEL[channel]}`)
            } else {
                message.error(`加入失败：${res.data.detail}`)
            }
            loadMemberships()
        } catch (err) {
            message.error(errorMessage(err, '加入企业组织失败'))
        } finally {
            setJoining(null)
        }
    }

    const leave = async (channel: string) => {
        try {
            await api.delete(`/users/me/organizations/${channel}`)
            message.success(`已解除${CHANNEL_LABEL[channel]}绑定`)
            loadMemberships()
        } catch (err) {
            message.error(errorMessage(err, '解除绑定失败'))
        }
    }

    const boundChannels = new Set(memberships.map((item) => item.channel))
    const canEditProfile = hasPermission('nickname:request')
    const pending = myRequest?.status === 'PENDING'
    const rejected = myRequest?.status === 'REJECTED'

    return (
        <>
            <Card className="table-card" styles={{ body: { padding: 0 } }}>
                <div style={{ padding: '24px 24px 20px', display: 'flex', alignItems: 'center', gap: 16 }}>
                    <div
                        style={{
                            width: 60,
                            height: 60,
                            borderRadius: 16,
                            flex: 'none',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: '#fff',
                            fontSize: 22,
                            background: 'linear-gradient(135deg, #4f46e5 0%, #7c3aed 100%)',
                            boxShadow: '0 4px 12px rgba(79,70,229,0.28)'
                        }}
                    >
                        {initial}
                    </div>
                    <div style={{ flex: 1 }}>
                        <div style={{ fontSize: 18, fontWeight: 500, color: '#101828' }}>
                            {profile.nickname || '未设置花名'}
                            {canEditProfile && (
                                <Button
                                    type="link"
                                    size="small"
                                    disabled={pending}
                                    onClick={() => {
                                        // 不预填当前花名：这是「申请新花名」而不是「编辑当前值」，
                                        // 预填旧值只会让用户忘了改就提交，然后吃一个 same_nickname 409。
                                        // 当前花名在弹窗正文里已经写明了。
                                        nicknameForm.resetFields()
                                        setNicknameModal(true)
                                    }}
                                >
                                    {pending ? '花名审批中…' : '申请改花名'}
                                </Button>
                            )}
                            {/* 审批进度贴着花名显示：这是唯一会被这次申请改动的身份字段 */}
                            {pending && (
                                <Tag color="orange" style={{ marginLeft: 4 }}>
                                    待审批 → {myRequest!.newNickname}
                                </Tag>
                            )}
                            {rejected && (
                                <Tooltip title={myRequest!.reviewNote || '未填写理由'}>
                                    <Tag color="red" style={{ marginLeft: 4, cursor: 'help' }}>
                                        已驳回：{myRequest!.newNickname}
                                    </Tag>
                                </Tooltip>
                            )}
                        </div>
                        <div style={{ fontSize: 13, color: '#667085', marginTop: 2 }}>{profile.account}</div>
                        <div style={{ marginTop: 8 }}>
                            {roles.map((role) => (
                                <Tag key={role} color={role === 'ADMIN' ? 'purple' : 'blue'}>
                                    {role}
                                </Tag>
                            ))}
                        </div>
                    </div>
                </div>
                <Divider style={{ margin: 0 }} />
                <div style={{ padding: '16px 24px 22px' }}>
                    <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small" colon={false}>
                        <Descriptions.Item label="工号">
                            <Typography.Text strong>{profile.id}</Typography.Text>
                        </Descriptions.Item>
                        <Descriptions.Item label="公司">
                            {/* 38 号迁移后存量全是 alibaba；default 是迁移前的遗留值，兑底显示「未知」 */}
                            {profile.dbTenantId === 'default'
                                ? '未知'
                                : profile.tenantName || profile.dbTenantId}
                        </Descriptions.Item>
                    </Descriptions>
                </div>
            </Card>

            {/* 企业组织：注册后可选加入钉钉/企微 */}
            <Card
                title="企业组织"
                className="table-card"
                style={{ marginTop: 16 }}
                loading={loadingMemberships}
                extra={
                    <Space>
                        {(['DINGTALK', 'WECOM'] as const)
                            .filter((channel) => !boundChannels.has(channel))
                            .map((channel) => (
                                <Button
                                    key={channel}
                                    size="small"
                                    loading={joining === channel}
                                    onClick={() => join(channel)}
                                >
                                    加入{CHANNEL_LABEL[channel]}
                                </Button>
                            ))}
                    </Space>
                }
            >
                {memberships.length === 0 ? (
                    <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                        还没有绑定任何企业组织。注册时可选加入，也可以随时在这里补上 ——
                        加入后你的花名会同步到企业通讯录。
                    </Typography.Text>
                ) : (
                    <Space direction="vertical" style={{ width: '100%' }} size={10}>
                        {memberships.map((item) => (
                            <div
                                key={item.channel}
                                style={{
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: 12,
                                    padding: '10px 14px',
                                    borderRadius: 10,
                                    background: '#f9fafb',
                                    border: '1px solid #eaecf0'
                                }}
                            >
                                <Tag color={item.channel === 'DINGTALK' ? 'blue' : 'green'}>
                                    {CHANNEL_LABEL[item.channel] || item.channel}
                                </Tag>
                                <div style={{ flex: 1, minWidth: 0 }}>
                                    <div style={{ fontSize: 13, color: '#344054' }}>
                                        成员 ID：{item.externalId || '-'}
                                        <Tag
                                            style={{ marginInlineStart: 8 }}
                                            color={item.status === 'JOINED' ? 'success' : 'error'}
                                        >
                                            {item.status === 'JOINED' ? '已加入' : '失败'}
                                        </Tag>
                                    </div>
                                    <div style={{ fontSize: 12, color: '#98a2b3' }}>{item.detail}</div>
                                </div>
                                <Popconfirm
                                    title="解除该企业组织绑定？"
                                    okText="解除"
                                    okButtonProps={{ danger: true }}
                                    cancelText="取消"
                                    onConfirm={() => leave(item.channel)}
                                >
                                    <Button size="small" danger>
                                        解除绑定
                                    </Button>
                                </Popconfirm>
                            </div>
                        ))}
                    </Space>
                )}
            </Card>

            <Card title="这一页在验证什么" style={{ marginTop: 16 }}>
                <Alert
                    type="info"
                    showIcon
                    message="页面上的身份全部来自网关注入的请求头，不是你自己传的参数"
                    description={
                        <Typography.Paragraph style={{ marginBottom: 0 }}>
                            <div>
                                网关处理请求时，会先把客户端可能伪造的 <code>X-User-Id</code>、
                                <code>X-User-Roles</code>、<code>X-Tenant-Id</code> 全部删掉，再用校验过的 JWT
                                重新写入。业务服务只信这个头，所以即使带上伪造头调用，返回的仍是真实身份。
                            </div>
                            <Divider style={{ margin: '12px 0' }} />
                            <Typography.Text code style={{ fontSize: 12 }}>
                                {`curl -H "Authorization: Bearer <你的accessToken>" -H "X-User-Id: 999" -H "X-User-Roles: ADMIN" http://localhost:8080/api/users/me`}
                            </Typography.Text>
                            <div style={{ marginTop: 8 }}>
                                返回的 id 仍是 <Typography.Text strong>#{profile.id}</Typography.Text>，不会变成 999。
                            </div>
                        </Typography.Paragraph>
                    }
                />
            </Card>

            {/* 申请改花名：提交的不是"修改"而是"申请"，审批通过前身份不变 */}
            <Modal
                title="申请花名变更"
                open={nicknameModal}
                onCancel={() => setNicknameModal(false)}
                onOk={() => nicknameForm.submit()}
                okText="提交申请"
                cancelText="取消"
                confirmLoading={nicknameSaving}
                width={440}
                destroyOnClose
            >
                <Alert
                    type="info"
                    showIcon
                    style={{ marginTop: 16 }}
                    message="花名是全局唯一身份标识，变更需管理员在「审批中心」批准"
                    description={`审批通过前，对外显示的花名仍是「${profile.nickname}」。`}
                />
                <Form
                    form={nicknameForm}
                    layout="vertical"
                    onFinish={submitNickname}
                    style={{ marginTop: 16 }}
                >
                    <Form.Item
                        name="nickname"
                        label="新花名"
                        rules={[
                            { required: true, message: '花名不能为空' },
                            { min: 2, max: 32, message: '花名长度 2-32 字' }
                        ]}
                        extra="花名全局唯一；占用情况会在提交时校验，但批准时可能已被他人抢先注册"
                    >
                        <Input maxLength={32} showCount placeholder="如：风清扬" />
                    </Form.Item>
                </Form>
            </Modal>
        </>
    )
}
