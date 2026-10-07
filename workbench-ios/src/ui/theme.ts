/**
 * 色板与字号——与 workbench-android 的 Theme.kt 保持一致，两个端看起来是同一个产品。
 *
 * 工作台是跨模块的门户，主色取青绿（Teal），与 CRM 客户端的靛蓝区分开——
 * 手机上两个 App 并排时，一眼能看出点开的是哪个。
 */

export const BrandTeal = '#0F766E'
export const BrandTealDark = '#115E59'
export const BrandSky = '#0EA5E9'
export const BrandEmerald = '#10B981'
export const BrandAmber = '#F59E0B'
export const BrandRose = '#EC4899'
export const BrandIndigo = '#4F46E5'

export const TextPrimary = '#101828'
export const TextSecondary = '#475467'
export const TextMuted = '#98A2B3'
export const SurfaceTint = '#F8F9FC'
export const Hairline = '#EAECF0'
export const ErrorRed = '#D92D20'
export const ErrorRedBg = '#FEF3F2'
export const ErrorRedText = '#B42318'
export const OverdueRed = '#F04438'
export const AdminOrange = '#F97316'

/** 字号刻意比桌面端大一档：手机屏幕近、单列浏览，正文 15 起步比 14 舒服。 */
export const FontSize = {
    headline: 24,
    titleLarge: 20,
    titleMedium: 17,
    titleSmall: 15,
    bodyLarge: 16,
    bodyMedium: 15,
    bodySmall: 13,
    labelLarge: 15,
    labelMedium: 13,
    labelSmall: 12
}

export const Radius = {
    card: 14,
    tag: 6,
    button: 12,
    dialog: 18
}

export const Spacing = {
    xs: 4,
    sm: 8,
    md: 14,
    lg: 16,
    xl: 24
}
