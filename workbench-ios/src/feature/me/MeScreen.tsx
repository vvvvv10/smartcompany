/**
 * 「我的」：身份、服务地址——从 workbench-android 的 MeScreen.kt 移植。
 *
 * 花名**只读**——改花名要走审批（在「审批」页提交申请、管理员批准），
 * 所以这里只展示当前花名，不给编辑入口。
 */

import React, { useState } from 'react'
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { useAuth } from '../auth/AuthContext'
import { ConfirmDialog, InfoRow, PageHeader, SectionCard, WbTag } from '../../ui/Components'
import { getSelectedServer } from '../../store/servers'
import { AdminOrange, BrandTeal, ErrorRed, FontSize, Radius, Spacing, TextMuted, TextPrimary, TextSecondary } from '../../ui/theme'

export function MeScreen() {
    const { profile, logout } = useAuth()
    const [confirmLogout, setConfirmLogout] = useState(false)
    const p = profile
    const roles = p?.gatewayRoles ?? []
    const server = getSelectedServer()
    // 公司显示租户字典名；'default' 是 38 号迁移前的遗留值，兜底显示「未知」
    const rawCompany = p?.tenantName || p?.dbTenantId || ''
    const company = rawCompany && rawCompany !== 'default' ? rawCompany : '未知'

    return (
        <View style={styles.root}>
            <PageHeader title={p?.nickname || '我的'} subtitle={p?.account} />
            <ScrollView contentContainerStyle={styles.content}>
                {/* 身份卡 */}
                <View style={styles.identityCard}>
                    <View style={styles.avatar}>
                        <Text style={styles.avatarText}>{(p?.nickname || p?.account || '?').slice(0, 1)}</Text>
                    </View>
                    <View style={styles.identityMain}>
                        <Text style={styles.identityName}>{p?.nickname || p?.account || '未登录'}</Text>
                        <Text style={styles.identityAccount}>{p?.account || '—'}</Text>
                        <View style={styles.roleRow}>
                            {roles.length > 0 ? (
                                roles.map((role: string) => (
                                    <WbTag
                                        key={role}
                                        label={{
                                            text: role,
                                            color: role === 'ADMIN' ? AdminOrange : BrandTeal
                                        }}
                                    />
                                ))
                            ) : (
                                <WbTag label={{ text: '无角色', color: TextMuted }} />
                            )}
                        </View>
                    </View>
                </View>

                {/* 账号信息 */}
                <SectionCard title="账号信息">
                    <View style={styles.infoBox}>
                        <InfoRow label="工号" value={p?.id != null ? String(p.id) : '—'} />
                        <InfoRow label="公司" value={company} />
                        <InfoRow label="花名" value={p?.nickname || '—'} />
                        <InfoRow label="服务地址" value={server} />
                    </View>
                </SectionCard>

                {/* 关于 */}
                <SectionCard title="关于">
                    <View style={styles.aboutBox}>
                        <View style={styles.aboutRow}>
                            <Text style={styles.aboutIcon}>👆</Text>
                            <Text style={styles.aboutText}>
                                Access Token 15 分钟，过期由客户端静默刷新；Refresh Token 15 天，用后即焚。
                            </Text>
                        </View>
                        <View style={styles.aboutRow}>
                            <Text style={styles.aboutIcon}>☁️</Text>
                            <Text style={styles.aboutText}>
                                所有请求经网关校验令牌后注入身份头，App 不自行拼接任何身份字段。
                            </Text>
                        </View>
                        <View style={styles.aboutRow}>
                            <Text style={styles.aboutIcon}>🏷</Text>
                            <Text style={styles.aboutText}>
                                花名变更需审批：在「审批」页提交申请，管理员批准后生效。
                            </Text>
                        </View>
                    </View>
                </SectionCard>

                {/* 退出登录 */}
                <TouchableOpacity onPress={() => setConfirmLogout(true)} style={styles.logoutBtn}>
                    <Text style={styles.logoutBtnText}>退出登录</Text>
                </TouchableOpacity>
            </ScrollView>

            <ConfirmDialog
                visible={confirmLogout}
                title="退出登录"
                message="退出后需要重新输入账号密码，本地会话将被清除。"
                confirmText="退出"
                danger
                onConfirm={() => {
                    setConfirmLogout(false)
                    void logout()
                }}
                onDismiss={() => setConfirmLogout(false)}
            />
        </View>
    )
}

const styles = StyleSheet.create({
    root: {
        flex: 1,
        backgroundColor: '#fff'
    },
    content: {
        padding: Spacing.lg,
        gap: Spacing.md,
        paddingBottom: 32
    },
    identityCard: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#fff',
        borderRadius: Radius.card,
        padding: 16,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 2,
        elevation: 1
    },
    avatar: {
        width: 52,
        height: 52,
        borderRadius: 26,
        backgroundColor: BrandTeal + '1F',
        alignItems: 'center',
        justifyContent: 'center'
    },
    avatarText: {
        fontSize: FontSize.titleLarge,
        fontWeight: 'bold',
        color: BrandTeal
    },
    identityMain: {
        flex: 1,
        marginLeft: 14
    },
    identityName: {
        fontSize: FontSize.titleMedium,
        fontWeight: 'bold',
        color: TextPrimary
    },
    identityAccount: {
        fontSize: FontSize.bodySmall,
        color: TextSecondary,
        marginTop: 2
    },
    roleRow: {
        flexDirection: 'row',
        gap: 6,
        marginTop: 8
    },
    infoBox: {
        paddingHorizontal: 14,
        paddingVertical: 8
    },
    aboutBox: {
        padding: 14
    },
    aboutRow: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 8
    },
    aboutIcon: {
        fontSize: 16,
        marginRight: 8
    },
    aboutText: {
        flex: 1,
        fontSize: FontSize.bodySmall,
        color: TextSecondary,
        lineHeight: 18
    },
    logoutBtn: {
        height: 48,
        borderRadius: Radius.button,
        borderWidth: 1,
        borderColor: '#EAECF0',
        alignItems: 'center',
        justifyContent: 'center'
    },
    logoutBtnText: {
        fontSize: FontSize.labelLarge,
        color: ErrorRed,
        fontWeight: '600'
    }
})
