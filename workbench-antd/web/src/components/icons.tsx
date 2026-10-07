/**
 * Tab 栏图标（内联 SVG）。
 *
 * 不引 icon 字体也不引图标库：这个壳的产物要塞进 APK，每多一个依赖就多一份体积。
 * 四个图标手写十几行就够，样式统一用 `currentColor`——选中态的颜色由
 * TabBar 自己的 CSS 变量控制，我们不掺和。
 */
type IconProps = { className?: string }

const base = {
  width: 22,
  height: 22,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

/** 系统管理：管理面板（四个格子 + 挡板） */
export const IconSystem = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <rect x="3" y="3" width="7" height="8" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="11" width="7" height="10" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
  </svg>
)

/** TMS：货车 */
export const IconTms = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <path d="M2.5 7.5h11v8h-11z" />
    <path d="M13.5 10.5h4l3 3v2h-7z" />
    <circle cx="7" cy="17.5" r="1.8" />
    <circle cx="17" cy="17.5" r="1.8" />
  </svg>
)

/** WMS：箱子 */
export const IconWms = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <path d="M12 3l8.5 4.2v9.6L12 21l-8.5-4.2V7.2z" />
    <path d="M3.7 7.3L12 11.4l8.3-4.1" />
    <path d="M12 11.4V21" />
  </svg>
)

/** OMS：购物袋 */
export const IconOms = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <path d="M4.5 7.5h15l-1.2 12.2a1.5 1.5 0 0 1-1.5 1.3H7.2a1.5 1.5 0 0 1-1.5-1.3z" />
    <path d="M9 9.5V6.8a3 3 0 0 1 6 0v2.7" />
  </svg>
)

/** CRM：客户（两个人） */
export const IconCrm = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <circle cx="9" cy="8" r="3.2" />
    <path d="M3.5 19c0-3 2.5-5 5.5-5s5.5 2 5.5 5" />
    <path d="M16 5.5a3 3 0 0 1 0 5.6" />
    <path d="M17.5 14.2c2 .6 3.5 2.4 3.5 4.8" />
  </svg>
)

/** 审批：清单打勾 */
export const IconApproval = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <rect x="4" y="3.5" width="16" height="17" rx="2.5" />
    <path d="M8 9.5l2.2 2.2L14.5 7" />
    <path d="M8 15.5h8" />
  </svg>
)

/** 我的：人像 */
export const IconMe = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <circle cx="12" cy="8" r="3.5" />
    <path d="M5 20c0-3.6 3.1-6 7-6s7 2.4 7 6" />
  </svg>
)
/** 通讯录：组织树（根节点分叉到两个下属） */
export const IconCorp = ({ className }: IconProps) => (
  <svg {...base} className={className} aria-hidden>
    <rect x="9" y="2.5" width="6" height="5" rx="1.2" />
    <rect x="2.5" y="16.5" width="6" height="5" rx="1.2" />
    <rect x="15.5" y="16.5" width="6" height="5" rx="1.2" />
    <path d="M12 7.5v4M5.5 16.5v-2.5a1 1 0 0 1 1-1h11a1 1 0 0 1 1 1v2.5" />
  </svg>
)
