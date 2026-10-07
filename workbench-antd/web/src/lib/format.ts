import {
  CUSTOMER_LEVEL,
  CUSTOMER_STATUS,
  FOLLOW_TYPE,
  OMS_CATEGORY,
  OMS_CHANNEL,
  OMS_ORDER_STATUS,
  OMS_PRODUCT_STATUS,
  REQUEST_STATUS,
  ROLE,
  STAGE,
  TODO_DUE,
  TMS_DRIVER_STATUS,
  TMS_ORDER_STATUS,
  TMS_VEHICLE_STATUS,
  WMS_RECORD_TYPE,
} from './labels'

/**
 * 展示格式化。
 *
 * 全部是纯函数（不碰 DOM/网络），单测直接钉住——格式错了是最容易漏、又最扎眼的
 * 一类问题（`¥60000` / `NaN` / `Invalid Date` 在页面上极其显眼）。
 */

const pick = (dict: Record<string, string>, code?: string | null) =>
  (code && dict[code]) || code || '—'

export const customerStatusText = (c?: string | null) => pick(CUSTOMER_STATUS, c)
export const customerLevelText = (c?: string | null) => pick(CUSTOMER_LEVEL, c)
export const stageText = (c?: string | null) => pick(STAGE, c)
export const requestStatusText = (c?: string | null) => pick(REQUEST_STATUS, c)
export const followTypeText = (c?: string | null) => pick(FOLLOW_TYPE, c)
export const dueText = (c?: string | null) => pick(TODO_DUE, c)
export const roleText = (c?: string | null) => pick(ROLE, c)

// TMS / WMS / OMS 的枚举文案。与原生版 `feature/{tms,wms,oms}/*Screen.kt` 里
// 各页面私有的字典保持一致——同一个状态码两端不能一个叫"运输中"一个叫"在途"。
export const tmsOrderStatusText = (c?: string | null) => pick(TMS_ORDER_STATUS, c)
export const vehicleStatusText = (c?: string | null) => pick(TMS_VEHICLE_STATUS, c)
export const driverStatusText = (c?: string | null) => pick(TMS_DRIVER_STATUS, c)
export const stockTypeText = (c?: string | null) => pick(WMS_RECORD_TYPE, c)
export const omsStatusText = (c?: string | null) => pick(OMS_ORDER_STATUS, c)
export const channelText = (c?: string | null) => pick(OMS_CHANNEL, c)
export const categoryText = (c?: string | null) => pick(OMS_CATEGORY, c)
export const productStatusText = (c?: string | null) => pick(OMS_PRODUCT_STATUS, c)

/** 金额：`60000` → `¥60,000`；空值给 `—`，不给 `¥0` 免得看起来像真有钱。 */
export function money(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `¥${Math.round(value).toLocaleString('zh-CN')}`
}

/** 大额压缩：`1250000` → `¥125.0万`，用在指标卡这种宽度紧张的地方。 */
export function moneyShort(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  if (Math.abs(value) >= 10000) return `¥${(value / 10000).toFixed(1)}万`
  return money(value)
}

/**
 * 字节数 → 可读容量：`21396681000000` → `19.5 GB`。
 *
 * 网关工作台的 `diskFree` / `diskTotal` 是**字节**。曾经错用金额格式化，页面上
 * 出现过 `¥2139668.1万` 这种一眼假的数字——所以单独一个函数并在单测里钉住。
 */
export function bytes(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = value
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i += 1
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

/**
 * 时间：两个服务格式不统一，统一收敛成 `MM-DD HH:mm`。
 *
 * - `2026-09-29T20:53:32`（crm，ISO）
 * - `2026-10-01 00:24:42`（user-center，MySQL datetime 文本）
 *
 * Safari/WebView 对非标准格式的 `new Date()` 解析不一致，所以**手工拆字段**，
 * 不交给 `new Date()` 猜。
 */
export function dateTime(raw?: string | null): string {
  if (!raw) return '—'
  const m = raw.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (!m) return raw
  return `${m[2]}-${m[3]} ${m[4]}:${m[5]}`
}

/** `2026-10-31` → `10-31`；只有年份月时取月。 */
export function dateOnly(raw?: string | null): string {
  if (!raw) return '—'
  const m = raw.match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (!m) return raw
  return `${m[2]}-${m[3]}`
}

/** 今天：`YYYY-MM-DD`（页面头部用）。 */
export function today(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 问候语，按当前小时分档。 */
export function greeting(now = new Date()): string {
  const h = now.getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}