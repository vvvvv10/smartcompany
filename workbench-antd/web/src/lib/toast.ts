import { Toast } from 'antd-mobile'
import { hapticError, hapticLight, hapticSuccess } from './haptics'

/**
 * Toast 的统一入口：**提示与触感绑在一起**。
 *
 * 之前 Toast.show 散在 5 个页面里 14 处，加触感时如果每处都手写
 * `Toast.show(...)` + `void hapticXxx()`，迟早漏掉几处（漏了就又是「按了没反应」）。
 * 收口成一个函数后，只要走这里，视觉和震动一定同时出现。
 *
 * 三档语义：
 *   ok   —— 操作成功（已批准 / 已提交 / 开关生效）→ Success 触感
 *   err  —— 操作失败或参数不合法（接口 5xx、驳回没填理由）→ Error 触感
 *   info —— 中性告知（服务地址已更新）→ Light 触感
 *
 * 触感在 Toast **之前** fire：Toast 有入场动画，先震一下用户手指才知道去看它。
 */
export function toast(content: string, kind: 'ok' | 'err' | 'info' = 'info'): void {
  if (kind === 'ok') hapticSuccess()
  else if (kind === 'err') hapticError()
  else hapticLight()
  Toast.show({ content, position: 'top' })
}

/** 成功提示 + Success 触感。 */
export const toastOk = (content: string): void => toast(content, 'ok')

/** 失败提示 + Error 触感。 */
export const toastErr = (content: string): void => toast(content, 'err')

/** 中性提示 + Light 触感。 */
export const toastInfo = (content: string): void => toast(content, 'info')
