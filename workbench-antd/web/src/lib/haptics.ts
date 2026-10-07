import { Haptics, ImpactStyle, NotificationType } from '@capacitor/haptics'

/**
 * 触感语义封装。
 *
 * 之前整个 App 一点震动都没有——按下去没有任何身体反馈，肉眼又看不出按压态，
 * 于是「按了没反应」（用户原话：操作感觉怪怪的）。这里按**语义**而不是按强度提供，
 * 调用方只管说「这是一次轻点/这是成功/这是失败」，不关心具体毫秒。
 *
 * 两层兜底，任何一层失败都**静默降级成无触感**，绝不因为马达/权限问题让业务报错：
 *   1. 浏览器（vite dev、PC 预览）与 iOS 之外的平台没有这个插件 → 调用 reject；
 *   2. 用户在系统里关了触感/振动 → 调用 reject。
 *
 * 分档的依据：Light 给「导航与切换」这类高频动作（连点 10 下也不烦），
 * Medium 给「提交了」这种一次性动作，Success/Error 给操作结果（比 Impact 更像
 * 「这件事成了/黄了」，安卓走 NotificationFeedback 会多一档力度）。
 */

async function impact(style: ImpactStyle): Promise<void> {
  try {
    await Haptics.impact({ style })
  } catch {
    /* 不支持或被系统关掉：无触感即可 */
  }
}

async function notify(type: NotificationType): Promise<void> {
  try {
    await Haptics.notification({ type })
  } catch {
    /* 同上 */
  }
}

/** 轻点：切 Tab、切筛选段、点开关这类高频导航。 */
export const hapticLight = (): void => void impact(ImpactStyle.Light)

/** 中等：提交表单、点「批准/驳回」这种一次性决定。 */
export const hapticMedium = (): void => void impact(ImpactStyle.Medium)

/** 重击：少见，留给「清空/断开连接」这类需要停顿感的动作。 */
export const hapticHeavy = (): void => void impact(ImpactStyle.Heavy)

/** 选中：分段控件的选中态变化，力度比 Light 更短，连着切不糊。 */
async function selection(): Promise<void> {
  try {
    await Haptics.selectionChanged()
  } catch {
    /* 不支持或被系统关掉：无触感即可 */
  }
}

export const hapticSelect = (): void => void selection()

/** 操作成功（申请提交、批准、开关生效）。 */
export const hapticSuccess = (): void => void notify(NotificationType.Success)

/** 操作失败（接口报错、校验不过、后端重启撞上）。 */
export const hapticError = (): void => void notify(NotificationType.Error)

/** 警告：驳回必填没填、需要确认的破坏性操作。 */
export const hapticWarning = (): void => void notify(NotificationType.Warning)
