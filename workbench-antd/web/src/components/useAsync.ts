import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../lib/api'

/**
 * 页面数据加载的通用钩子。
 *
 * 三个状态分开存（`data` / `error` / `loading`），因为**「空」和「错」和「没权限」
 * 在页面上要显示完全不同的东西**：空是 EmptyBox，错是 ErrorBlock + 重试按钮，
 * 403 是 PermissionDenied。合成一个 status 字符串很容易在某次改动里把 403
 * 显示成「加载失败」。
 */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [loading, setLoading] = useState(true)
  const seq = useRef(0)

  const run = useCallback(async () => {
    const my = ++seq.current
    setLoading(true)
    try {
      const value = await loader()
      // 竞态保护：慢的旧请求回来时不能覆盖新数据
      if (seq.current === my) {
        setData(value)
        setError(null)
      }
    } catch (e) {
      if (seq.current === my) setError(e as Error)
    } finally {
      if (seq.current === my) setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    void run()
  }, [run])

  return { data, error, loading, reload: run, setData }
}

/** 403 判定：单独提出来，页面里到处都是「403 就降级」。 */
export const isForbidden = (e: unknown) => e instanceof ApiError && e.httpCode === 403

/** 面向用户的中文错误文案。技术异常（网络断、JSON 解析失败）也要能看懂。 */
export function errorText(e: unknown): string {
  // 超时（httpCode 0）与 403/500 要能一眼分开：超时是"再等等/换个地址"，
  // 403 是"这个账号看不到"，提示语不该混
  if (e instanceof ApiError) return e.message
  if (e instanceof TypeError) return '连不上服务器，请检查网络或服务地址'
  if (e instanceof Error && e.message) return e.message
  return '出了点问题，稍后再试'
}