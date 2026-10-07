/**
 * 轻量图表：环形图 + 排行条。
 *
 * **不引图表库**：为了两个图引入 echarts/recharts 会给 APK 加几百 KB，而这壳里
 * 一个 APK 才 3.9MB。环形图用 conic-gradient 画，排行条用 flex 百分比，都不需要
 * 依赖，且配色跟着 CSS 变量走。
 */

export interface Slice {
  label: string
  value: number
  /** CSS 颜色；不传按序取默认色板 */
  color?: string
}

/** 与 antd 蓝同一族、又能让 5~6 段互相区分的色板 */
const PALETTE = ['#1677ff', '#00b578', '#ff8f1f', '#f5483b', '#7b61ff', '#0fc6c2']

/** 环形图 + 右侧图例。`unit` 只影响图例里的数字后缀。 */
export function Donut({ slices, unit }: { slices: Slice[]; unit?: string }) {
  const total = slices.reduce((sum, s) => sum + s.value, 0)
  const visible = slices.filter((s) => s.value > 0)

  if (total === 0 || visible.length === 0) {
    return (
      <div className="donut-empty">
        <div className="donut-empty-circle" />
        <span>暂无数据</span>
      </div>
    )
  }

  // conic-gradient 累积角度：从 12 点方向顺时针
  let acc = 0
  const stops = visible
    .map((s, i) => {
      const from = (acc / total) * 360
      acc += s.value
      const to = (acc / total) * 360
      return `${s.color ?? PALETTE[i % PALETTE.length]} ${from.toFixed(2)}deg ${to.toFixed(2)}deg`
    })
    .join(', ')

  return (
    <div className="donut">
      <div className="donut-ring" style={{ background: `conic-gradient(${stops})` }}>
        <div className="donut-hole">
          <div className="donut-total">{total}</div>
          <div className="donut-total-label">{unit ?? '合计'}</div>
        </div>
      </div>
      <div className="donut-legend">
        {visible.map((s, i) => (
          <div className="legend-row" key={s.label}>
            <i className="legend-dot" style={{ background: s.color ?? PALETTE[i % PALETTE.length] }} />
            <span className="legend-label">{s.label}</span>
            <span className="legend-value">{s.value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * 横向排行条。`hint` 跟在数值后面，例如销售额。
 *
 * `normalize` 决定条宽的基准，两种语义别混用：
 *  - `max`（默认）：**排行**用。最大值那条满格，其余按比例——能看出名次差距
 *    （热销款 TOP5 就该这样）
 *  - `total`：**分布**用。按占总量的比例——渠道分布里每家都是 1 单时，
 *    按 max 归一化会五条全是满格，看着像渲染坏了
 */
export function RankBars({
  items,
  unit,
  normalize = 'max',
}: {
  items: { label: string; value: number; hint?: string; color?: string }[]
  unit?: string
  normalize?: 'max' | 'total'
}) {
  const basis =
    normalize === 'total'
      ? Math.max(1, items.reduce((sum, i) => sum + i.value, 0))
      : Math.max(1, ...items.map((i) => i.value))
  return (
    <div className="ranks">
      {items.map((it, idx) => (
        <div className="rank" key={it.label}>
          <div className="rank-head">
            <span className="rank-label">
              <i className="rank-no" style={{ background: idx < 3 ? '#1677ff' : '#c9cdd4' }}>
                {idx + 1}
              </i>
              {it.label}
            </span>
            <span className="rank-value">
              {it.value.toLocaleString('zh-CN')}
              {unit ?? ''}
              {it.hint ? <em>{it.hint}</em> : null}
            </span>
          </div>
          <div className="rank-track">
            <div
              className="rank-fill"
              style={{
                width: `${Math.max(4, (it.value / basis) * 100)}%`,
                background: it.color ?? 'linear-gradient(90deg,#4d94ff,#1677ff)',
              }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}