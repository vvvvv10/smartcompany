import { Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Spin, Table, Tag, Tree, Typography, message } from 'antd'
import {
    ApartmentOutlined,
    AppstoreOutlined,
    DeleteOutlined,
    CrownOutlined,
    PlusOutlined,
    TeamOutlined,
    UsergroupAddOutlined
} from '@ant-design/icons'
import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import type { PageResult, PermissionRow, TeamMemberRow, TeamRow, UserRow } from '../api/types'
import { api, errorMessage } from '../api/client'
import { PermissionGroups } from './permission-shared'

/** 组织架构树节点。key 约定：`root` / `t:{团队id}` / `u:{用户id}` */
interface OrgNode {
    key: string
    title: ReactNode
    children?: OrgNode[]
}

/**
 * 组织架构：左树右面板，把「谁归谁管」与「这一块到底有多少权限」合成一张图看。
 *
 * <p><b>默认纯呈现</b>——不传 {@code editable} 就没有任何写操作，「我的团队」页正是这种
 * 用法（负责人只能看自己的子树，连建团队的按钮都不该出现）。权限中心传
 * {@code editable} 后，选中团队节点会在右侧面板给出「新增下级」「删除」，
 * 选中虚拟根则给「新建顶层团队」：操作跟着选中的节点走，不在树行上悬浮
 * + 号——那很容易被误当成可以拖拽换上级，而「把 A 挂到 A 的下级下面」是成环的。</p>
 *
 * <p>树上放<b>「批量拉人」</b>（选中团队的面板内，与「新增下级」并排）：批量入口跟着
 * 选中的节点走，调的是后端幂等的加人接口（重复加=静默忽略），成功后回调
 * {@code onChanged} 刷新整棵树。移人、设负责人与调上下级仍在「团队」页签的抽屉/
 * 弹窗里做——调上下级有成环与整棵子树的禁用校验，不在树上重复实现一套。</p>
 *
 * <p>口径与后端完全一致（前端不重算任何权限）：</p>
 * <ul>
 *   <li>团队节点的权限数 = <b>整棵子树</b>在职成员自身权限的并集；</li>
 *   <li>成员节点的权限数 = 自身角色权限 ∪ 其所带队节点的子树并集；</li>
 *   <li>虚拟根 = 传入的各根团队的并集（不是全站权限）。</li>
 * </ul>
 *
 * <p>没有加入任何团队的成员不会出现在树上——他们的权限由角色决定，
 * 在「授权矩阵」那边看。</p>
 *
 * <p>数据由调用方传入：传进来的范围就是看得见的范围，组件不做任何越界补全。
 * 权限中心传全量，「我的团队」只传自己的子树——<b>过滤在服务端完成</b>。
 * 写操作成功后回调 {@code onChanged}，由调用方重新取数（组件不自己拉全量，
 * 否则两处的数据来源会出现两套时序）。</p>
 */
export function OrgExplorer({
    teams,
    loading,
    permRows,
    note,
    rootLabel = '全公司',
    rootHint,
    editable = false,
    onChanged
}: {
    teams: TeamRow[]
    loading: boolean
    permRows: PermissionRow[]
    /**
     * 顶部说明。不传则用权限中心那一句；负责人视图传自己的边界说明——
     * 两处共用同一棵树，但「你看到的范围到哪儿」必须由各自的调用方讲清楚。
     */
    note?: ReactNode
    /** 虚拟根节点的叫法。负责人视图里它不是「全公司」，是「我的团队」 */
    rootLabel?: string
    /** 根节点面板里的补充说明 */
    rootHint?: ReactNode
    /** 打开写操作（新建顶层/下级团队、删除团队）。不传则整棵树只读 */
    editable?: boolean
    /** 写操作成功后触发，由调用方重新取数 */
    onChanged?: () => void
}) {
    const [selected, setSelected] = useState<string>('root')

    // 新建团队：parent 取自当前选中节点（null = 挂顶层）。存 parentName 是为了让弹窗
    // 能直接把「上级是谁」写死给人看——这里不给改上级的下拉，改上级在「团队」页签做
    const [createForm] = Form.useForm<{ name: string; description: string }>()
    const [createFor, setCreateFor] = useState<{ parentId: number | null; parentName: string } | null>(null)
    const [creating, setCreating] = useState(false)
    // 批量拉人：目标团队存快照（只用 id/name，成员过滤走下面的派生值，刷新不脱节）；
    // 用户池懒加载——打开弹窗才拉，树是只读展示时（我的团队）根本没有这入口
    const [addFor, setAddFor] = useState<TeamRow | null>(null)
    const [pool, setPool] = useState<UserRow[] | null>(null)
    const [picked, setPicked] = useState<number[]>([])
    const [adding, setAdding] = useState(false)
    const [expanded, setExpanded] = useState<string[]>(['root'])

    // 数据由调用方给：权限中心拉 /admin/teams（全量），我的团队拉 /users/me/teams（只给自己的子树）。
    // 这里只管呈现，所以同一棵树在两处的节点口径完全一致。
    useEffect(() => {
        setExpanded(['root', ...teams.map((t) => `t:${t.id}`)])
    }, [teams])
    // 成员可能同时挂在好几个团队下，这里按 userId 去重，避免「20 个成员其实是 8 个人」
    // 这种口径对不上的账
    const stats = useMemo(() => {
        const memberIds = new Set<number>()
        for (const team of teams) {
            for (const m of team.members) memberIds.add(m.userId)
        }
        return { teamCount: teams.length, memberCount: memberIds.size }
    }, [teams])

    /** 虚拟根 = 传入的各根团队子树并集，按权限点首次出现的顺序去重 */
    const rootPermissions = useMemo(() => {
        const seen = new Set<string>()
        const out: string[] = []
        for (const team of teams) {
            if (team.parentId !== null) continue
            for (const code of team.effectivePermissions) {
                if (!seen.has(code)) {
                    seen.add(code)
                    out.push(code)
                }
            }
        }
        return out
    }, [teams])

    const treeData: OrgNode[] = useMemo(() => {
        const build = (parentId: number | null): OrgNode[] =>
            teams
                .filter((t) => t.parentId === parentId)
                .map((t) => ({
                    key: `t:${t.id}`,
                    title: (
                        <span>
                            <TeamOutlined style={{ color: '#6366f1', marginRight: 6 }} />
                            {t.name}
                            <Tag color="green" style={{ marginLeft: 8 }}>
                                {t.effectivePermissions.length}
                            </Tag>
                        </span>
                    ),
                    children: [
                        ...build(t.id),
                        ...t.members.map((m) => ({
                            key: `u:${m.userId}`,
                            title: (
                                <span>
                                    {m.lead && (
                                        <CrownOutlined style={{ color: '#f59e0b', marginRight: 6 }} />
                                    )}
                                    {m.nickname}
                                    <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
                                        {m.account}
                                    </Typography.Text>
                                    <Tag style={{ marginLeft: 8 }}>
                                        {m.effectivePermissions.length}
                                    </Tag>
                                </span>
                            )
                        }))
                    ]
                }))
        return [
            {
                key: 'root',
                title: (
                    <span>
                        <AppstoreOutlined style={{ color: '#10b981', marginRight: 6 }} />
                        {rootLabel}
                        <Tag color="green" style={{ marginLeft: 8 }}>
                            {rootPermissions.length}
                        </Tag>
                    </span>
                ),
                children: build(null)
            }
        ]
    }, [teams, rootPermissions])

    const isRoot = selected === 'root'
    const teamKey = selected.startsWith('t:') ? Number(selected.slice(2)) : null
    const userKey = selected.startsWith('u:') ? Number(selected.slice(2)) : null
    const team = teamKey === null ? null : teams.find((t) => t.id === teamKey) ?? null
    const member =
        userKey === null
            ? null
            : teams.flatMap((t) => t.members).find((m) => m.userId === userKey) ?? null

    const parentName = team?.parentId == null
        ? null
        : teams.find((t) => t.id === team.parentId)?.name ?? null
    const childTeams = team ? teams.filter((t) => t.parentId === team.id) : []
    const memberTeams =
        member === null ? [] : teams.filter((t) => t.members.some((m) => m.userId === member.userId))
    /** 继承来的 = 有效权限里去掉自身角色带来的那部分 */
    const inherited =
        member === null
            ? []
            : member.effectivePermissions.filter((c) => !member.ownPermissions.includes(c))
    // 批量弹窗的队伍：onChanged 刷新后 addFor 是旧快照，优先取刷新后的最新 props
    const addTeam = addFor === null ? null : teams.find((t) => t.id === addFor.id) ?? addFor

    const submitCreate = async (values: { name: string; description: string }) => {
        setCreating(true)
        try {
            await api.post('/admin/teams', {
                name: values.name,
                description: values.description,
                parentId: createFor?.parentId ?? null
            })
            message.success('团队已创建，选中它即可批量拉人')
            setCreateFor(null)
            onChanged?.()
        } catch (err) {
            // 重名是后端已有的人话文案，直接透出
            message.error(errorMessage(err, '创建团队失败'))
        } finally {
            setCreating(false)
        }
    }

    const removeTeam = async (team: TeamRow) => {
        try {
            await api.delete(`/admin/teams/${team.id}`)
            message.success(`团队「${team.name}」已删除`)
            // 删的正是当前选中节点：key 指向已不存在的团队，右侧面板会落到
            // 「选中节点不存在」的兜底文案，不如直接退回根
            if (selected === `t:${team.id}`) setSelected('root')
            onChanged?.()
        } catch (err) {
            // 「还有 N 个子团队」这类拒绝由后端给出，原文透出
            message.error(errorMessage(err, '删除团队失败'))
        }
    }

    const openBatchAdd = (team: TeamRow) => {
        setAddFor(team)
        setPicked([])
        // 用户池只拉一次（size 500 覆盖演示规模；人多了再改成分页搜索）
        if (pool === null) {
            api.get<PageResult<UserRow>>('/admin/users', { params: { page: 1, size: 500 } })
                .then((r) => setPool(r.data.list))
                .catch((err) => {
                    message.error(errorMessage(err, '加载用户列表失败'))
                    setAddFor(null)
                })
        }
    }

    // 逐个提交而非批量端点：后端是 insert ... on duplicate key 的幂等写法，
    // 已在队里的会静默带过；个别账号禁用/不存在时 best-effort 跳过并汇总原因，
    // 不让一条坏数据堵住整批。全部失败才关不掉弹窗，让人改完再试
    const submitBatchAdd = async () => {
        if (!addFor || picked.length === 0) return
        setAdding(true)
        const fails: string[] = []
        let ok = 0
        for (const userId of picked) {
            try {
                await api.post(`/admin/teams/${addFor.id}/members`, { userId, lead: false })
                ok += 1
            } catch (err) {
                const u = pool?.find((x) => x.id === userId)
                fails.push(`${u ? `${u.nickname}（${u.account}）` : `#${userId}`}：${errorMessage(err, '失败')}`)
            }
        }
        setAdding(false)
        if (ok === 0) {
            message.error(`一个都没加上：${fails.join('；')}`)
            return
        }
        if (fails.length === 0) {
            message.success(`已把 ${ok} 人加入「${addFor.name}」`)
        } else {
            message.warning(`加入 ${ok} 人，${fails.length} 人失败：${fails.join('；')}`)
        }
        setAddFor(null)
        setPicked([])
        onChanged?.()
    }

    return (
        <>
            <Typography.Paragraph type="secondary">
                {note ?? (
                    <>
                        左边是组织架构，节点上的数字是权限点数量；点任意节点看明细。
                        团队权限 = <b>整棵子树</b>在职成员的并集，负责人自动拥有它——
                        所以父团队的数字天然大于等于子团队。
                        {editable
                            ? '选中团队可就地新建下级、批量拉人或删除它；移人、设负责人与调上下级在「团队」页签。'
                            : '建团队与调上下级在「团队」页签。'}
                    </>
                )}
            </Typography.Paragraph>

            <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
                <Card
                    title={<span><ApartmentOutlined /> 组织架构</span>}
                    size="small"
                    style={{ width: 340, flexShrink: 0 }}
                    styles={{ body: { padding: 12, maxHeight: 640, overflow: 'auto' } }}
                >
                    <Spin spinning={loading}>
                        <Tree
                            treeData={treeData}
                            selectedKeys={[selected]}
                            expandedKeys={expanded}
                            onExpand={(keys) => setExpanded(keys as string[])}
                            onSelect={(keys) => {
                                // antd 点「已经选中」的节点会把 keys 清空（反选）。这里不跟着清：
                                // 面板突然跳回「全公司」会让人以为节点被删了。要回根就点虚拟根。
                                if (keys.length > 0) setSelected(String(keys[0]))
                            }}
                            showLine={{ showLeafIcon: false }}
                        />
                    </Spin>
                </Card>

                <Card
                    size="small"
                    style={{ flex: 1, minWidth: 0 }}
                    title={
                        isRoot
                            ? rootLabel
                            : team
                              ? `「${team.name}」`
                              : member
                                ? member.nickname
                                : '明细'
                    }
                >
                    {isRoot && (
                        <>
                            {editable && (
                                <Space style={{ marginBottom: 12 }}>
                                    <Button
                                        type="primary"
                                        icon={<PlusOutlined />}
                                        onClick={() =>
                                            setCreateFor({ parentId: null, parentName: `${rootLabel}（最顶层）` })
                                        }
                                    >
                                        新建顶层团队
                                    </Button>
                                </Space>
                            )}
                            <Typography.Paragraph type="secondary">
                                {rootHint ??
                                    '未加入任何团队的成员不在这棵树上——他们的权限由角色决定。'}
                            </Typography.Paragraph>
                            <Space size={16} style={{ marginBottom: 16 }}>
                                <Tag color="blue">{stats.teamCount} 个团队</Tag>
                                <Tag color="blue">{stats.memberCount} 名成员</Tag>
                                <Tag color="green">{rootPermissions.length} 项权限</Tag>
                            </Space>
                            <Typography.Title level={5}>{rootLabel}的有效权限</Typography.Title>
                            <PermissionGroups codes={rootPermissions} permRows={permRows} />
                        </>
                    )}

                    {team && (
                        <>
                            <Space size={8} style={{ marginBottom: 12, flexWrap: 'wrap' }}>
                                <Tag>#{team.id}</Tag>
                                <Tag>上级：{parentName ?? '（最顶层）'}</Tag>
                                <Tag>直属成员 {team.members.length} 人</Tag>
                                <Tag>下级团队 {childTeams.length} 个</Tag>
                                {team.members.some((m) => m.lead) && (
                                    <Tag color="gold">
                                        负责人：{team.members.find((m) => m.lead)?.nickname}
                                    </Tag>
                                )}
                            </Space>
                            {editable && (
                                <Space size={8} style={{ marginBottom: 12 }}>
                                    <Button
                                        type="primary"
                                        icon={<PlusOutlined />}
                                        onClick={() =>
                                            setCreateFor({ parentId: team.id, parentName: team.name })
                                        }
                                    >
                                        新增下级团队
                                    </Button>
                                    <Button
                                        icon={<UsergroupAddOutlined />}
                                        onClick={() => openBatchAdd(team)}
                                    >
                                        批量拉人
                                    </Button>
                                    <Popconfirm
                                        title="删除这个团队？"
                                        description="只解除成员关系，不影响任何人的角色与权限；还有子团队时会被拒绝。"
                                        okText="删除"
                                        cancelText="取消"
                                        onConfirm={() => removeTeam(team)}
                                    >
                                        <Button danger icon={<DeleteOutlined />}>
                                            删除
                                        </Button>
                                    </Popconfirm>
                                </Space>
                            )}
                            {team.description && (
                                <Typography.Paragraph type="secondary">
                                    {team.description}
                                </Typography.Paragraph>
                            )}

                            <Typography.Title level={5}>
                                有效权限（{team.effectivePermissions.length}）
                            </Typography.Title>
                            <Typography.Paragraph type="secondary">
                                含下级团队在职成员的权限并集；本队负责人自动拥有这一份。
                            </Typography.Paragraph>
                            <PermissionGroups
                                codes={team.effectivePermissions}
                                permRows={permRows}
                                emptyText="这棵子树的成员目前没有任何权限，去「授权矩阵」给他们的角色勾上。"
                            />

                            {childTeams.length > 0 && (
                                <>
                                    <Typography.Title level={5}>下级团队</Typography.Title>
                                    <Space size={8} wrap style={{ marginBottom: 16 }}>
                                        {childTeams.map((c) => (
                                            <Tag
                                                key={c.id}
                                                style={{ cursor: 'pointer' }}
                                                onClick={() => setSelected(`t:${c.id}`)}
                                            >
                                                {c.name} · {c.effectivePermissions.length} 项
                                            </Tag>
                                        ))}
                                    </Space>
                                </>
                            )}

                            <Typography.Title level={5}>直属成员</Typography.Title>
                            <Table
                                rowKey="userId"
                                size="small"
                                pagination={false}
                                dataSource={team.members}
                                columns={[
                                    {
                                        title: '成员',
                                        key: 'member',
                                        render: (_: unknown, m: TeamMemberRow) => (
                                            <Space size={5}>
                                                {m.lead && (
                                                    <CrownOutlined style={{ color: '#f59e0b' }} />
                                                )}
                                                <Typography.Text strong>{m.nickname}</Typography.Text>
                                                <Typography.Text type="secondary">{m.account}</Typography.Text>
                                                {m.status !== 1 && <Tag color="red">已禁用</Tag>}
                                            </Space>
                                        )
                                    },
                                    {
                                        title: '角色',
                                        key: 'roles',
                                        width: 150,
                                        render: (_: unknown, m: TeamMemberRow) =>
                                            m.roles.length === 0 ? (
                                                <Typography.Text type="secondary">未挂角色</Typography.Text>
                                            ) : (
                                                m.roles.map((r) => (
                                                    <Tag
                                                        key={r}
                                                        color={r === 'ADMIN' ? 'purple' : 'blue'}
                                                        style={{ marginBottom: 4 }}
                                                    >
                                                        {r}
                                                    </Tag>
                                                ))
                                            )
                                    },
                                    {
                                        title: '自身权限',
                                        key: 'own',
                                        width: 100,
                                        render: (_: unknown, m: TeamMemberRow) => `${m.ownPermissions.length} 项`
                                    },
                                    {
                                        title: '有效权限',
                                        key: 'eff',
                                        width: 100,
                                        render: (_: unknown, m: TeamMemberRow) => (
                                            <Tag color="green">{m.effectivePermissions.length} 项</Tag>
                                        )
                                    }
                                ]}
                                onRow={(m) => ({
                                    onClick: () => setSelected(`u:${m.userId}`),
                                    style: { cursor: 'pointer' }
                                })}
                            />
                        </>
                    )}

                    {member && (
                        <>
                            <Space size={8} style={{ marginBottom: 12, flexWrap: 'wrap' }}>
                                <Tag>账号：{member.account}</Tag>
                                <Tag color="purple">
                                    角色：{member.roles.length ? member.roles.join(' / ') : '未挂角色'}
                                </Tag>
                                <Tag color={member.status === 1 ? 'green' : 'red'}>
                                    {member.status === 1 ? '在职' : '已禁用'}
                                </Tag>
                                <Tag>所属团队 {memberTeams.length} 个</Tag>
                                {memberTeams.some((t) => t.members.find((m) => m.userId === member.userId)?.lead) && (
                                    <Tag color="gold">是团队负责人</Tag>
                                )}
                            </Space>

                            <Typography.Title level={5}>有效权限（{member.effectivePermissions.length}）</Typography.Title>
                            <PermissionGroups
                                codes={member.effectivePermissions}
                                permRows={permRows}
                                emptyText="该成员没有权限（未挂角色或角色未勾任何权限点）。"
                            />

                            <Typography.Title level={5}>其中继承来的（{inherited.length}）</Typography.Title>
                            {inherited.length === 0 ? (
                                <Typography.Paragraph type="secondary">
                                    没有继承——这些权限全部来自他自己的角色。
                                </Typography.Paragraph>
                            ) : (
                                <>
                                    <Typography.Paragraph type="secondary">
                                        来自他带队的团队（子树并集），不是自己角色勾出来的。
                                    </Typography.Paragraph>
                                    <PermissionGroups codes={inherited} permRows={permRows} />
                                </>
                            )}

                            <Typography.Title level={5}>来自角色的（{member.ownPermissions.length}）</Typography.Title>
                            <PermissionGroups codes={member.ownPermissions} permRows={permRows} />

                            <Typography.Title level={5}>挂在哪些团队</Typography.Title>
                            <Space size={8} wrap>
                                {memberTeams.map((t) => (
                                    <Tag
                                        key={t.id}
                                        style={{ cursor: 'pointer' }}
                                        onClick={() => setSelected(`t:${t.id}`)}
                                    >
                                        {t.name}
                                    </Tag>
                                ))}
                            </Space>
                        </>
                    )}

                    {!isRoot && !team && !member && (
                        <Typography.Text type="secondary">选中节点不存在，可能已被删除。</Typography.Text>
                    )}
                </Card>
            </div>

            {/* 新建团队：上级写死为当前选中的节点——改上级要连同「不能挂到自己下级下面」
                一起校验，那是「团队」页签编辑弹窗的事，这里不重复实现一套 */}
            {editable && (
                <Modal
                    title="新建团队"
                    open={createFor !== null}
                    onCancel={() => setCreateFor(null)}
                    afterClose={() => createForm.resetFields()}
                    onOk={() => createForm.submit()}
                    okText="创建"
                    cancelText="取消"
                    confirmLoading={creating}
                    width={440}
                    destroyOnClose
                >
                    <Form
                        form={createForm}
                        layout="vertical"
                        onFinish={submitCreate}
                        style={{ marginTop: 16 }}
                    >
                        <div style={{ marginBottom: 16 }}>
                            <div style={{ color: '#98a2b3', fontSize: 12, marginBottom: 4 }}>上级团队</div>
                            <Typography.Text strong>{createFor?.parentName}</Typography.Text>
                            <Typography.Paragraph
                                type="secondary"
                                style={{ marginTop: 4, marginBottom: 0, fontSize: 12 }}
                            >
                                挂在它下面。创建后如需换上级，到「团队」页签编辑。
                            </Typography.Paragraph>
                        </div>
                        <Form.Item
                            name="name"
                            label="团队名称"
                            rules={[
                                { required: true, message: '团队名称不能为空' },
                                { max: 64, message: '团队名称最长 64 字' }
                            ]}
                        >
                            <Input maxLength={64} placeholder="如：华东交付组" />
                        </Form.Item>
                        <Form.Item name="description" label="说明">
                            <Input maxLength={255} placeholder="这个团队负责什么" />
                        </Form.Item>
                    </Form>
                </Modal>
            )}

            {/* 批量拉人：候选里过滤掉已在队的成员——后端幂等，但重复提交会按 payload
                刷新 is_lead，人家的负责人标记可能被 lead=false 冲掉，过滤掉最稳 */}
            {editable && (
                <Modal
                    title={addFor ? `批量拉人：加入「${addTeam?.name ?? addFor.name}」` : '批量拉人'}
                    open={addFor !== null}
                    onCancel={() => setAddFor(null)}
                    onOk={submitBatchAdd}
                    okText="加入"
                    cancelText="取消"
                    okButtonProps={{ disabled: picked.length === 0 }}
                    confirmLoading={adding}
                    width={520}
                    destroyOnClose
                >
                    <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 12 }}>
                        按姓名或账号搜索、一次多选；已在队里的不列出。个别账号已禁用会被跳过并给出原因，
                        其余照常加入——加入立即生效，团队有效权限是整棵子树在职成员的并集。
                    </Typography.Paragraph>
                    <Select
                        mode="multiple"
                        style={{ width: '100%' }}
                        placeholder="搜索姓名或账号，可多选"
                        value={picked}
                        onChange={(v) => setPicked(v as number[])}
                        optionFilterProp="label"
                        notFoundContent={pool === null ? <Spin size="small" /> : '没有可选账号'}
                        options={(pool ?? [])
                            .filter((u) => !(addTeam?.members ?? []).some((m) => m.userId === u.id))
                            .map((u) => ({
                                value: u.id,
                                label:
                                    u.status === 1
                                        ? `${u.nickname}（${u.account}）`
                                        : `${u.nickname}（${u.account}）·已禁用`,
                                disabled: u.status !== 1
                            }))}
                    />
                    {picked.length > 0 && (
                        <Typography.Text
                            type="secondary"
                            style={{ display: 'block', marginTop: 8, fontSize: 12 }}
                        >
                            已选 {picked.length} 人
                        </Typography.Text>
                    )}
                </Modal>
            )}
        </>
    )
}
