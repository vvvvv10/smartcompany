import { UserAddOutlined } from '@ant-design/icons'
import { Badge, Button, Card, Checkbox, Form, Input, Modal, Select, Space, Table, Tag, Tooltip, TreeSelect, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'
import { CreateUserResult, PageResult, RoleRow, TeamRow, TenantRow, UserRow } from '../api/types'
import { useAuth } from '../auth/AuthContext'

const avatarPalette = ['#4f46e5', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6']

/**
 * 新增用户表单——三件客观事实（手机号、姓名、身份证）+ 一件可选归属：
 * 花名与初始密码都由服务端生成，管理员不再临场编。
 *
 * <p>teamId 可空：不选就是不加入任何团队，此人只有角色权限。
 * 选了则服务端建号后顺手入队，不用再跑一趟「团队」页签。</p>
 */
interface CreateUserForm {
    account: string
    realName: string
    idCard: string
    teamId?: number | null
    /** 归属租户；不选 = 示例集团集团（alibaba） */
    tenantId?: string | null
    /** 是否同步到企业通讯录（钉钉/企业微信）；默认勾选，可取消 */
    corpSync?: boolean
}

/** 树形下拉的数据形状（antd TreeSelect 只认 value/title/children 这三个字段） */
interface TeamOption {
    value: number
    title: string
    children?: TeamOption[]
}

/** 平表转树：与权限中心同一套挂法，parent_id 为 null 的是顶层团队 */
function buildTeamOptions(teams: TeamRow[]): TeamOption[] {
    const childrenOf = new Map<number | null, TeamRow[]>()
    for (const team of teams) {
        const key = team.parentId ?? null
        if (!childrenOf.has(key)) childrenOf.set(key, [])
        childrenOf.get(key)!.push(team)
    }
    const toOption = (team: TeamRow): TeamOption => ({
        value: team.id,
        title: team.name,
        children: (childrenOf.get(team.id) ?? []).map(toOption)
    })
    return (childrenOf.get(null) ?? []).map(toOption)
}

function avatarColor(seed: string) {
    const sum = seed.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0)
    return avatarPalette[sum % avatarPalette.length]
}

export default function UsersPage() {
    // 列表是 user:list，写操作是 user:manage —— 与后端两个端点的 require 一一对应，
    // 保证「看得见按钮就点得动」，不会出现点了才吃 403
    const { hasPermission } = useAuth()
    const canManage = hasPermission('user:manage')
    const [rows, setRows] = useState<UserRow[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    const [keyword, setKeyword] = useState('')
    const [loading, setLoading] = useState(false)
    const [roleModalOpen, setRoleModalOpen] = useState(false)
    const [editing, setEditing] = useState<UserRow | null>(null)
    const [roleModalForm] = Form.useForm()
    const [roleOptions, setRoleOptions] = useState<{ value: string; label: string }[]>([])
    const [createOpen, setCreateOpen] = useState(false)
    const [saving, setSaving] = useState(false)
    const [createForm] = Form.useForm<CreateUserForm>()
    // 建号成功后的结果弹窗：花名 + 系统初始密码，密码只在这里出现一次
    const [created, setCreated] = useState<CreateUserResult | null>(null)
    // 团队树只给「所属团队」下拉用。拉不到就静默留空——归属是可选的，
    // 不该因为团队列表没读到就把建号这件事一起挡住
    const [teamOptions, setTeamOptions] = useState<TeamOption[]>([])
    // 租户字典：列表内联切换归属、建号表单下拉共用。给 user:list 就能拉，不需要新权限点
    const [tenantOptions, setTenantOptions] = useState<{ value: string; label: string }[]>([])

    // 角色下拉从权限中心加载（含自定义角色），不再硬编码 USER/ADMIN
    useEffect(() => {
        api
            .get<RoleRow[]>('/admin/role-list')
            .then((res) =>
                setRoleOptions(res.data.map((r) => ({ value: r.code, label: `${r.code} · ${r.name}` })))
            )
            .catch(() => {})
        api
            .get<TeamRow[]>('/admin/teams')
            .then((res) => setTeamOptions(buildTeamOptions(res.data)))
            .catch(() => {})
        // 字典拉不到就不显示租户列的下拉（回退成只读 Tag），不阻塞页面
        api
            .get<TenantRow[]>('/admin/tenants')
            .then((res) =>
                setTenantOptions(res.data.map((t) => ({ value: t.id, label: t.name })))
            )
            .catch(() => {})
    }, [])

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<PageResult<UserRow>>('/admin/users', {
                params: { page, size, keyword: keyword || undefined }
            })
            setRows(res.data.list)
            setTotal(res.data.total)
        } catch (err) {
            message.error(errorMessage(err, '加载用户列表失败'))
        } finally {
            setLoading(false)
        }
    }, [page, size, keyword])

    useEffect(() => {
        load()
    }, [load])

    const toggleStatus = async (row: UserRow) => {
        const next = row.status === 1 ? 0 : 1
        try {
            await api.patch(`/admin/users/${row.id}/status`, { status: next })
            message.success(next === 1 ? `已启用 ${row.nickname || row.account}` : `已禁用 ${row.nickname || row.account}`)
            load()
        } catch (err) {
            message.error(errorMessage(err, '操作失败'))
        }
    }

    const openRoleModal = (row: UserRow) => {
        setEditing(row)
        roleModalForm.setFieldsValue({ roles: row.roles.length ? row.roles : ['USER'] })
        setRoleModalOpen(true)
    }

    // 列表内联改归属。生效时机在服务端注释里写明：已有 access token 的 tid 不热改，
    // 15 分钟内刷新令牌重签才带上新 tid（用户立刻重登则立即生效）
    const changeTenant = async (row: UserRow, tenantId: string) => {
        try {
            const res = await api.put<{ tenantName: string }>(`/admin/users/${row.id}/tenant`, { tenantId })
            message.success(`${row.nickname || row.account} 已归属「${res.data.tenantName}」`)
            load()
        } catch (err) {
            message.error(errorMessage(err, '修改归属失败'))
            load() // 失败回滚显示：重拉一次，把下拉弹回库里真实的值
        }
    }

    const submitRoles = async (values: { roles: string[] }) => {
        if (!editing) {
            return
        }
        try {
            await api.put(`/admin/users/${editing.id}/roles`, { roles: values.roles })
            message.success('角色已更新')
            setRoleModalOpen(false)
            load()
        } catch (err) {
            message.error(errorMessage(err, '更新角色失败'))
        }
    }

    // 清空表单交给 Modal 的 afterClose：在关闭动画播完之后再 reset，
    // 否则字段会在淡出过程中突然闪回初始值
    const closeCreate = () => setCreateOpen(false)

    const submitCreate = async (values: CreateUserForm) => {
        const account = values.account.trim()
        setSaving(true)
        try {
            const res = await api.post<CreateUserResult>('/admin/users', {
                account,
                realName: values.realName.trim(),
                idCard: values.idCard.trim(),
                // 不选 = null。服务端先验团队再建号再入队，这里不做二次兜底
                teamId: values.teamId ?? null,
                // 不选 = null。服务端 requireActive 复验（表单可绕过，校验必须在服务端）
                tenantId: values.tenantId ?? null,
                // 外部副作用（拉人/发邀请）必须显式勾选才发生
                corpSync: values.corpSync ?? false
            })
            closeCreate()
            // 花名与初始密码都在这个响应里，先亮出来再刷新列表——
            // 密码只回这一次，弹窗一关就再也拿不回来，顺序不能反
            setCreated(res.data)
            // 列表按 id 倒序，新号 id 最大；跳回第一页并清掉搜索条件才能立刻看到这一行。
            // 两者本就处于初始值时不改 state（否则 effect 不会重跑），改为直接手动刷新一次。
            const needJump = page !== 1 || keyword !== ''
            setPage(1)
            setKeyword('')
            if (!needJump) {
                load()
            }
        } catch (err) {
            message.error(errorMessage(err, '创建用户失败'))
        } finally {
            setSaving(false)
        }
    }

    // 显式标注类型：不标的话 align: 'center' 会被拓宽成 string，antd 的 AlignType 收不下
    const columns: ColumnsType<UserRow> = [
        {
            title: '用户',
            dataIndex: 'nickname',
            width: 220,
            render: (_: string, row: UserRow) => (
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
                            fontWeight: 500,
                            background: avatarColor(row.account)
                        }}
                    >
                        {(row.nickname || row.account).slice(0, 1)}
                    </div>
                    <div className="user-cell-info">
                        <div className="user-cell-name">{row.nickname || '未设置昵称'}</div>
                        <div className="user-cell-sub">{row.account}</div>
                    </div>
                </div>
            )
        },
        { title: 'ID', dataIndex: 'id', width: 80, align: 'center', render: (id: number) => <span style={{ color: '#98a2b3' }}>#{id}</span> },
        {
            title: '姓名',
            dataIndex: 'realName',
            width: 120,
            render: (value: string) =>
                value ? (
                    <span style={{ color: '#475467' }}>{value}</span>
                ) : (
                    // 注册进来的号没有建档，留白比写「未设置」更不吵
                    <span style={{ color: '#98a2b3' }}>—</span>
                )
        },
        {
            title: '身份证',
            dataIndex: 'idCardMasked',
            width: 175,
            // 脱敏值复制了也没用，这里不放复制图标；完整号码本来就不进接口
            render: (value: string) =>
                value ? (
                    <span style={{ fontFamily: 'monospace', color: '#475467' }}>{value}</span>
                ) : (
                    <span style={{ color: '#98a2b3' }}>—</span>
                )
        },
        {
            title: '租户',
            dataIndex: 'tenantId',
            width: 130,
            align: 'center',
            // 有 user:manage 就是内联下拉直接改归属；没有则只读展示——
            // 与「操作列整列不出现」同一条口径：看不见改得动的入口
            render: (_: string, row: UserRow) =>
                canManage && tenantOptions.length > 0 ? (
                    <Select
                        size="small"
                        value={row.tenantId}
                        style={{ width: 110 }}
                        options={tenantOptions}
                        onChange={(value) => changeTenant(row, value)}
                        onClick={(e) => e.stopPropagation()}
                    />
                ) : (
                    <Tag color="gold">{row.tenantName || row.tenantId}</Tag>
                )
        },
        {
            title: '角色',
            dataIndex: 'roles',
            width: 160,
            align: 'center',
            render: (roles: string[]) =>
                roles.length === 0 ? (
                    <Tag>无</Tag>
                ) : (
                    roles.map((role) => (
                        <Tag key={role} color={role === 'ADMIN' ? 'purple' : 'blue'} style={{ marginInlineEnd: 4 }}>
                            {role}
                        </Tag>
                    ))
                )
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            align: 'center',
            render: (status: number) =>
                status === 1 ? <Badge color="#12b76a" text="正常" /> : <Badge color="#f04438" text="已禁用" />
        },
        {
            title: '最后登录',
            dataIndex: 'lastLoginAt',
            width: 180,
            align: 'center',
            render: (value: string) =>
                value ? <span style={{ color: '#475467' }}>{value}</span> : <span style={{ color: '#98a2b3' }}>从未登录</span>
        },
        // 没有 user:manage 就整列不出现 —— 宁可少一列，也不让人点一个必然 403 的按钮
        ...(canManage
            ? [
                  {
                      title: '操作',
                      width: 160,
                      align: 'center' as const,
                      render: (_: unknown, row: UserRow) => (
                          <Space size={4}>
                              <Button type="link" size="small" onClick={() => openRoleModal(row)}>
                                  分配角色
                              </Button>
                              <Tooltip title={row.status === 1 ? '禁用后该账号将无法登录' : '启用该账号'}>
                                  <Button
                                      type="link"
                                      size="small"
                                      danger={row.status === 1}
                                      onClick={() => toggleStatus(row)}
                                  >
                                      {row.status === 1 ? '禁用' : '启用'}
                                  </Button>
                              </Tooltip>
                          </Space>
                      )
                  }
              ]
            : [])
    ]

    return (
        <>
            <Card
                className="table-card"
                title={`用户列表 · 共 ${total} 个账号`}
                extra={
                    <Space>
                        <Input.Search
                            placeholder="搜索手机号、姓名或花名"
                            allowClear
                            onSearch={(value) => {
                                setPage(1)
                                setKeyword(value)
                            }}
                            style={{ width: 240 }}
                        />
                        <Button onClick={load}>刷新</Button>
                        {canManage && (
                            <Button
                                type="primary"
                                icon={<UserAddOutlined />}
                                onClick={() => setCreateOpen(true)}
                            >
                                新增用户
                            </Button>
                        )}
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
                title={editing ? `分配角色 · ${editing.nickname || editing.account}` : '分配角色'}
                open={roleModalOpen}
                onCancel={() => setRoleModalOpen(false)}
                onOk={() => roleModalForm.submit()}
                okText="保存"
                cancelText="取消"
                width={420}
            >
                <Form form={roleModalForm} layout="vertical" onFinish={submitRoles} style={{ marginTop: 16 }}>
                    <Form.Item
                        name="roles"
                        label="角色"
                        rules={[{ required: true, message: '至少选择一个角色' }]}
                        extra="ADMIN 可访问用户管理与运营数据，USER 仅能查看自己的身份"
                    >
                        <Select mode="multiple" options={roleOptions} placeholder="选择角色" />
                    </Form.Item>
                </Form>
            </Modal>

            <Modal
                title="新增用户"
                open={createOpen}
                onCancel={closeCreate}
                afterClose={() => createForm.resetFields()}
                onOk={() => createForm.submit()}
                okText="创建"
                cancelText="取消"
                confirmLoading={saving}
                width={460}
            >
                <Form
                    form={createForm}
                    layout="vertical"
                    onFinish={submitCreate}
                    style={{ marginTop: 16 }}
                    /* 建号默认归属示例集团集团；通讯录同步默认勾选（可取消）。
                       afterClose 的 resetFields 也会回到这份初始值 */
                    initialValues={{ tenantId: 'alibaba', corpSync: true }}
                >
                    <Form.Item
                        name="account"
                        label="手机号"
                        rules={[
                            { required: true, message: '请输入手机号' },
                            { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的 11 位手机号' }
                        ]}
                        extra="手机号即登录账号"
                    >
                        <Input placeholder="11 位手机号" allowClear autoComplete="off" maxLength={11} />
                    </Form.Item>

                    <Form.Item
                        name="realName"
                        label="真实姓名"
                        rules={[
                            { required: true, whitespace: true, message: '请输入真实姓名' },
                            { max: 64, message: '姓名最长 64 字' }
                        ]}
                        extra="花名按姓名自动生成，重名自动加序号（张三 → 张三2）"
                    >
                        <Input placeholder="如：张三" allowClear autoComplete="off" />
                    </Form.Item>

                    <Form.Item
                        name="idCard"
                        label="身份证号"
                        rules={[
                            { required: true, whitespace: true, message: '请输入身份证号' },
                            { pattern: /^\d{17}[\dXx]$/, message: '身份证号应为 18 位' }
                        ]}
                        extra="仅用于建档留痕，列表与接口只展示脱敏值"
                    >
                        <Input placeholder="18 位身份证号" allowClear autoComplete="off" />
                    </Form.Item>

                    <Form.Item
                        name="teamId"
                        label="所属团队"
                        tooltip="可不选：不加入任何团队时，此人的权限完全由角色决定"
                        extra={
                            teamOptions.length === 0
                                ? '还没有团队，可先到「管理台 → 权限中心」建一个，或暂不选择'
                                : '建号成功即加入该团队；要带队的话建完去「团队」页签指定负责人'
                        }
                    >
                        <TreeSelect
                            allowClear
                            showSearch
                            treeNodeFilterProp="title"
                            treeDefaultExpandAll
                            treeData={teamOptions}
                            placeholder="不加入任何团队（可稍后在团队页签加入）"
                        />
                    </Form.Item>

                    <Form.Item
                        name="tenantId"
                        label="归属租户"
                        tooltip="决定此人的业务数据归哪个租户（按租户行级隔离）"
                        extra="缺省示例集团集团；已有账号要改归属，在列表的租户列直接切换"
                    >
                        <Select
                            allowClear
                            options={tenantOptions}
                            placeholder="示例集团集团（默认）"
                        />
                    </Form.Item>

                    <Form.Item
                        name="corpSync"
                        valuePropName="checked"
                        extra="按手机号同步到已接入的企业通讯录（钉钉/企业微信，配了凭据的才调用）；失败只提示，不影响建号"
                    >
                        <Checkbox>同步到企业通讯录</Checkbox>
                    </Form.Item>

                    <div className="section-note" style={{ marginTop: 0 }}>
                        角色缺省 USER、状态缺省正常，要给管理员用「分配角色」单独加。
                        初始密码由系统生成，创建成功后只显示一次，请当场复制。
                    </div>
                </Form>
            </Modal>

            {/* 建号结果：花名 + 系统初始密码。密码只回这一次，关掉就再也取不回来 */}
            <Modal
                title="创建成功"
                open={created !== null}
                onCancel={() => setCreated(null)}
                footer={
                    <Button type="primary" onClick={() => setCreated(null)}>
                        我已保存
                    </Button>
                }
                width={420}
            >
                {created && (
                    <div style={{ marginTop: 16 }}>
                        <div className="section-note" style={{ marginTop: 0, marginBottom: 14 }}>
                            请当场把下面三项转交给本人。初始密码关闭本窗口后无法再次查看，
                            系统也暂无自助改密入口。
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                            <div>
                                <div style={{ color: '#98a2b3', fontSize: 12, marginBottom: 2 }}>登录手机号</div>
                                <Typography.Text copyable>{created.user.account}</Typography.Text>
                            </div>
                            <div>
                                <div style={{ color: '#98a2b3', fontSize: 12, marginBottom: 2 }}>花名 · 自动生成</div>
                                <Typography.Text copyable>{created.user.nickname}</Typography.Text>
                            </div>
                            <div>
                                <div style={{ color: '#98a2b3', fontSize: 12, marginBottom: 2 }}>初始密码</div>
                                <Typography.Text copyable strong style={{ fontFamily: 'monospace', fontSize: 16 }}>
                                    {created.initialPassword}
                                </Typography.Text>
                            </div>
                            {/* 企业通讯录同步是「勾选才做、失败只提示」：结果紧跟在交接三项之后 */}
                            {created.corpSyncResult && (
                                <div>
                                    <div style={{ color: '#98a2b3', fontSize: 12, marginBottom: 2 }}>企业通讯录同步</div>
                                    <Typography.Text
                                        type={/失败|未配置/.test(created.corpSyncResult) ? 'warning' : 'success'}
                                    >
                                        {created.corpSyncResult}
                                    </Typography.Text>
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </Modal>
        </>
    )
}
