import { Capacitor, registerPlugin } from '@capacitor/core'

interface SysBarsPlugin {
  /** 状态栏高度（物理像素，等于 CSS px）。取不到时 height = 0。 */
  statusBarHeight(): Promise<{ height: number }>
}

const SysBars = registerPlugin<SysBarsPlugin>('SysBars')

/** 读一次原生量到的状态栏高度。失败/非原生一律返回 0（调用方据此退回 CSS env）。 */
async function read(): Promise<number> {
  if (!Capacitor.isNativePlatform()) return 0
  try {
    const res = await SysBars.statusBarHeight()
    return typeof res?.height === 'number' && res.height > 0 ? res.height : 0
  } catch {
    return 0
  }
}

function apply(px: number): void {
  if (px <= 0) return
  // 原生给的是**物理像素**，CSS 里的 1px 是 1/devicePixelRatio 个物理像素。
  // 实测（本机 420dpi，dpr 2.625，系统 status_bar_height=128px）：直接把 128 写进 CSS
  // 会被当成 128 CSS px = 336 物理 px，页头凭空多出 200 多像素空白。必须除以 dpr。
  const dpr = typeof window === 'undefined' ? 1 : window.devicePixelRatio || 1
  const cssPx = Math.round(px / dpr)
  if (cssPx > 0) document.documentElement.style.setProperty('--wb-status-h', `${cssPx}px`)
}

/**
 * 把状态栏真实高度写进 `--wb-status-h`，页头/登录页拿它做上内边距。
 *
 * <p>为什么必须有原生这一趟：Chrome 在 Android 上 `env(safe-area-inset-top)` **只反映
 * 屏幕挖孔**，不含状态栏本身。挖孔机型上那个值只有孔高（本机口径约 40px），而状态栏连
 * 图标带边距一百多 px——只看 env 的话，页头标题照样被时间/电量压住；模拟器没有挖孔，
 * env 直接是 0。所以 CSS 里的 env 只能当兜底，真值来自原生。
 *
 * <p>横竖屏切换后状态栏 inset 会变，重新量一次；测量本身很便宜，
 * 但 resize 在拖动窗口时会疯狂触发，用 200ms 尾巴收敛。
 */
export async function applyStatusBarHeight(): Promise<void> {
  apply(await read())
  if (typeof window === 'undefined') return
  let timer: ReturnType<typeof setTimeout> | undefined
  const onResize = () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => void read().then(apply), 200)
  }
  window.addEventListener('orientationchange', onResize)
  window.addEventListener('resize', onResize)
}