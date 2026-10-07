/**
 * 认证状态——从 workbench-android 的 AuthState + AuthViewModel 移植。
 *
 * 用一个 React state 承载"是否已登录"，导航层据此决定进登录页还是主页——
 * 这样刷新失败被踢下线时 UI 会自动退回登录页，不用每个页面自己判 token。
 *
 * 花名可能在别处被改（管理员批准了申请），所以 profile 支持外部刷新：
 * 窗口聚焦时拉一次，外加 15s 轮询兜底（与 web 端 AuthContext 同一策略）。
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { api, ApiException } from '../../api/client'
import type { Profile } from '../../api/types'
import { bindSessionInvalidated, clear, getAccessToken, getRefreshToken, isLoggedIn, saveTokens } from '../../store/session'

type Status = 'unknown' | 'loggedIn' | 'loggedOut'

interface AuthValue {
    status: Status
    profile: Profile | null
    login: (account: string, password: string) => Promise<void>
    logout: () => Promise<void>
    refreshProfile: () => Promise<void>
    hasPermission: (code: string) => boolean
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
    const [status, setStatus] = useState<Status>('unknown')
    const [profile, setProfile] = useState<Profile | null>(null)
    const profileRef = useRef<Profile | null>(null)
    // ref 同步只能放在 effect 里——渲染期写 ref 会破坏并发渲染的可中断性
    useEffect(() => {
        profileRef.current = profile
    }, [profile])

    const refreshProfile = useCallback(async () => {
        if (!(await getAccessToken())) return
        try {
            const p = await api.get<Profile>('/api/users/me')
            setProfile(p)
        } catch (e: unknown) {
            if (e instanceof ApiException && e.isAuthFailure) {
                await clear()
                setStatus('loggedOut')
                setProfile(null)
            }
        }
    }, [])

    // 冷启动：读本地 token 判定登录态
    useEffect(() => {
        let cancelled = false
        ;(async () => {
            const loggedIn = await isLoggedIn()
            if (cancelled) return
            if (loggedIn) {
                // 拿不到资料不代表没登录（可能只是网络抖动），仍进主页
                try {
                    const p = await api.get<Profile>('/api/users/me')
                    if (!cancelled) {
                        setProfile(p)
                        setStatus('loggedIn')
                    }
                } catch {
                    if (!cancelled) setStatus('loggedIn')
                }
            } else {
                if (!cancelled) setStatus('loggedOut')
            }
        })()
        return () => {
            cancelled = true
        }
    }, [])

    // 会话失效通知：刷新失败时退回登录页
    useEffect(() => {
        bindSessionInvalidated(() => {
            setProfile(null)
            setStatus('loggedOut')
        })
    }, [])

    // 花名可能被管理员在别处改掉，侧栏/顶栏/首页都认这份 profile。
    // 15s 轮询兜底（与 web 端同一策略）。React Native 没有 window 聚焦事件，
    // 所以这里只用轮询——审批发生在同一个标签页里时，聚焦事件也不会触发。
    useEffect(() => {
        const timer = setInterval(() => void refreshProfile(), 15_000)
        return () => clearInterval(timer)
    }, [refreshProfile])

    const login = useCallback(async (account: string, password: string) => {
        const tokens = await api.post<{ accessToken: string; refreshToken: string }>('/api/auth/login', {
            account: account.trim(),
            password
        })
        await saveTokens(tokens.accessToken, tokens.refreshToken)
        const p = await api.get<Profile>('/api/users/me')
        setProfile(p)
        setStatus('loggedIn')
    }, [])

    const logout = useCallback(async () => {
        const refresh = await getRefreshToken()
        // 吊销失败无所谓，本地一定要清
        try {
            await api.post('/api/auth/logout', { refreshToken: refresh })
        } catch {
            // 忽略
        }
        await clear()
        setProfile(null)
        setStatus('loggedOut')
    }, [])

    const hasPermission = useCallback(
        (code: string) => (profileRef.current?.permissionCodes ?? []).includes(code),
        []
    )

    const value = useMemo(
        () => ({ status, profile, login, logout, refreshProfile, hasPermission }),
        [status, profile, login, logout, refreshProfile, hasPermission]
    )

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthValue {
    const ctx = useContext(AuthContext)
    if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
    return ctx
}
