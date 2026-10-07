/**
 * 审批页——从 workbench-android 的 ApprovalsScreen.kt + ApprovalsViewModel.kt 移植。
 *
 * **移动端只读**：本页只查询申请进度，不提供提交/批准/驳回——
 * 管理台的修改类操作一律不下发到手机端，审批动作只能在 Web 管理台完成。
 *
 * 两个独立区块，权限各走各的：
 *  - **我的申请**：任何人可看（后端状态查询不设权限点）
 *  - **待审批**：需要 `nickname:review`，缺权限时展示 403 占位而不是报错
 */

import React, { useCallback, useEffect, useState } from 'react'
import { RefreshControl, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { api, ApiException, displayMessage } from '../../api/client'
import type { NicknameRequestRow } from '../../api/types'
import { PendingBadge } from '../main/PendingBadge'
import { EmptyBox, LoadingBox, PageHeader, PermissionDenied, SectionCard, WbTag } from '../../ui/Components'
import { formatDate, formatDateTime, requestStatusLabel } from '../../ui/labels'
import { BrandTeal, ErrorRed, FontSize, Radius, Spacing, TextPrimary, TextSecondary } from '../../ui/theme'

interface ApprovalsData {
    pending: NicknameRequestRow[]
    pendingTotal: number
    mine: NicknameRequestRow[]
    pendingCount: number
    reviewForbidden: boolean
    reviewPermissionMissing: string | null
}

export function ApprovalsScreen() {
    const [state, setState] = useState<ApprovalsData | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [refreshing, setRefreshing] = useState(false)
    const [tab, setTab] = useState<'PENDING' | 'ALL'>('PENDING')

    const load = useCallback(async () => {
        try {
            const status = tab === 'ALL' ? undefined : 'PENDING'
            let forbidden = false
            let missing: string | null = null

            // 待审批列表需要 nickname:review，没有权限时后端 403 —— 这是正常情况，
            // 要作为 reviewForbidden 交给 UI 展示 403 占位，而不是「加载失败」
            const list = await api
                .get<{ list: NicknameRequestRow[]; total: number }>(
                    `/api/admin/nickname-requests${status ? `?status=${status}` : ''}`
                )
                .catch((e: unknown) => {
                    if (e instanceof ApiException && e.httpCode === 403) {
                        forbidden = true
                        missing = e.message
                        return null
                    }
                    throw e
                })

            const mineRes = await api
                .get<{ list: NicknameRequestRow[] }>('/api/users/me/nickname/requests')
                .catch(() => null)
            const mine = (mineRes?.list ?? []).filter((r) => r.status === 'PENDING')

            let pendingCount = 0
            if (!forbidden) {
                pendingCount =
                    status === 'PENDING'
                        ? list?.total ?? 0
                        : await api
                              .get<{ count: number }>('/api/admin/nickname-requests/pending-count')
                              .then((r) => r.count)
                              .catch(() => 0)
            }

            PendingBadge.update(pendingCount)

            setState({
                pending: list?.list ?? [],
                pendingTotal: list?.total ?? 0,
                mine,
                pendingCount,
                reviewForbidden: forbidden,
                reviewPermissionMissing: missing
            })
            setError(null)
        } catch (e) {
            setError(displayMessage(e))
            setState(null)
        }
    }, [tab])

    // tab 切换即重载（对齐安卓端 switchTab → load(force)；首屏也走这里）。
    // 排进微任务：数据加载的 setState 不能与 effect 同步执行（react-hooks/set-state-in-effect）
    useEffect(() => {
        void Promise.resolve().then(() => load())
    }, [load])

    // 下拉刷新的转圈状态放在事件处理器里，不进 effect（避免级联渲染）
    const onRefresh = () => {
        setRefreshing(true)
        void load().finally(() => setRefreshing(false))
    }

    return (
        <View style={styles.root}>
            <PageHeader title="审批中心" subtitle="申请进度仅查看，审批操作请到管理台" />
            <ScrollView
                style={styles.scroll}
                contentContainerStyle={styles.scrollContent}
                refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            >
                {error ? (
                    <Text style={styles.errorText}>{error}</Text>
                ) : !state ? (
                    <LoadingBox />
                ) : (
                    <>
                        {/* 我的申请 */}
                        <SectionCard title="我的申请">
                            {state.mine.length === 0 ? (
                                <EmptyBox text="暂无待审批的申请" />
                            ) : (
                                state.mine.map((row) => (
                                    <View key={row.id} style={styles.listRow}>
                                        <View style={styles.listMain}>
                                            <View style={styles.listTitleRow}>
                                                <Text style={styles.listTitle}>
                                                    {row.oldNickname} → {row.newNickname}
                                                </Text>
                                                <WbTag label={requestStatusLabel(row.status)} />
                                            </View>
                                            <Text style={styles.listDesc}>提交于 {formatDateTime(row.createdAt)}</Text>
                                        </View>
                                    </View>
                                ))
                            )}
                        </SectionCard>

                        {/* 待我审批 */}
                        {state.reviewForbidden ? (
                            <SectionCard title="待我审批">
                                <PermissionDenied permission="nickname:review" current={[]} />
                            </SectionCard>
                        ) : (
                            <View style={styles.segment}>
                                <TouchableOpacity
                                    onPress={() => setTab('PENDING')}
                                    style={[styles.segmentBtn, tab === 'PENDING' && styles.segmentBtnActive]}
                                >
                                    <Text style={[styles.segmentText, tab === 'PENDING' && styles.segmentTextActive]}>
                                        待审批 {state.pendingCount}
                                    </Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={() => setTab('ALL')}
                                    style={[styles.segmentBtn, tab === 'ALL' && styles.segmentBtnActive]}
                                >
                                    <Text style={[styles.segmentText, tab === 'ALL' && styles.segmentTextActive]}>
                                        全部
                                    </Text>
                                </TouchableOpacity>
                            </View>
                        )}

                        {!state.reviewForbidden ? (
                            <SectionCard title={`申请列表（${state.pending.length}）`}>
                                {state.pending.length === 0 ? (
                                    <EmptyBox text={tab === 'PENDING' ? '没有待审批的申请' : '还没有任何申请'} />
                                ) : (
                                    state.pending.map((row) => <RequestRow key={row.id} row={row} />)
                                )}
                            </SectionCard>
                        ) : null}
                    </>
                )}
            </ScrollView>
        </View>
    )
}

// ---------------- 一条申请（只读） ----------------

function RequestRow({ row }: { row: NicknameRequestRow }) {
    return (
        <View style={styles.listRow}>
            <View style={styles.listMain}>
                <View style={styles.listTitleRow}>
                    <Text style={styles.listTitle}>{row.account}</Text>
                    <WbTag label={requestStatusLabel(row.status)} />
                </View>
                <Text style={styles.listDesc}>
                    {row.oldNickname} → {row.newNickname}
                </Text>
                <Text style={styles.listMeta}>
                    提交于 {formatDateTime(row.createdAt)}
                    {row.reviewedAt ? ` · ${formatDate(row.reviewedAt)}` : ''}
                </Text>
                {row.reviewNote ? (
                    <Text style={styles.listNote}>驳回理由：{row.reviewNote}</Text>
                ) : null}
            </View>
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
    errorText: {
        fontSize: FontSize.bodyMedium,
        color: TextPrimary,
        textAlign: 'center',
        padding: 24
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
        color: BrandTeal,
        fontWeight: '600'
    },
    listRow: {
        paddingHorizontal: 14,
        paddingVertical: 10
    },
    listMain: {
        flex: 1
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
        fontSize: FontSize.bodyMedium,
        color: TextPrimary,
        marginTop: 4
    },
    listMeta: {
        fontSize: FontSize.labelSmall,
        color: TextSecondary,
        marginTop: 2
    },
    listNote: {
        fontSize: FontSize.bodySmall,
        color: ErrorRed,
        marginTop: 4
    }
})
