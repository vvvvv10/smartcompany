import { beforeEach, describe, expect, it } from 'vitest'

/**
 * api 层的会话行为测试（用假 fetch，不碰网络）。
 *
 * 这里锁的是实测踩到过的坑：**accessToken 过期时多个请求同时 401，
 * 如果各自去刷 refresh，一次性轮换的 refreshToken 会被作废两次，
 * 用户直接被踢回登录页**。所以刷新必须是 single-flight。
 */

// api 模块在 import 时就读 localStorage（服务地址），先把它装好
const store = new Map<string, string>()
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
    clear: () => store.clear(),
  },
})

let refreshCalls = 0
let seenAuth: (string | null)[] = []

/** 假后端：Bearer good 放行，其它一律 401；刷新会换一对新 token。 */
function fakeFetch() {
  return async (url: string, init?: RequestInit) => {
    const headers = (init?.headers || {}) as Record<string, string>
    const auth = headers.Authorization ?? null
    seenAuth.push(auth)
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json' },
      })

    if (url.includes('/api/auth/refresh')) {
      refreshCalls += 1
      const sent = JSON.parse(String(init?.body || '{}'))
      // 一次性轮换：同一个 refreshToken 只能换一次，第二次（bad/已用过）都失败
      if (sent.refreshToken === 'bad' || refreshCalls > 1) {
        return json(401, { message: 'refresh token 已失效' })
      }
      return json(200, { accessToken: 'good', refreshToken: 'good2', expiresIn: 3600 })
    }

    if (auth !== 'Bearer good') return json(401, { message: 'token 过期' })
    if (url.includes('/api/users/me/approvals')) {
      return json(200, [{ id: 1 }])
    }
    if (url.includes('/api/users/permissions/my')) {
      return json(200, { effective: [], grants: [], revokes: [], requests: [] })
    }
    return json(200, {})
  }
}

const { api, setTokens, getAccessToken } = await import('../lib/api')

beforeEach(() => {
  store.clear()
  refreshCalls = 0
  seenAuth = []
  ;(globalThis as unknown as { fetch: unknown }).fetch = fakeFetch()
  // 登录后拿到的 token 立刻过期：模拟「用了半小时后 accessToken 到期」
  setTokens({ accessToken: 'expired', refreshToken: 'r1' })
})

describe('token 刷新', () => {
  it('并发 401 只刷新一次，所有请求都重放成功', async () => {
    const [mine, perms] = await Promise.all([api.myApprovals(), api.myPermissions()])

    expect(refreshCalls).toBe(1)
    expect(mine).toHaveLength(1)
    expect(perms.requests).toHaveLength(0)
    // 刷新后的 accessToken 已经写回本地
    expect(getAccessToken()).toBe('good')
    // 两个请求都先用旧 token 失败、再用新 token 成功
    expect(seenAuth.filter((a) => a === 'Bearer expired')).toHaveLength(2)
    expect(seenAuth.filter((a) => a === 'Bearer good')).toHaveLength(2)
    // 刷新请求本身不带 Authorization（refreshToken 在 body 里）
    expect(seenAuth.filter((a) => a === null)).toHaveLength(1)
  })

  it('刷新令牌也失效时才清会话（只重试一次，不死循环）', async () => {
    setTokens({ accessToken: 'expired', refreshToken: 'bad' })
    await expect(api.myApprovals()).rejects.toThrow()
    expect(refreshCalls).toBe(1)
    expect(getAccessToken()).toBe('')
  })
})