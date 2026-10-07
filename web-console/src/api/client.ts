import axios, { AxiosInstance } from 'axios'
import { TokenPair } from './types'

const ACCESS_KEY = 'uc.accessToken'
const REFRESH_KEY = 'uc.refreshToken'

export const tokenStore = {
    get access() {
        return localStorage.getItem(ACCESS_KEY)
    },
    get refresh() {
        return localStorage.getItem(REFRESH_KEY)
    },
    set(access: string, refresh: string) {
        localStorage.setItem(ACCESS_KEY, access)
        localStorage.setItem(REFRESH_KEY, refresh)
    },
    clear() {
        localStorage.removeItem(ACCESS_KEY)
        localStorage.removeItem(REFRESH_KEY)
    }
}

export const api: AxiosInstance = axios.create({
    baseURL: '/api',
    timeout: 15000
})

/** 取后端统一错误结构 {code, message} 里的 message，拿不到就用兜底文案 */
export function errorMessage(err: unknown, fallback = '请求失败'): string {
    const anyErr = err as { response?: { data?: { message?: string } } }
    if (anyErr?.response?.data?.message) {
        return anyErr.response.data.message
    }
    return (err as Error)?.message || fallback
}

api.interceptors.request.use((config) => {
    const token = tokenStore.access
    if (token) {
        config.headers = config.headers || {}
        config.headers.Authorization = `Bearer ${token}`
    }
    return config
})

// 认证接口自身 401 不能再触发刷新，否则会有互相打转的风险
const isAuthEndpoint = (url?: string) => !!url && url.includes('/auth/')

let inflight: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
    if (!inflight) {
        inflight = axios
            .post<TokenPair>('/api/auth/refresh', { refreshToken: tokenStore.refresh })
            .then((res) => {
                tokenStore.set(res.data.accessToken, res.data.refreshToken)
                return res.data.accessToken
            })
            .finally(() => {
                inflight = null
            })
    }
    return inflight
}

api.interceptors.response.use(
    (response) => response,
    async (error) => {
        const original = error.config
        const status = error.response?.status
        const code = error.response?.data?.code

        const retriable =
            status === 401 &&
            original &&
            !original._retry &&
            !isAuthEndpoint(original.url) &&
            tokenStore.refresh &&
            code !== 'refresh_invalid'

        if (retriable) {
            original._retry = true
            try {
                const token = await refreshAccessToken()
                original.headers = original.headers || {}
                original.headers.Authorization = `Bearer ${token}`
                return api(original)
            } catch (refreshError) {
                tokenStore.clear()
                return Promise.reject(refreshError)
            }
        }

        // 令牌彻底失效（被登出/吊销）时把本地状态一起清掉，避免停留在"看起来已登录"的界面
        if (status === 401 && code === 'token_revoked') {
            tokenStore.clear()
        }
        return Promise.reject(error)
    }
)
