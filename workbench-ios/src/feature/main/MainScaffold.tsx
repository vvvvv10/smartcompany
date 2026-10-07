/**
 * 主框架：底部 3 个 Tab——从 workbench-android 的 MainScaffold.kt 移植。
 *
 * 用 React state 存选中项，切 Tab 不销毁页面状态，回来时筛选条件、滚动位置都还在。
 *
 * 原「系统」Tab 已按需求去掉：注册/运营数据按权限挪进了概览页的分段，
 * 网关健康与路由不再在 App 里展示（web 端仍有）。
 */

import React, { useState } from 'react'
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { OverviewScreen } from '../overview/OverviewScreen'
import { ApprovalsScreen } from '../approvals/ApprovalsScreen'
import { MeScreen } from '../me/MeScreen'
import { usePendingCount } from './PendingBadge'
import { BrandTeal, TextMuted } from '../../ui/theme'

type Tab = 'overview' | 'approvals' | 'me'

const TABS: { key: Tab; label: string; icon: string }[] = [
    { key: 'overview', label: '概览', icon: '📊' },
    { key: 'approvals', label: '审批', icon: '✅' },
    { key: 'me', label: '我的', icon: '👤' }
]

export function MainScaffold() {
    const [selected, setSelected] = useState<Tab>('overview')
    const pending = usePendingCount()

    return (
        <View style={styles.root}>
            <View style={styles.content}>
                {selected === 'overview' ? <OverviewScreen /> : null}
                {selected === 'approvals' ? <ApprovalsScreen /> : null}
                {selected === 'me' ? <MeScreen /> : null}
            </View>

            <View style={styles.bottomBar}>
                {TABS.map((tab) => {
                    const active = selected === tab.key
                    const showBadge = tab.key === 'approvals' && pending > 0
                    return (
                        <TouchableOpacity
                            key={tab.key}
                            onPress={() => setSelected(tab.key)}
                            style={styles.tabItem}
                        >
                            <View>
                                <Text style={[styles.tabIcon, active && styles.tabIconActive]}>
                                    {tab.icon}
                                </Text>
                                {showBadge ? (
                                    <View style={styles.badge}>
                                        <Text style={styles.badgeText}>{pending > 99 ? '99+' : pending}</Text>
                                    </View>
                                ) : null}
                            </View>
                            <Text style={[styles.tabLabel, active && styles.tabLabelActive]}>{tab.label}</Text>
                        </TouchableOpacity>
                    )
                })}
            </View>
        </View>
    )
}

const styles = StyleSheet.create({
    root: {
        flex: 1,
        backgroundColor: '#fff'
    },
    content: {
        flex: 1
    },
    bottomBar: {
        flexDirection: 'row',
        backgroundColor: '#fff',
        borderTopWidth: 1,
        borderTopColor: '#EAECF0',
        paddingBottom: 4
    },
    tabItem: {
        flex: 1,
        alignItems: 'center',
        paddingVertical: 8
    },
    tabIcon: {
        fontSize: 20,
        opacity: 0.55
    },
    tabIconActive: {
        opacity: 1
    },
    tabLabel: {
        fontSize: 12,
        color: TextMuted,
        marginTop: 2
    },
    tabLabelActive: {
        color: BrandTeal,
        fontWeight: '600'
    },
    badge: {
        position: 'absolute',
        top: -2,
        right: -8,
        backgroundColor: '#EC4899',
        borderRadius: 9,
        minWidth: 18,
        height: 18,
        alignItems: 'center',
        justifyContent: 'center',
        paddingHorizontal: 4
    },
    badgeText: {
        color: '#fff',
        fontSize: 11,
        fontWeight: 'bold'
    }
})
