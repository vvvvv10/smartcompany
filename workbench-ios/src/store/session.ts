/**
 * 登录态持久化——从 workbench-android 的 SessionStore.kt 移植。
 *
 * 用 AsyncStorage 存 access / refresh 两个 token，并维护一份内存缓存：
 * API 拦截器在每次请求时都要读 token，走 AsyncStorage 的异步读会把请求卡在 IO 上。
 * 所以只有冷启动第一次会真正读盘，之后一律走内存读。
 *
 * 同时维护一个「会话作废」通知：刷新令牌也失效时，只是把本地态清掉，
 * **没有任何人通知 UI**，用户会一直卡在主页满屏报错、又没有入口重新登录。
 * 由 AuthContext 在启动时挂上，回调可能来自网络层，所以用 useState 保证触发重渲染。
 */

import AsyncStorage from '@react-native-async-storage/async-storage'

const ACCESS_KEY = 'access_token'
const REFRESH_KEY = 'refresh_token'

let cachedAccess: string | null = null
let cachedRefresh: string | null = null
let loaded = false

/** 会话作废通知。与安卓端 `SessionStore.onSessionInvalidated` 同一职责。 */
let onSessionInvalidated: (() => void) | null = null

export function bindSessionInvalidated(cb: () => void) {
    onSessionInvalidated = cb
}

/** 冷启动兜底读盘：只发生一次，之后 loaded 置位。 */
async function ensureLoaded() {
    if (loaded) return
    try {
        const [a, r] = await Promise.all([
            AsyncStorage.getItem(ACCESS_KEY),
            AsyncStorage.getItem(REFRESH_KEY)
        ])
        cachedAccess = a
        cachedRefresh = r
    } catch {
        // 读不到就当没登录，不阻断启动
    }
    loaded = true
}

/** 同步读，供 API 客户端用；未加载时先触发一次异步加载（不 await）。 */
export function accessNow(): string | null {
    if (!loaded) void ensureLoaded()
    return cachedAccess
}

export async function getAccessToken(): Promise<string | null> {
    await ensureLoaded()
    return cachedAccess
}

export async function getRefreshToken(): Promise<string | null> {
    await ensureLoaded()
    return cachedRefresh
}

export async function isLoggedIn(): Promise<boolean> {
    const t = await getAccessToken()
    return !!t
}

export async function saveTokens(access: string, refresh: string) {
    cachedAccess = access
    cachedRefresh = refresh
    loaded = true
    await AsyncStorage.multiSet([
        [ACCESS_KEY, access],
        [REFRESH_KEY, refresh]
    ])
}

export async function clear() {
    cachedAccess = null
    cachedRefresh = null
    loaded = true
    await AsyncStorage.multiRemove([ACCESS_KEY, REFRESH_KEY])
    // 通知 UI 退回登录页。放在这里而不是各调用点：刷新失败那条路径藏在网络层，
    // 调用方根本拿不到信号。
    onSessionInvalidated?.()
}
