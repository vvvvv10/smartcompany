/**
 * API 客户端——从 workbench-android 的 Http.kt + AuthInterceptor.kt + BaseUrlInterceptor.kt 移植。
 *
 * 三个由线上实测决定、不能想当然的点：
 *  - 写操作（POST/PUT/PATCH）返回 **200 + 完整对象**，不是 201
 *  - **凡是可能 4xx 的接口一律当错误处理**：后端的 `{code,message}` 要从 errorBody 里
 *    解出来，直接声明返回 T 时那句中文提示会被兜成 `HTTP 403 Forbidden`，文案全丢
 *  - 服务地址在**运行时**可切换，不必重建实例——换地址时早先建好的拦截链还指着旧的
 *    repository，于是"选了新地址、登录请求却还发给旧地址"
 *
 * 两个必须处理的坑（与安卓端一致）：
 *  1. **并发刷新**：列表页同时发 5 个请求全部 401，就会连发 5 次 refresh，而 refresh
 *     令牌是"用一次就轮换"的，后 4 次必然失败 → 被迫登出。用 Promise 合并成只刷一次。
 *  2. **递归**：刷新请求本身绝不能带 auth 头（它 401 了会再触发刷新）。
 */

import { accessNow, getRefreshToken, saveTokens, clear } from '../store/session'
import { getSelectedServer } from '../store/servers'

/** 把后端的 `{code, message}` 错误体和网络异常统一成一种异常。 */
export class ApiException extends Error {
    code: string
    httpCode: number

    constructor(code: string, message: string, httpCode = 0) {
        super(message)
        this.name = 'ApiException'
        this.code = code
        this.httpCode = httpCode
    }

    get isAuthFailure() {
        return this.httpCode === 401
    }
}

/** 这些路径 401 是"账号密码错了/刷新令牌本身失效"，不能再去刷新。 */
const AUTH_PATHS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout', '/api/auth/register']

function isAuthPath(path: string) {
    return AUTH_PATHS.some((p) => path.startsWith(p))
}

async function parseError(res: Response): Promise<ApiException> {
    try {
        const body = await res.json()
        if (body && body.message) {
            return new ApiException(body.code || `http_${res.status}`, body.message, res.status)
        }
    } catch {
        // 解不出就退化成状态码文案
    }
    return new ApiException(`http_${res.status}`, `服务返回异常（${res.status}）`, res.status)
}

/** 并发刷新合并：同一批 401 只真正打一次 /auth/refresh。 */
let refreshPromise: Promise<string | null> | null = null

function doRefresh(): Promise<string | null> {
    return (async () => {
        const refresh = await getRefreshToken()
        if (!refresh) return null
        try {
            const base = getSelectedServer()
            const res = await fetch(`${base}/api/auth/refresh`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken: refresh })
            })
            if (!res.ok) return null
            const tokens = await res.json()
            await saveTokens(tokens.accessToken, tokens.refreshToken)
            return tokens.accessToken
        } catch {
            return null
        }
    })()
}

function refreshToken(): Promise<string | null> {
    if (!refreshPromise) {
        refreshPromise = doRefresh().finally(() => {
            refreshPromise = null
        })
    }
    return refreshPromise
}

async function request<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
    const base = getSelectedServer()
    const url = `${base}${path}`
    const token = accessNow()
    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...((options.headers as Record<string, string>) ?? {})
    }
    if (token) headers['Authorization'] = `Bearer ${token}`

    const res = await fetch(url, { ...options, headers })

    if (res.status === 401 && retry && !isAuthPath(path)) {
        const newToken = await refreshToken()
        if (newToken) {
            return request<T>(path, options, false)
        }
        // 刷新也失败了 → 会话彻底失效，清掉本地态并通知 UI
        await clear()
        throw new ApiException('unauthorized', '登录已过期，请重新登录', 401)
    }

    if (!res.ok) {
        throw await parseError(res)
    }

    // 204 或空体
    const text = await res.text()
    if (!text) return null as T
    return JSON.parse(text) as T
}

/**
 * **移动端只读**：`post` 只留给会话类接口（登录/登出/刷新），业务数据一律不给写入口。
 * 管理台的修改类操作（审批、提交申请、增删改）不下发到手机端。
 */
export const api = {
    get<T>(path: string): Promise<T> {
        return request<T>(path)
    },
    post<T>(path: string, body?: unknown): Promise<T> {
        return request<T>(path, {
            method: 'POST',
            body: body !== undefined ? JSON.stringify(body) : undefined
        })
    }
}

/** 给 UI 用的可读文案。 */
export function displayMessage(e: unknown): string {
    if (e instanceof ApiException) return e.message
    if (e instanceof Error) return e.message || '操作失败'
    return '操作失败'
}
