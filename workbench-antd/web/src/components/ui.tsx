import type { ReactNode } from 'react'
import { Button, Card, ErrorBlock } from 'antd-mobile'
import { errorText } from './useAsync'

/**
 * 页面骨架组件。
 *
 * 都是 antd-mobile 组件的薄封装——统一间距与标题层级，别让每个页面各写一套
 * `Card` + `padding`，改样式时只改这一处。
 */

/** 分区卡片：白底圆角 + 阴影。左侧的主色竖条由 CSS 提供。 */
export function SectionCard({
  title,
  extra,
  children,
}: {
  title?: ReactNode
  extra?: ReactNode
  children: ReactNode
}) {
  return (
    <Card className="section">
      {(title || extra) && (
        <div className="section-head">
          <div className="section-title">{title}</div>
          {extra ? <div className="section-extra">{extra}</div> : null}
        </div>
      )}
      {children}
    </Card>
  )
}

/**
 * 指标网格。列数要跟着卡片数走：4 张用 2×2 正好，6 张用 3 列两行——
 * 3 列网格放 4 张会让第 4 张单独落在第二行，看着像少了一张。
 */
export function StatGrid({ cols = 3, children }: { cols?: number; children: ReactNode }) {
  return (
    <div className="stat-grid" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {children}
    </div>
  )
}

/**
 * 骨架屏。
 *
 * 不用 antd-mobile 自带的 `Skeleton`（等宽横条，跟我们的指标卡/两行式列表对不上，
 * 数据一到会整块跳）。这里按**自己的版式**画灰块：块的位置和真实内容对齐，
 * 数据到达时版面基本不动，观感是"内容正在浮现"而不是"页面重来一遍"。
 * 具名导出而不是默认导出，避免和 antd-mobile 的 Skeleton 撞名时看不出用的是哪个。
 */

/** 指标卡骨架：与 `.stat` 同高的两块（标签 + 数字）。 */
export function StatSkeleton({ cols = 3, count = 6 }: { cols?: number; count?: number }) {
  return (
    <div className="stat-grid" style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {Array.from({ length: count }, (_, i) => (
        <div className="stat" key={i}>
          <div className="sk" style={{ width: '50%', height: 10, margin: '0 auto' }} />
          <div className="sk" style={{ width: '62%', height: 18, margin: '7px auto 0' }} />
        </div>
      ))}
    </div>
  )
}

/** 列表骨架：n 条两行式假行，行高与 `.row` 一致。 */
export function RowSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div>
      {Array.from({ length: count }, (_, i) => (
        <div className="row" key={i}>
          <div className="row-top">
            <div className="sk" style={{ width: '56%', height: 15 }} />
            <div className="sk" style={{ width: 56, height: 20 }} />
          </div>
          <div className="sk" style={{ width: '40%', height: 12, marginTop: 6 }} />
        </div>
      ))}
    </div>
  )
}

/** 整页骨架：指标格 + 几条行——页面级 `!data` 时的占位，顺序与真实版式相同。 */
export function PageSkeleton({ cols = 3, stats = 6, rows = 4 }: { cols?: number; stats?: number; rows?: number }) {
  return (
    <>
      <StatSkeleton cols={cols} count={stats} />
      <div style={{ height: 12 }} />
      <RowSkeleton count={rows} />
    </>
  )
}

/** 指标卡：浅蓝渐变底 + 主色大数字。`tone="danger"` 给「禁用」这类要停一眼的红数字。 */
export function StatCard({
  label,
  value,
  hint,
  tone,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: 'danger'
}) {
  return (
    <div className={`stat${tone ? ` stat-${tone}` : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint ? <div className="stat-hint">{hint}</div> : null}
    </div>
  )
}

/**
 * 状态标签。
 *
 * 自己画软填充而不用 antd-mobile 的 `Tag`：5.43 的 Tag 只有 solid / outline 两种
 * 填充，outline 落在白卡上偏素；四种语义色的淡底 + 同色文字更像"成品"。
 */
export function StatusTag({
  color,
  children,
}: {
  color?: 'primary' | 'success' | 'warning' | 'danger'
  children: ReactNode
}) {
  return <span className={`chip chip-${color ?? 'default'}`}>{children}</span>
}

/**
 * 空态。
 *
 * 插图是自己画的线条 SVG，不是字符——之前用了一个 `⌸` 字符，在部分字体下渲染成
 * 豆腐块，看起来像图标加载失败。antd-mobile 自带的 `Empty` 组件在 5.43 已废弃，
 * 所以这里自己画，还能控制配色跟着灰阶走。
 */
export function EmptyBox({ text, hint }: { text: string; hint?: string }) {
  return (
    <div className="empty">
      <div className="empty-icon" aria-hidden>
        <svg width="72" height="56" viewBox="0 0 72 56" fill="none">
          <rect
            x="10.5"
            y="12.5"
            width="51"
            height="36"
            rx="4.5"
            stroke="currentColor"
            strokeWidth="2"
          />
          <path d="M10.5 22h12l4 6h19l4-6h12" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
          <path d="M24 41.5h24" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.45" />
          <circle cx="55" cy="43" r="8.5" fill="#fff" stroke="currentColor" strokeWidth="2" />
          <path d="M61 49l5.5 5.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      </div>
      <div className="empty-text">{text}</div>
      {hint ? <div className="empty-hint">{hint}</div> : null}
    </div>
  )
}

/** 权限不足占位：与「出错」区分开——它不是故障，是这个账号看不到。 */
export function PermissionDenied({ message }: { message?: string }) {
  return (
    <div className="denied">
      <div className="denied-title">没有查看权限</div>
      <div className="denied-text">
        {message || '当前账号缺少对应的权限点，需要管理员授权后才能查看。'}
      </div>
    </div>
  )
}

/**
 * 出错占位 + **重试按钮**。
 *
 * antd-mobile 的 `ErrorBlock` 只有插画和文案，`ErrorBlockProps` 里**没有 onRetry**，
 * 所以按钮得自己加。`useAsync` 的设计注释一直写着「错是 ErrorBlock + 重试按钮」，
 * 但七个错误分支其实一个按钮都没带——后端一重启，整页就只剩一张插画干看着
 * （2026-10-06 并行会话重启 user-center，正好撞上用户操作，审批页满屏 500，
 *  想重试只能杀掉 App 重开）。这里收口成一个组件，调用方只传 `reload`。
 *
 * 刻意**不在这里发错误触感**：加载失败发生在渲染期，后端宕机时切三个 Tab 就会
 * 连震三下；该震的是「用户主动做的动作失败了」，那个走 `toastErr`。
 */
export function ErrorBox({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="error-box">
      <ErrorBlock status="disconnected" description={errorText(error)} />
      {onRetry ? (
        <div className="error-box-actions">
          <Button size="small" fill="outline" shape="rounded" color="primary" onClick={onRetry}>
            重新加载
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/** 键值行：`标签 …… 值`，详情弹窗里大量用。 */
export function FieldRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="field">
      <div className="field-label">{label}</div>
      <div className="field-value">{children}</div>
    </div>
  )
}