/**
 * 共享 UI 组件——从 workbench-android 的 ui/Components.kt 移植。
 *
 * 用 React Native 核心组件 + 自定义样式复刻 Material 3 的视觉：
 * 卡片圆角 14、徽标圆角 6、页头青绿渐变。不引入第三方 UI 库，
 * 保持依赖最小（本机没 Xcode，依赖越少越好装越好构建）。
 */

import React from 'react'
import {
    ActivityIndicator,
    Modal,
    StyleSheet,
    Text,
    TouchableOpacity,
    View,
    ViewStyle
} from 'react-native'
import { BrandTeal, ErrorRed, ErrorRedBg, ErrorRedText, FontSize, Radius, TextMuted, TextPrimary, TextSecondary } from './theme'
import type { Label } from './labels'

/** 枚举徽标：底色用枚举自己的语义色，一眼看出状态。 */
export function WbTag({ label, style }: { label: Label; style?: ViewStyle }) {
    return (
        <View style={[styles.tag, { backgroundColor: label.color + '1F' }, style]}>
            <Text style={[styles.tagText, { color: label.color }]}>{label.text}</Text>
        </View>
    )
}

/** 指标卡。数字是主角、标签是配角，`hint` 是第三行的补充说明。 */
export function StatCard({
    icon,
    tint,
    value,
    label,
    hint
}: {
    icon: string
    tint: string
    value: string
    label: string
    hint?: string
}) {
    return (
        <View style={styles.statCard}>
            <View style={[styles.statIcon, { backgroundColor: tint + '1F' }]}>
                <Text style={[styles.statIconText, { color: tint }]}>{icon}</Text>
            </View>
            <Text style={styles.statValue} numberOfLines={1}>
                {value}
            </Text>
            <Text style={styles.statLabel} numberOfLines={1}>
                {label}
            </Text>
            {hint ? (
                <Text style={styles.statHint} numberOfLines={2}>
                    {hint}
                </Text>
            ) : null}
        </View>
    )
}

/** 页面区块卡片：52 头高 + 正文。 */
export function SectionCard({
    title,
    badge,
    action,
    children
}: {
    title: string
    badge?: React.ReactNode
    action?: React.ReactNode
    children: React.ReactNode
}) {
    return (
        <View style={styles.sectionCard}>
            <View style={styles.sectionHeader}>
                <Text style={styles.sectionTitle}>{title}</Text>
                {badge}
                {action}
            </View>
            <View>{children}</View>
        </View>
    )
}

export function LoadingBox() {
    return (
        <View style={styles.loadingBox}>
            <ActivityIndicator size="large" color={BrandTeal} />
        </View>
    )
}

export function EmptyBox({ text }: { text: string }) {
    return (
        <View style={styles.emptyBox}>
            <Text style={styles.emptyText}>{text}</Text>
        </View>
    )
}

/** 一行"标签 + 值"，用于详情页的信息条目。 */
export function InfoRow({ label, value }: { label: string; value: string }) {
    return (
        <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{label}</Text>
            <Text style={styles.infoValue}>{value || '—'}</Text>
        </View>
    )
}

/** 错误提示条：只给一句可读文案，不把堆栈糊给用户。 */
export function ErrorNote({ text }: { text: string }) {
    return (
        <View style={styles.errorNote}>
            <Text style={styles.errorNoteText}>{text}</Text>
        </View>
    )
}

/**
 * 权限不足的占位页，对应 web 端 `RequirePermission` 那个 403 `Result`。
 *
 * 刻意把**缺哪个权限点**和**当前有哪些权限点**都写出来：管理员看到就知道该去
 * 「权限配置」勾哪一项，而不是来回问「为什么进不去」。
 */
export function PermissionDenied({ permission, current }: { permission: string; current: string[] }) {
    return (
        <View style={styles.permissionDenied}>
            <Text style={styles.permissionCode}>403</Text>
            <Text style={styles.permissionText}>该页面需要权限点 {permission}</Text>
            <Text style={styles.permissionSub}>请联系管理员在「权限配置」中授予</Text>
            <Text style={styles.permissionCurrent}>
                当前权限点：{current.length > 0 ? current.join(', ') : '无'}
            </Text>
        </View>
    )
}

/** 渐变页头。每个 Tab 顶部用它统一视觉。 */
export function PageHeader({ title, subtitle }: { title: string; subtitle?: string }) {
    return (
        <View style={styles.pageHeader}>
            <Text style={styles.pageHeaderTitle}>{title}</Text>
            {subtitle ? <Text style={styles.pageHeaderSubtitle}>{subtitle}</Text> : null}
        </View>
    )
}

/** 二次确认框。审批是写操作且改的是全局唯一的花名，一律先确认。 */
export function ConfirmDialog({
    visible,
    title,
    message,
    confirmText,
    danger = false,
    onConfirm,
    onDismiss
}: {
    visible: boolean
    title: string
    message: string
    confirmText: string
    danger?: boolean
    onConfirm: () => void
    onDismiss: () => void
}) {
    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onDismiss}>
            <View style={styles.dialogOverlay}>
                <View style={styles.dialogBox}>
                    <Text style={styles.dialogTitle}>{title}</Text>
                    <Text style={styles.dialogMessage}>{message}</Text>
                    <View style={styles.dialogActions}>
                        <TouchableOpacity onPress={onDismiss} style={styles.dialogCancel}>
                            <Text style={styles.dialogCancelText}>取消</Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                            onPress={onConfirm}
                            style={[styles.dialogConfirm, danger && styles.dialogConfirmDanger]}
                        >
                            <Text style={styles.dialogConfirmText}>{confirmText}</Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </View>
        </Modal>
    )
}

const styles = StyleSheet.create({
    tag: {
        borderRadius: Radius.tag,
        paddingHorizontal: 8,
        paddingVertical: 3,
        alignSelf: 'flex-start'
    },
    tagText: {
        fontSize: FontSize.labelSmall,
        fontWeight: '600'
    },
    statCard: {
        flex: 1,
        backgroundColor: '#fff',
        borderRadius: Radius.card,
        padding: 14,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 2,
        elevation: 1
    },
    statIcon: {
        width: 32,
        height: 32,
        borderRadius: 16,
        alignItems: 'center',
        justifyContent: 'center'
    },
    statIconText: {
        fontSize: 16
    },
    statValue: {
        fontSize: FontSize.titleLarge,
        fontWeight: 'bold',
        color: TextPrimary,
        marginTop: 10
    },
    statLabel: {
        fontSize: FontSize.bodySmall,
        color: TextSecondary,
        marginTop: 2
    },
    statHint: {
        fontSize: FontSize.labelSmall,
        color: TextMuted,
        marginTop: 6
    },
    sectionCard: {
        backgroundColor: '#fff',
        borderRadius: Radius.card,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 2,
        elevation: 1
    },
    sectionHeader: {
        height: 52,
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 14
    },
    sectionTitle: {
        flex: 1,
        fontSize: FontSize.titleSmall,
        fontWeight: '600',
        color: TextPrimary
    },
    loadingBox: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center'
    },
    emptyBox: {
        alignItems: 'center',
        paddingVertical: 40
    },
    emptyText: {
        fontSize: FontSize.bodyMedium,
        color: TextSecondary
    },
    infoRow: {
        flexDirection: 'row',
        paddingVertical: 6
    },
    infoLabel: {
        width: 76,
        fontSize: FontSize.bodyMedium,
        color: TextSecondary
    },
    infoValue: {
        flex: 1,
        fontSize: FontSize.bodyMedium,
        color: TextPrimary
    },
    errorNote: {
        backgroundColor: ErrorRedBg,
        borderRadius: 10,
        padding: 12
    },
    errorNoteText: {
        fontSize: FontSize.bodySmall,
        color: ErrorRedText
    },
    permissionDenied: {
        alignItems: 'center',
        paddingHorizontal: 20,
        paddingVertical: 36
    },
    permissionCode: {
        fontSize: FontSize.headline,
        fontWeight: 'bold',
        color: ErrorRed
    },
    permissionText: {
        fontSize: FontSize.bodyMedium,
        color: TextPrimary,
        textAlign: 'center',
        marginTop: 8
    },
    permissionSub: {
        fontSize: FontSize.bodySmall,
        color: TextSecondary,
        textAlign: 'center',
        marginTop: 4
    },
    permissionCurrent: {
        fontSize: FontSize.labelSmall,
        color: TextMuted,
        textAlign: 'center',
        marginTop: 8
    },
    pageHeader: {
        backgroundColor: BrandTeal,
        paddingHorizontal: 16,
        paddingVertical: 18
    },
    pageHeaderTitle: {
        fontSize: FontSize.titleLarge,
        fontWeight: 'bold',
        color: '#fff'
    },
    pageHeaderSubtitle: {
        fontSize: FontSize.bodySmall,
        color: 'rgba(255,255,255,0.82)',
        marginTop: 3
    },
    dialogOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.4)',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24
    },
    dialogBox: {
        backgroundColor: '#fff',
        borderRadius: Radius.dialog,
        padding: 20,
        width: '100%',
        maxWidth: 400
    },
    dialogTitle: {
        fontSize: FontSize.titleMedium,
        fontWeight: 'bold',
        color: TextPrimary
    },
    dialogMessage: {
        fontSize: FontSize.bodyMedium,
        color: TextSecondary,
        marginTop: 10,
        lineHeight: 22
    },
    dialogActions: {
        flexDirection: 'row',
        justifyContent: 'flex-end',
        marginTop: 20,
        gap: 10
    },
    dialogCancel: {
        paddingHorizontal: 16,
        paddingVertical: 10,
        borderRadius: Radius.button
    },
    dialogCancelText: {
        fontSize: FontSize.labelLarge,
        color: TextSecondary
    },
    dialogConfirm: {
        paddingHorizontal: 16,
        paddingVertical: 10,
        borderRadius: Radius.button,
        backgroundColor: BrandTeal
    },
    dialogConfirmDanger: {
        backgroundColor: ErrorRed
    },
    dialogConfirmText: {
        fontSize: FontSize.labelLarge,
        color: '#fff',
        fontWeight: '600'
    }
})
