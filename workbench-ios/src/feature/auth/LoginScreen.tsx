/**
 * 登录页——从 workbench-android 的 LoginScreen.kt 移植。
 *
 * 账号密码**不预填**——值留在 state 里等于把口令摊在 UI 层，截图/无障碍都能读到。
 * 下方只放一张只读的「演示账号」提示卡，点开要自己敲。
 *
 * 服务地址选择器放在登录按钮之前——先决定「往哪台服务器登录」，再决定「用谁的身份登录」。
 */

import React, { useEffect, useState } from 'react'
import {
    ActivityIndicator,
    Modal,
    ScrollView,
    StyleSheet,
    Text,
    TextInput,
    TouchableOpacity,
    View
} from 'react-native'
import { useAuth } from './AuthContext'
import { ErrorNote } from '../../ui/Components'
import { BrandTeal, FontSize, Radius, TextMuted, TextPrimary, TextSecondary } from '../../ui/theme'
import { addServer, getServers, getSelectedServer, loadNow, normalize, selectServer } from '../../store/servers'

export function LoginScreen() {
    const { login } = useAuth()
    const [account, setAccount] = useState('')
    const [password, setPassword] = useState('')
    const [passwordVisible, setPasswordVisible] = useState(false)
    const [loading, setLoading] = useState(false)
    const [globalError, setGlobalError] = useState<string | null>(null)
    const [accountError, setAccountError] = useState<string | null>(null)
    const [passwordError, setPasswordError] = useState<string | null>(null)

    const [servers, setServers] = useState<string[]>(getServers())
    const [selected, setSelected] = useState(getSelectedServer())
    const [menuOpen, setMenuOpen] = useState(false)
    const [showAdd, setShowAdd] = useState(false)

    // 冷启动读一次服务器配置：等存储水合完成再同步到组件状态
    // （同步 setState 必须排在 await 之后，避免 effect 级联渲染）
    useEffect(() => {
        let cancelled = false
        void loadNow().then(() => {
            if (cancelled) return
            setServers(getServers())
            setSelected(getSelectedServer())
        })
        return () => {
            cancelled = true
        }
    }, [])

    const doLogin = async () => {
        if (loading) return
        const acc = account.trim()
        const accErr = acc ? null : '请输入手机号或账号'
        const pwdErr = password ? null : '请输入密码'
        setAccountError(accErr)
        setPasswordError(pwdErr)
        if (accErr || pwdErr) return

        setLoading(true)
        setGlobalError(null)
        try {
            await login(acc, password)
            // 成功后清空表单，避免下次进登录页还留着上一个账号
            setAccount('')
            setPassword('')
        } catch (e) {
            // 后端对"账号不存在"和"密码错误"返回同一条文案，原样透出
            setGlobalError(e instanceof Error ? e.message : '登录失败')
        } finally {
            setLoading(false)
        }
    }

    return (
        <ScrollView style={styles.root} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {/* 渐变头图 */}
            <View style={styles.hero}>
                <View style={styles.heroIcon}>
                    <Text style={styles.heroIconText}>📈</Text>
                </View>
                <Text style={styles.heroTitle}>个人工作台</Text>
                <Text style={styles.heroSub}>今天该做什么，一眼看清</Text>
            </View>

            <View style={styles.form}>
                <Text style={styles.formTitle}>登录</Text>

                {globalError ? <ErrorNote text={globalError} /> : null}

                <View style={styles.field}>
                    <Text style={styles.fieldLabel}>手机号 / 账号</Text>
                    <TextInput
                        value={account}
                        onChangeText={(t) => {
                            setAccount(t)
                            setAccountError(null)
                            setGlobalError(null)
                        }}
                        placeholder="手机号 / 账号"
                        placeholderTextColor={TextMuted}
                        autoCapitalize="none"
                        autoCorrect={false}
                        style={[styles.input, accountError ? styles.inputError : null]}
                    />
                    {accountError ? <Text style={styles.fieldError}>{accountError}</Text> : null}
                </View>

                <View style={styles.field}>
                    <Text style={styles.fieldLabel}>密码</Text>
                    <View style={[styles.input, styles.passwordRow, passwordError ? styles.inputError : null]}>
                        <TextInput
                            value={password}
                            onChangeText={(t) => {
                                setPassword(t)
                                setPasswordError(null)
                                setGlobalError(null)
                            }}
                            placeholder="密码"
                            placeholderTextColor={TextMuted}
                            secureTextEntry={!passwordVisible}
                            autoCapitalize="none"
                            autoCorrect={false}
                            style={styles.passwordInput}
                            onSubmitEditing={doLogin}
                            returnKeyType="done"
                        />
                        <TouchableOpacity onPress={() => setPasswordVisible(!passwordVisible)} style={styles.eyeBtn}>
                            <Text style={styles.eyeText}>{passwordVisible ? '🙈' : '👁'}</Text>
                        </TouchableOpacity>
                    </View>
                    {passwordError ? <Text style={styles.fieldError}>{passwordError}</Text> : null}
                </View>

                {/* 服务地址选择器 */}
                <ServerSelector
                    servers={servers}
                    selected={selected}
                    menuOpen={menuOpen}
                    onToggle={() => setMenuOpen(!menuOpen)}
                    onSelect={async (url) => {
                        setMenuOpen(false)
                        await selectServer(url)
                        setSelected(getSelectedServer())
                    }}
                    onAdd={() => {
                        setMenuOpen(false)
                        setShowAdd(true)
                    }}
                />

                <TouchableOpacity
                    onPress={doLogin}
                    disabled={loading}
                    style={[styles.loginBtn, loading && styles.loginBtnDisabled]}
                >
                    {loading ? (
                        <ActivityIndicator color="#fff" />
                    ) : (
                        <Text style={styles.loginBtnText}>登 录</Text>
                    )}
                </TouchableOpacity>

                <View style={styles.demoCard}>
                    <Text style={styles.demoTitle}>演示账号</Text>
                    <Text style={styles.demoText}>13800000006 / DEMO_PASSWORD</Text>
                </View>
            </View>

        {/* 条件挂载：每次打开都是全新实例，输入与报错天然复位 */}
        {showAdd ? (
            <AddServerDialog
                visible
                onDismiss={() => setShowAdd(false)}
                onConfirm={async (raw) => {
                    const normalized = await addServer(raw)
                    if (normalized) {
                        setServers(getServers())
                        setSelected(getSelectedServer())
                        setShowAdd(false)
                    }
                    return normalized
                }}
            />
        ) : null}
    </ScrollView>
    )
}

// ---------------- 服务地址选择器 ----------------

function ServerSelector({
    servers,
    selected,
    menuOpen,
    onToggle,
    onSelect,
    onAdd
}: {
    servers: string[]
    selected: string
    menuOpen: boolean
    onToggle: () => void
    onSelect: (url: string) => void
    onAdd: () => void
}) {
    return (
        <View style={styles.field}>
            <Text style={styles.fieldLabel}>服务地址</Text>
            <TouchableOpacity onPress={onToggle} style={[styles.input, styles.selector]}>
                <Text style={styles.selectorText} numberOfLines={1}>
                    {selected}
                </Text>
                <Text style={styles.selectorArrow}>{menuOpen ? '▲' : '▼'}</Text>
            </TouchableOpacity>
            {menuOpen ? (
                <View style={styles.menu}>
                    {servers.map((url) => (
                        <TouchableOpacity
                            key={url}
                            onPress={() => onSelect(url)}
                            style={styles.menuItem}
                        >
                            <Text style={[styles.menuItemText, url === selected && styles.menuItemActive]} numberOfLines={1}>
                                {url}
                            </Text>
                            {url === selected ? <Text style={styles.menuCheck}>✓</Text> : null}
                        </TouchableOpacity>
                    ))}
                    <View style={styles.menuDivider} />
                    <TouchableOpacity onPress={onAdd} style={styles.menuItem}>
                        <Text style={styles.menuAdd}>＋ 添加服务器…</Text>
                    </TouchableOpacity>
                </View>
            ) : null}
        </View>
    )
}

// ---------------- 新增服务器弹窗 ----------------

function AddServerDialog({
    visible,
    onDismiss,
    onConfirm
}: {
    visible: boolean
    onDismiss: () => void
    onConfirm: (raw: string) => Promise<string | null>
}) {
    const [input, setInput] = useState('')
    const [error, setError] = useState<string | null>(null)

    const submit = async () => {
        const normalized = normalize(input)
        if (!normalized) {
            setError('地址格式不正确，例如 1.2.3.4:8080')
            return
        }
        const result = await onConfirm(input)
        if (!result) {
            setError('地址格式不正确，例如 1.2.3.4:8080')
        }
    }

    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onDismiss}>
            <View style={styles.dialogOverlay}>
                <View style={styles.dialogBox}>
                    <Text style={styles.dialogTitle}>添加服务器</Text>
                    <TextInput
                        value={input}
                        onChangeText={(t) => {
                            setInput(t)
                            setError(null)
                        }}
                        placeholder="1.2.3.4:8080"
                        placeholderTextColor={TextMuted}
                        autoCapitalize="none"
                        autoCorrect={false}
                        keyboardType="url"
                        style={[styles.input, error ? styles.inputError : null]}
                    />
                    <Text style={styles.dialogHint}>{error ?? '只填到端口即可；不写协议默认 http://'}</Text>
                    <View style={styles.dialogActions}>
                        <TouchableOpacity onPress={onDismiss} style={styles.dialogCancel}>
                            <Text style={styles.dialogCancelText}>取消</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={submit} style={styles.dialogConfirm}>
                            <Text style={styles.dialogConfirmText}>添加</Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </View>
        </Modal>
    )
}

const styles = StyleSheet.create({
    root: {
        flex: 1,
        backgroundColor: '#fff'
    },
    content: {
        flexGrow: 1
    },
    hero: {
        backgroundColor: BrandTeal,
        paddingVertical: 48,
        alignItems: 'center'
    },
    heroIcon: {
        width: 64,
        height: 64,
        borderRadius: 32,
        backgroundColor: 'rgba(255,255,255,0.18)',
        alignItems: 'center',
        justifyContent: 'center'
    },
    heroIconText: {
        fontSize: 30
    },
    heroTitle: {
        fontSize: FontSize.headline,
        fontWeight: 'bold',
        color: '#fff',
        marginTop: 16
    },
    heroSub: {
        fontSize: FontSize.bodyMedium,
        color: 'rgba(255,255,255,0.82)',
        marginTop: 6
    },
    form: {
        paddingHorizontal: 24,
        paddingTop: 32,
        paddingBottom: 32,
        gap: 16
    },
    formTitle: {
        fontSize: FontSize.titleLarge,
        fontWeight: 'bold',
        color: TextPrimary
    },
    field: {
        gap: 6
    },
    fieldLabel: {
        fontSize: FontSize.labelMedium,
        color: TextSecondary,
        fontWeight: '500'
    },
    input: {
        borderWidth: 1,
        borderColor: '#EAECF0',
        borderRadius: Radius.button,
        paddingHorizontal: 14,
        paddingVertical: 12,
        fontSize: FontSize.bodyMedium,
        color: TextPrimary,
        backgroundColor: '#fff'
    },
    inputError: {
        borderColor: '#D92D20'
    },
    fieldError: {
        fontSize: FontSize.labelSmall,
        color: '#D92D20'
    },
    passwordRow: {
        flexDirection: 'row',
        alignItems: 'center'
    },
    passwordInput: {
        flex: 1,
        borderWidth: 0
    },
    eyeBtn: {
        padding: 4
    },
    eyeText: {
        fontSize: 16
    },
    selector: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between'
    },
    selectorText: {
        flex: 1,
        fontSize: FontSize.bodyMedium,
        color: TextPrimary
    },
    selectorArrow: {
        fontSize: 12,
        color: TextMuted
    },
    menu: {
        borderWidth: 1,
        borderColor: '#EAECF0',
        borderRadius: Radius.button,
        backgroundColor: '#fff',
        marginTop: 4,
        overflow: 'hidden'
    },
    menuItem: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 14,
        paddingVertical: 12
    },
    menuItemText: {
        flex: 1,
        fontSize: FontSize.bodyMedium,
        color: TextPrimary
    },
    menuItemActive: {
        color: BrandTeal,
        fontWeight: '600'
    },
    menuCheck: {
        color: BrandTeal,
        marginLeft: 8
    },
    menuDivider: {
        height: 1,
        backgroundColor: '#EAECF0'
    },
    menuAdd: {
        fontSize: FontSize.bodyMedium,
        color: BrandTeal
    },
    loginBtn: {
        height: 50,
        borderRadius: Radius.button,
        backgroundColor: BrandTeal,
        alignItems: 'center',
        justifyContent: 'center'
    },
    loginBtnDisabled: {
        opacity: 0.6
    },
    loginBtnText: {
        fontSize: FontSize.labelLarge,
        color: '#fff',
        fontWeight: '600'
    },
    demoCard: {
        backgroundColor: '#F8F9FC',
        borderRadius: 10,
        padding: 12
    },
    demoTitle: {
        fontSize: FontSize.labelMedium,
        fontWeight: '600',
        color: TextPrimary
    },
    demoText: {
        fontSize: FontSize.bodySmall,
        color: TextSecondary,
        marginTop: 4
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
    dialogHint: {
        fontSize: FontSize.labelSmall,
        color: TextMuted,
        marginTop: 6
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
    dialogConfirmText: {
        fontSize: FontSize.labelLarge,
        color: '#fff',
        fontWeight: '600'
    }
})
