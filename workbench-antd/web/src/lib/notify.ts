import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

/**
 * 待办提醒（前台服务）的 Web 侧桥。
 *
 * **为什么要有这一层，而不是在 App 里直接轮询**：Android 会在切后台后节流甚至暂停
 * WebView 里的定时器，纯前端轮询在后台等于没有。所以后台提醒由 Android 前台服务
 * （`ApprovalsWatchService`）负责，Web 只做两件事：
 *
 *  1. **把登录凭据喂给服务** —— 服务读不到 WebView 的 localStorage，这是壳架构的硬约束。
 *     登录后 `start`、token 刷新后 `updateToken`、退出时 `stop`。
 *  2. **接住通知点击的落点** —— 点通知 → 跳审批页并高亮那条单子。
 *
 * 三种失败都**静默降级**：服务起不来（Android 12+ 后台启动限制）、用户没给通知权限、
 * 插件不可用（浏览器里开发）——这些都不该让 App 报错，用户该看到的待办在页面上仍然有。
 */

interface NotifyNative {
  start(opts: { token: string; baseUrl: string }): Promise<{ started: boolean }>
  updateToken(opts: { token: string }): Promise<void>
  stop(): Promise<void>
  setEnabled(opts: { enabled: boolean }): Promise<void>
  isEnabled(): Promise<{ enabled: boolean; canNotify: boolean; watching: boolean }>
  ensurePermission(): Promise<{ granted: boolean }>
  consumeDeepLink(): Promise<{ id?: number }>
}

const Notify = registerPlugin<NotifyNative>('Notify')

const log = (msg: string, err: unknown) => {
  if (import.meta.env.DEV) console.warn(`[notify] ${msg}`, err)
}

/** 登录后调用：给服务发凭据并拉起轮询。 */
export async function startWatching(token: string, baseUrl: string): Promise<boolean> {
  try {
    await Notify.ensurePermission()
    await Notify.start({ token, baseUrl })
    return true
  } catch (e) {
    log('启动后台提醒失败', e)
    return false
  }
}

/** accessToken 刷新后同步（api 层刷新成功时调，否则服务会拿着过期 token 空转到停）。 */
export function pushToken(token: string): void {
  void Notify.updateToken({ token }).catch((e) => log('同步 token 失败', e))
}

/** 退出登录：停服务 + 清台账（下一个人不该看到上一位的"已知待办"）。 */
export function stopWatching(): void {
  void Notify.stop().catch((e) => log('停止后台提醒失败', e))
}

/** 「我的」里的提醒开关。 */
export async function setNotifyEnabled(enabled: boolean): Promise<void> {
  try {
    await Notify.setEnabled({ enabled })
  } catch (e) {
    log('切换提醒开关失败', e)
  }
}

export async function notifyStatus(): Promise<{
  enabled: boolean
  canNotify: boolean
  watching: boolean
}> {
  try {
    return await Notify.isEnabled()
  } catch {
    return { enabled: false, canNotify: false, watching: false }
  }
}

/**
 * 取通知点击的落点（待审批单子 id）。
 *
 * **一次性消费**：定位是高亮的辅助，不是标记已读——用户手动切走或处理完就没有意义了。
 * 返回 null 表示没点过通知，或 App 还没登录（那时页面不存在，定位没有意义）。
 */
export async function consumeDeepLink(): Promise<number | null> {
  try {
    const { id } = await Notify.consumeDeepLink()
    return typeof id === 'number' && id > 0 ? id : null
  } catch {
    return null
  }
}

/**
 * 回到前台时再取一次深链。
 *
 * 场景：App 已经在后台运行 → 用户点通知 → `onNewIntent` 把落点暂存 →
 * 但 Web 层此刻可能还没挂载完插件，所以除了首屏取一次，App 每次回到前台也要取一次。
 */
export function watchDeepLink(onTarget: (id: number) => void): () => void {
  let cancelled = false
  const check = () => {
    if (cancelled || document.visibilityState !== 'visible') return
    void consumeDeepLink().then((id) => {
      if (id && !cancelled) onTarget(id)
    })
  }
  document.addEventListener('visibilitychange', check)
  return () => {
    cancelled = true
    document.removeEventListener('visibilitychange', check)
  }
}

export type { PluginListenerHandle }
