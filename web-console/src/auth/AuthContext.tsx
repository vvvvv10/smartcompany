import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, tokenStore } from '../api/client'
import { PluginManifest, Profile, TokenPair } from '../api/types'

interface AuthValue {
    profile: Profile | null
    loading: boolean
    roles: string[]
    isAdmin: boolean
    /** 权限点集合 */
    permissions: string[]
    /** 各能力服务的插件描述（菜单 + 权限开关），侧栏据此渲染非基础功能 */
    plugins: PluginManifest[]
    /** 是否拥有某个权限点（ADMIN 兜底由后端保证，这里只看返回值） */
    hasPermission: (code: string) => boolean
    login: (account: string, password: string) => Promise<void>
    register: (payload: RegisterPayload) => Promise<void>
    logout: () => Promise<void>
    reload: () => Promise<void>
}

export interface RegisterPayload {
    phone: string
    password: string
    captcha: string
    /** 花名：全局唯一，必填 */
    nickname: string
}

const AuthContext = createContext<AuthValue>(undefined as unknown as AuthValue)

export function AuthProvider({ children }: { children: ReactNode }) {
    const [profile, setProfile] = useState<Profile | null>(null)
    const [loading, setLoading] = useState(true)
    const [plugins, setPlugins] = useState<PluginManifest[]>([])

    const reload = useCallback(async () => {
        if (!tokenStore.access) {
            setProfile(null)
            setLoading(false)
            return
        }
        try {
            const res = await api.get<Profile>('/users/me')
            setProfile(res.data)
        } catch {
            tokenStore.clear()
            setProfile(null)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        let alive = true
        if (!profile) {
            setPlugins([])
            return
        }
        // 每个服务一个 /api/<svc>/plugin.json；除 user-center 外都是可插拔能力。
        // 有一个服务没装/停机，Promise.allSettled 兜住，只是少它那块菜单。
        const base = ['oms', 'wms', 'tms', 'crm']
        Promise.allSettled(base.map((s) => api.get<PluginManifest>(`/${s}/plugin.json`)))
            .then((results) => {
                if (!alive) return
                const ok: PluginManifest[] = []
                results.forEach((r) => {
                    if (r.status === 'fulfilled' && r.value?.data?.menus) {
                        ok.push(r.value.data)
                    }
                })
                setPlugins(ok)
            })
            .catch(() => setPlugins([]))
        return () => {
            alive = false
        }
    }, [profile])

    useEffect(() => {
        reload()
        // 花名可能被管理员在别处改掉（审批通过），而侧栏 / 顶栏 / 首页都认这份 profile。
        // 不主动拉新的话，本人改完花名要等刷新页面或重进「我的身份」才看得到新名字——
        // 看起来就像「审批没生效」。所以：窗口重新聚焦时立刻拉一次（覆盖切标签页），
        // 外加一个慢轮询兜底（审批也可能发生在同一个标签页里，聚焦事件不会触发）。
        const onFocus = () => reload()
        window.addEventListener('focus', onFocus)
        const timer = setInterval(reload, 15_000)
        return () => {
            window.removeEventListener('focus', onFocus)
            clearInterval(timer)
        }
    }, [reload])

    const login = useCallback(
        async (account: string, password: string) => {
            const res = await api.post<TokenPair>('/auth/login', { account, password })
            tokenStore.set(res.data.accessToken, res.data.refreshToken)
            await reload()
        },
        [reload]
    )

    const register = useCallback(
        async (payload: RegisterPayload) => {
            const res = await api.post<TokenPair>('/auth/register', payload)
            tokenStore.set(res.data.accessToken, res.data.refreshToken)
            await reload()
        },
        [reload]
    )

    const logout = useCallback(async () => {
        try {
            await api.post('/auth/logout', { refreshToken: tokenStore.refresh })
        } catch {
            // 令牌可能已过期，本地状态照清
        }
        tokenStore.clear()
        setProfile(null)
    }, [])

    const roles = profile?.gatewayRoles ?? []
    const permissions = profile?.permissionCodes ?? []
    const permissionSet = useMemo(() => new Set(permissions), [permissions])
    const hasPermission = useCallback((code: string) => permissionSet.has(code), [permissionSet])
    const value = useMemo(
        () => ({
            profile,
            loading,
            roles,
            isAdmin: roles.includes('ADMIN'),
            permissions,
            plugins,
            hasPermission,
            login,
            register,
            logout,
            reload
        }),
        [profile, loading, roles, permissions, plugins, hasPermission, login, register, logout, reload]
    )

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthValue {
    return useContext(AuthContext)
}
