/**
 * 概览：个人工作台的主体——从 workbench-android 的 OverviewScreen.kt + OverviewViewModel.kt 移植。
 *
 * **按权限分段展示两类数据**（可见性判定与 web 端菜单显隐同一份权限点）：
 *  - 销售（`crm:read`）：CRM 个人工作台，核心是「今天我该跟进谁」，待办排第一；
 *  - 管理员（`user:list` + `dashboard:view`）：用户注册与运营数据；
 *  - 两者兼有 → 顶部分段按钮切换，默认停在运营数据。
 *
 * 四路并发、各自降级，串起来就是白白多几个 RTT：
 *  - `ops` / `crm`   两块主数据，**按可见分段才拉**，一路失败不拖垮另一路；
 *  - `pendingCount`  待我审批的条数（无 `nickname:review` 权限时后端 403 → 记 0）；
 *  - `myRequest`     我自己的花名申请，**只在 PENDING / REJECTED 时带出来**。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { RefreshControl, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { api, displayMessage } from '../../api/client'
import type { AdminDashboard, CrmWorkbench, MyNicknameRequest } from '../../api/types'
import { useAuth } from '../auth/AuthContext'
import { PendingBadge } from '../main/PendingBadge'
import { EmptyBox, ErrorNote, LoadingBox, PageHeader, PermissionDenied, SectionCard, StatCard, WbTag } from '../../ui/Components'
import { dueLabel, formatDateTime, formatMoney, greeting, requestStatusLabel, stageLabel, statusLabel, levelLabel, typeLabel, todayText } from '../../ui/labels'
import { BrandAmber, BrandEmerald, BrandIndigo, BrandRose, BrandSky, FontSize, OverdueRed, Radius, Spacing, TextPrimary, TextSecondary } from '../../ui/theme'

type Section = 'ops' | 'crm'

interface OverviewData {
    tabs: Section[]
    crm: CrmWorkbench | null
    crmError: string | null
    ops: AdminDashboard | null
    opsError: string | null
    pendingApprovals: number
    myRequestStatus: string | null
}

export function OverviewScreen() {
    const { profile } = useAuth()
    const [state, setState] = useState<OverviewData | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [refreshing, setRefreshing] = useState(false)
    const [selected, setSelected] = useState<Section | null>(null)

    const perms = useMemo(() => profile?.permissionCodes ?? [], [profile])
    const canOps = perms.includes('user:list') && perms.includes('dashboard:view')
    const canCrm = perms.includes('crm:read')

    const visibleTabs = useCallback((): Section[] => {
        const tabs: Section[] = []
        if (canOps) tabs.push('ops')
        if (canCrm) tabs.push('crm')
        return tabs
    }, [canOps, canCrm])

    const load = useCallback(async () => {
        try {
            const tabs = visibleTabs()

            const [pendingResult, myRequestResult, opsResult, crmResult] = await Promise.allSettled([
                api.get<{ count: number }>('/api/admin/nickname-requests/pending-count'),
                api.get<MyNicknameRequest>('/api/users/me/nickname/request'),
                tabs.includes('ops') ? api.get<AdminDashboard>('/api/admin/dashboard') : Promise.resolve(null),
                tabs.includes('crm') ? api.get<CrmWorkbench>('/api/crm/workbench') : Promise.resolve(null)
            ])

            // 换号后沿用旧选择没有意义，越界就回默认
            setSelected((prev) => (prev && tabs.includes(prev) ? prev : tabs[0] ?? null))

            const pendingCount =
                pendingResult.status === 'fulfilled' ? pendingResult.value?.count ?? 0 : 0
            PendingBadge.update(pendingCount)

            const myRequest = myRequestResult.status === 'fulfilled' ? myRequestResult.value : null
            const ops = opsResult.status === 'fulfilled' ? opsResult.value : null
            const crm = crmResult.status === 'fulfilled' ? crmResult.value : null

            const wanted = [opsResult, crmResult].filter((r) => r.status === 'rejected')
            // 该看的全挂了才整页报错；只挂一路就让另一路照常展示
            if (wanted.length > 0 && wanted.length === [opsResult, crmResult].filter((r) => r !== undefined).length) {
                const msg = wanted[0].status === 'rejected' ? displayMessage(wanted[0].reason) : '加载失败'
                setError(msg)
                setState(null)
                return
            }

            setState({
                tabs,
                crm,
                crmError: crmResult.status === 'rejected' ? displayMessage(crmResult.reason) : null,
                ops,
                opsError: opsResult.status === 'rejected' ? displayMessage(opsResult.reason) : null,
                pendingApprovals: pendingCount,
                // 只把**真正待办**的申请带出来：PENDING 等人批、REJECTED 要重交
                myRequestStatus: myRequest?.status === 'PENDING' || myRequest?.status === 'REJECTED'
                    ? myRequest.status
                    : null
            })
            setError(null)
        } catch (e) {
            setError(displayMessage(e))
            setState(null)
        }
    }, [visibleTabs])

    // 权限决定的分段变化时重载。排进微任务：
    // 数据加载的 setState 不能与 effect 同步执行（react-hooks/set-state-in-effect）
    useEffect(() => {
        void Promise.resolve().then(() => load())
    }, [load])

    // 下拉刷新的转圈状态放在事件处理器里，不进 effect（避免级联渲染）
    const onRefresh = () => {
        setRefreshing(true)
        void load().finally(() => setRefreshing(false))
    }

    const active = selected && state?.tabs.includes(selected) ? selected : state?.tabs[0]

    return (
        <View style={styles.root}>
            <PageHeader
                title={`${greeting()}，${profile?.nickname || profile?.account || '你好'}`}
                subtitle={`${todayText()} · ` + (() => {
                    if (canOps && canCrm) return '运营数据与业务数据，分段切换查看'
                    if (canOps) return '用户注册与运营概览'
                    if (canCrm) return '只显示与你相关的客户、商机与跟进'
                    return '当前账号没有可查看的数据权限'
                })()}
            />
            <ScrollView
                style={styles.scroll}
                contentContainerStyle={styles.scrollContent}
                refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            >
                {error ? (
                    <View style={styles.center}>
                        <Text style={styles.errorText}>{error}</Text>
                        <Text style={styles.errorSub}>下拉可重试</Text>
                    </View>
                ) : !state ? (
                    <LoadingBox />
                ) : (
                    <>
                        {/* 待我处理 */}
                        {state.pendingApprovals > 0 || state.myRequestStatus ? (
                            <SectionCard title="待我处理">
                                {state.pendingApprovals > 0 ? (
                                    <RowItem
                                        title="花名变更申请"
                                        desc={`${state.pendingApprovals} 条待审批，去「审批」页处理`}
                                        tag={requestStatusLabel('PENDING')}
                                    />
                                ) : null}
                                {state.myRequestStatus ? (
                                    <RowItem
                                        title="我提交的花名申请"
                                        desc={
                                            state.myRequestStatus === 'PENDING'
                                                ? '审批通过后花名才会变更'
                                                : '已被驳回，可在「审批」页重新提交'
                                        }
                                        tag={requestStatusLabel(state.myRequestStatus)}
                                    />
                                ) : null}
                            </SectionCard>
                        ) : null}

                        {/* 分段 */}
                        {state.tabs.length > 1 ? (
                            <View style={styles.segment}>
                                {state.tabs.map((tab) => (
                                    <TouchableOpacity
                                        key={tab}
                                        onPress={() => setSelected(tab)}
                                        style={[styles.segmentBtn, active === tab && styles.segmentBtnActive]}
                                    >
                                        <Text style={[styles.segmentText, active === tab && styles.segmentTextActive]}>
                                            {tab === 'ops' ? '运营数据' : '业务数据'}
                                        </Text>
                                    </TouchableOpacity>
                                ))}
                            </View>
                        ) : null}

                        {state.tabs.length === 0 ? (
                            <SectionCard title="概览">
                                <PermissionDenied
                                    permission="crm:read（业务数据）或 user:list + dashboard:view（运营数据）"
                                    current={perms}
                                />
                            </SectionCard>
                        ) : active === 'ops' ? (
                            <OpsBlocks data={state} />
                        ) : (
                            <CrmBlocks data={state} />
                        )}
                    </>
                )}
            </ScrollView>
        </View>
    )
}

// ---------------- 运营数据 ----------------

function OpsBlocks({ data }: { data: OverviewData }) {
    if (!data.ops) {
        return <ErrorNote text={data.opsError || '运营数据加载失败，下拉可重试'} />
    }
    const d = data.ops
    const t = d.totals

    return (
        <>
            <View style={styles.statGrid}>
                <StatCard icon="👥" tint={BrandIndigo} value={String(t.totalUsers)} label="总用户" hint={`停用 ${t.disabledUsers}`} />
                <StatCard icon="🧑‍🤝‍🧑" tint={BrandSky} value={String(t.todayRegistrations)} label="今日注册" hint={`本周 ${t.weekRegistrations}`} />
                <StatCard icon="👤" tint={BrandEmerald} value={String(t.activeUsers)} label="活跃用户" hint={`近 30 天 ${t.monthActiveUsers} 人`} />
                <StatCard
                    icon="🛡"
                    tint={BrandAmber}
                    value={String(d.roleDistribution.length)}
                    label="角色数"
                    hint={d.roleDistribution.map((r) => `${r.roleCode} ${r.count}`).join(' / ') || '暂无角色数据'}
                />
            </View>

            {d.trend.length > 0 ? (
                <SectionCard title={`近 ${d.trend.length} 天注册`}>
                    <View style={styles.trendBox}>
                        {d.trend.map((p) => {
                            const max = Math.max(...d.trend.map((x) => x.registrations), 1)
                            return (
                                <View key={p.date} style={styles.trendRow}>
                                    <Text style={styles.trendDate}>{p.date.slice(5)}</Text>
                                    <View style={styles.trendBarBg}>
                                        <View style={[styles.trendBar, { width: `${(p.registrations / max) * 100}%` }]} />
                                    </View>
                                    <Text style={styles.trendValue}>{p.registrations}</Text>
                                </View>
                            )
                        })}
                    </View>
                </SectionCard>
            ) : null}
        </>
    )
}

// ---------------- 业务数据 ----------------

function CrmBlocks({ data }: { data: OverviewData }) {
    if (!data.crm) {
        return <ErrorNote text={data.crmError || '数据加载失败，下拉可重试'} />
    }
    const workbench = data.crm
    const stats = workbench.stats
    const overdue = workbench.todos.filter((t) => t.due === 'OVERDUE').length

    return (
        <>
            <View style={styles.statGrid}>
                <StatCard icon="👥" tint={BrandSky} value={String(stats.customers)} label="我的客户" hint={`近 7 天新增 ${stats.weekNew}`} />
                <StatCard icon="👍" tint={BrandEmerald} value={String(stats.following)} label="跟进中" hint={`成交 ${stats.deal} 个`} />
                <StatCard icon="🚀" tint={BrandAmber} value={String(stats.oppActive)} label="进行中商机" hint={`在手 ${formatMoney(stats.oppAmount)}`} />
                <StatCard icon="💰" tint={BrandRose} value={formatMoney(stats.winAmount)} label="赢单金额" hint="历史累计" />
                <StatCard
                    icon="📝"
                    tint={stats.overdue > 0 ? OverdueRed : BrandSky}
                    value={String(stats.today + stats.upcoming)}
                    label="待跟进"
                    hint={
                        stats.overdue > 0
                            ? `${stats.overdue} 条已逾期，优先处理`
                            : stats.today > 0
                              ? `其中 ${stats.today} 条就在今天`
                              : '未来 7 天没有积压'
                    }
                />
                <StatCard icon="🧑‍💼" tint="#2E90FA" value={String(stats.weekNew)} label="近 7 天新增" hint="按建档时间统计" />
            </View>

            {/* 跟进待办 */}
            <SectionCard
                title={`跟进待办（${workbench.todos.length}）`}
                badge={overdue > 0 ? <WbTag label={{ text: `${overdue} 条逾期`, color: OverdueRed }} /> : null}
            >
                {workbench.todos.length === 0 ? (
                    <EmptyBox text="未来 7 天没有待跟进的客户，节奏很稳" />
                ) : (
                    workbench.todos.map((todo) => {
                        const due = dueLabel(todo.due)
                        return (
                            <View key={todo.id} style={styles.listRow}>
                                <View style={[styles.dueDot, { backgroundColor: due.color }]} />
                                <View style={styles.listMain}>
                                    <View style={styles.listTitleRow}>
                                        <Text style={styles.listTitle}>{todo.customerName}</Text>
                                        {todo.due !== 'UPCOMING' ? <WbTag label={due} /> : null}
                                    </View>
                                    <Text style={styles.listDesc} numberOfLines={1}>
                                        {todo.content}
                                    </Text>
                                </View>
                                <View style={styles.listRight}>
                                    <Text style={styles.listTime}>{formatDateTime(todo.nextFollowAt)}</Text>
                                    <WbTag label={typeLabel(todo.type)} />
                                </View>
                            </View>
                        )
                    })
                )}
            </SectionCard>

            {/* 我的商机 */}
            <SectionCard
                title={`我的商机（${workbench.opportunities.length}）`}
                badge={
                    <Text style={styles.sectionBadgeText}>{formatMoney(stats.oppAmount)}</Text>
                }
            >
                {workbench.opportunities.length === 0 ? (
                    <EmptyBox text="暂无进行中的商机" />
                ) : (
                    workbench.opportunities.map((opp) => (
                        <View key={opp.id} style={styles.listRow}>
                            <View style={styles.listMain}>
                                <Text style={styles.listTitle} numberOfLines={1}>
                                    {opp.name}
                                </Text>
                                <Text style={styles.listDesc} numberOfLines={1}>
                                    {opp.customerName} · {opp.probability}% · 预计 {opp.expectedCloseDate || '未定'}
                                </Text>
                            </View>
                            <View style={styles.listRight}>
                                <Text style={styles.oppAmount}>{formatMoney(opp.amount)}</Text>
                                <WbTag label={stageLabel(opp.stage)} />
                            </View>
                        </View>
                    ))
                )}
            </SectionCard>

            {/* 最近客户 */}
            <SectionCard title={`最近客户（${workbench.customers.length}）`}>
                {workbench.customers.length === 0 ? (
                    <EmptyBox text="还没有建档的客户" />
                ) : (
                    workbench.customers.map((c) => (
                        <View key={c.id} style={styles.listRow}>
                            <View style={styles.listMain}>
                                <Text style={styles.listTitle}>{c.name}</Text>
                                <Text style={styles.listDesc}>建档于 {formatDateTime(c.createdAt)}</Text>
                            </View>
                            <View style={styles.listRight}>
                                <WbTag label={levelLabel(c.level)} />
                                <WbTag label={statusLabel(c.status)} />
                            </View>
                        </View>
                    ))
                )}
            </SectionCard>

            {/* 最近跟进 */}
            <SectionCard title={`最近跟进（${workbench.followUps.length}）`}>
                {workbench.followUps.length === 0 ? (
                    <EmptyBox text="还没有跟进记录" />
                ) : (
                    workbench.followUps.map((fu, i) => (
                        <View key={i} style={styles.listRow}>
                            <Text style={styles.followIcon}>🕐</Text>
                            <View style={styles.listMain}>
                                <Text style={styles.followTitle}>
                                    {fu.customerName} · {typeLabel(fu.type).text}
                                </Text>
                                <Text style={styles.listDesc} numberOfLines={2}>
                                    {fu.content}
                                </Text>
                            </View>
                            <Text style={styles.listTime}>{formatDateTime(fu.createdAt)}</Text>
                        </View>
                    ))
                )}
            </SectionCard>
        </>
    )
}

/** 「待我处理」区块里的一行：标题 + 说明 + 右侧状态徽标。 */
function RowItem({ title, desc, tag }: { title: string; desc: string; tag: { text: string; color: string } }) {
    return (
        <View style={styles.rowItem}>
            <View style={styles.listMain}>
                <Text style={styles.rowItemTitle}>{title}</Text>
                <Text style={styles.listDesc}>{desc}</Text>
            </View>
            <WbTag label={tag} />
        </View>
    )
}

const styles = StyleSheet.create({
    root: {
        flex: 1,
        backgroundColor: '#fff'
    },
    scroll: {
        flex: 1
    },
    scrollContent: {
        padding: Spacing.lg,
        gap: Spacing.md,
        paddingBottom: 32
    },
    center: {
        alignItems: 'center',
        padding: 24,
        gap: 10
    },
    errorText: {
        fontSize: FontSize.bodyMedium,
        color: TextPrimary
    },
    errorSub: {
        fontSize: FontSize.bodySmall,
        color: TextSecondary
    },
    segment: {
        flexDirection: 'row',
        backgroundColor: '#F8F9FC',
        borderRadius: Radius.button,
        padding: 3
    },
    segmentBtn: {
        flex: 1,
        paddingVertical: 8,
        alignItems: 'center',
        borderRadius: Radius.button - 2
    },
    segmentBtnActive: {
        backgroundColor: '#fff'
    },
    segmentText: {
        fontSize: FontSize.labelLarge,
        color: TextSecondary
    },
    segmentTextActive: {
        color: BrandAmber,
        fontWeight: '600'
    },
    statGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: Spacing.md
    },
    trendBox: {
        paddingHorizontal: 14,
        paddingVertical: 6,
        gap: 7
    },
    trendRow: {
        flexDirection: 'row',
        alignItems: 'center'
    },
    trendDate: {
        width: 44,
        fontSize: FontSize.labelSmall,
        color: TextSecondary
    },
    trendBarBg: {
        flex: 1,
        height: 8,
        backgroundColor: '#F8F9FC',
        borderRadius: 4,
        overflow: 'hidden'
    },
    trendBar: {
        height: 8,
        backgroundColor: BrandAmber,
        borderRadius: 4
    },
    trendValue: {
        width: 26,
        fontSize: FontSize.labelSmall,
        fontWeight: '600',
        color: TextPrimary,
        textAlign: 'right',
        marginLeft: 10
    },
    sectionBadgeText: {
        fontSize: FontSize.labelMedium,
        color: BrandEmerald,
        fontWeight: 'bold'
    },
    listRow: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 14,
        paddingVertical: 10
    },
    dueDot: {
        width: 8,
        height: 8,
        borderRadius: 4
    },
    listMain: {
        flex: 1,
        marginLeft: 10
    },
    listTitleRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6
    },
    listTitle: {
        fontSize: FontSize.titleSmall,
        fontWeight: '600',
        color: TextPrimary
    },
    listDesc: {
        fontSize: FontSize.bodySmall,
        color: TextSecondary,
        marginTop: 2
    },
    listRight: {
        alignItems: 'flex-end',
        gap: 4,
        marginLeft: 8
    },
    listTime: {
        fontSize: FontSize.labelSmall,
        color: TextSecondary
    },
    oppAmount: {
        fontSize: FontSize.titleSmall,
        fontWeight: 'bold',
        color: BrandEmerald
    },
    followIcon: {
        fontSize: 16,
        color: TextSecondary
    },
    followTitle: {
        fontSize: FontSize.labelMedium,
        fontWeight: '600',
        color: TextPrimary
    },
    rowItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 14,
        paddingVertical: 10
    },
    rowItemTitle: {
        fontSize: FontSize.titleSmall,
        fontWeight: '600',
        color: TextPrimary
    }
})
