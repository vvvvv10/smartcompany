import { Donut } from '../components/charts'
import { errorText, isForbidden, useAsync } from '../components/useAsync'
import { EmptyBox, PermissionDenied, RowSkeleton, SectionCard, StatCard, StatGrid, StatSkeleton, StatusTag } from '../components/ui'
import { api } from '../lib/api'
import { dateTime, driverStatusText, tmsOrderStatusText, vehicleStatusText } from '../lib/format'

/**
 * TMS 运输管理（只读看板）。
 *
 * 四个接口并行拉，任一失败只影响对应区块——看板挂了不该让车辆列表也空掉。
 * **页面不做权限点过滤**：模块开关打开就能进，没权限时后端 403，由 PermissionDenied
 * 兜底（与原生版「Tab 之间不做权限过滤」的约定一致）。
 */
export default function Tms() {
  const dash = useAsync(() => api.tmsDashboard(), [])
  const orders = useAsync(() => api.tmsOrders(), [])
  const vehicles = useAsync(() => api.tmsVehicles(), [])
  const drivers = useAsync(() => api.tmsDrivers(), [])

  if (dash.error && isForbidden(dash.error)) return <PermissionDenied message={errorText(dash.error)} />

  const d = dash.data
  const cancelled = d
    ? Math.max(0, d.orders.total - d.orders.pending - d.orders.inTransit - d.orders.delivered)
    : 0

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">TMS 运输</div>
        <div className="page-head-sub">运单、车辆与司机</div>
      </div>

      <SectionCard title="运力概览">
        {d ? (
          <>
            <StatGrid cols={2}>
              <StatCard label="运单总数" value={d.orders.total} hint={`待处理 ${d.orders.pending}`} />
              <StatCard label="运输中" value={d.orders.inTransit} hint={`已送达 ${d.orders.delivered}`} />
              <StatCard
                label="车辆"
                value={d.vehicles.total}
                hint={`在途 ${d.vehicles.busy} · 空闲 ${d.vehicles.idle}`}
              />
              <StatCard
                label="司机"
                value={d.drivers.total}
                hint={`在岗 ${d.drivers.active} · 休息 ${d.drivers.total - d.drivers.active}`}
              />
            </StatGrid>
            <div style={{ marginTop: 16 }}>
              <Donut
                unit="运单"
                slices={[
                  { label: '待处理', value: d.orders.pending, color: '#ff8f1f' },
                  { label: '运输中', value: d.orders.inTransit },
                  { label: '已送达', value: d.orders.delivered, color: '#00b578' },
                  { label: '已取消', value: cancelled, color: '#f5483b' },
                ]}
              />
            </div>
          </>
        ) : dash.loading ? (
          <StatSkeleton cols={2} count={4} />
        ) : (
          <EmptyBox text="运力数据加载失败" hint={errorText(dash.error)} />
        )}
      </SectionCard>

      <SectionCard title={`运输订单（${orders.data?.list.length ?? 0}）`}>
        <ListBody
          state={orders}
          empty="暂无运单"
          render={(o) => (
            <>
              <div className="row-top">
                <span className="row-title">{o.orderNo}</span>
                <StatusTag color={orderColor(o.status)}>{tmsOrderStatusText(o.status)}</StatusTag>
              </div>
              <div className="row-meta">
                {o.customerName || '—'} · {o.origin || '—'} → {o.destination || '—'}
                <br />
                {dateTime(o.createdAt)}
              </div>
            </>
          )}
        />
      </SectionCard>

      <SectionCard title={`车辆（${vehicles.data?.list.length ?? 0}）`}>
        <ListBody
          state={vehicles}
          empty="暂无车辆"
          render={(v) => (
            <>
              <div className="row-top">
                <span className="row-title">{v.plateNo}</span>
                <StatusTag color={vehicleColor(v.status)}>{vehicleStatusText(v.status)}</StatusTag>
              </div>
              <div className="row-meta">
                {v.vehicleType || '—'} · {v.driverName ? `司机 ${v.driverName}` : '未排班'}
              </div>
            </>
          )}
        />
      </SectionCard>

      <SectionCard title={`司机（${drivers.data?.list.length ?? 0}）`}>
        <ListBody
          state={drivers}
          empty="暂无司机"
          render={(dr) => (
            <>
              <div className="row-top">
                <span className="row-title">{dr.name}</span>
                <StatusTag color={dr.status === 'ACTIVE' ? 'success' : undefined}>
                  {driverStatusText(dr.status)}
                </StatusTag>
              </div>
              {dr.phone ? <div className="row-meta">{dr.phone}</div> : null}
            </>
          )}
        />
      </SectionCard>

      <div style={{ height: 12 }} />
    </div>
  )
}

/**
 * 列表区块的四种状态收敛到一处：加载中 / 出错 / 空 / 有数据。
 * 四个区块各写一遍 if 只会让"出错时文案到底是哪个"变得难以确认。
 */
export function ListBody<T>({
  state,
  empty,
  render,
}: {
  state: { data: { list: T[] } | null; error: Error | null; loading: boolean }
  empty: string
  render: (item: T) => React.ReactNode
}) {
  if (state.error) return <EmptyBox text="加载失败" hint={errorText(state.error)} />
  if (!state.data) return <RowSkeleton count={4} />
  if (state.data.list.length === 0) return <EmptyBox text={empty} />
  return <>{state.data.list.map((item, i) => <div className="row" key={i}>{render(item)}</div>)}</>
}

/** 运单状态色：待处理橙、运输中蓝、已送达绿、已取消红 */
function orderColor(status: string): 'primary' | 'success' | 'warning' | 'danger' {
  if (status === 'DELIVERED') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'CANCELLED') return 'danger'
  return 'primary'
}

function vehicleColor(status: string): 'primary' | 'success' | 'danger' {
  if (status === 'IDLE') return 'success'
  if (status === 'MAINTENANCE') return 'danger'
  return 'primary'
}