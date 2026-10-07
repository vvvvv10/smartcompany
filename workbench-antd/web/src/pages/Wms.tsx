import { RankBars } from '../components/charts'
import { errorText, isForbidden, useAsync } from '../components/useAsync'
import { EmptyBox, PermissionDenied, RowSkeleton, SectionCard, StatCard, StatGrid, StatSkeleton, StatusTag } from '../components/ui'
import { api } from '../lib/api'
import { dateTime, stockTypeText } from '../lib/format'
import type { WmsStockRecord } from '../lib/types'
import { ListBody } from './Tms'

/**
 * WMS 仓储管理（只读看板）。
 *
 * 看板只有 stockRecords + inventory 两段，**仓库数不在看板里**——用仓库列表长度
 * 补位（与原生版同一口径，别给后端没给的字段编默认值）。
 */
export default function Wms() {
  const dash = useAsync(() => api.wmsDashboard(), [])
  const warehouses = useAsync(() => api.wmsWarehouses(), [])
  const inventory = useAsync(() => api.wmsInventory(), [])
  const records = useAsync(() => api.wmsStockRecords(), [])

  if (dash.error && isForbidden(dash.error)) return <PermissionDenied message={errorText(dash.error)} />

  const d = dash.data

  /** 库存按仓库聚合 TOP5：库存分布是仓库维度，SKU 维度列表太长没法看 */
  const byWarehouse = (() => {
    const list = inventory.data?.list ?? []
    const map = new Map<string, number>()
    list.forEach((i) => {
      const key = i.warehouseName?.trim() || '未指定仓库'
      map.set(key, (map.get(key) ?? 0) + (i.quantity ?? 0))
    })
    return [...map.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([label, value]) => ({ label, value }))
  })()

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">WMS 仓储</div>
        <div className="page-head-sub">仓库、库存与出入库</div>
      </div>

      <SectionCard title="库存概览" extra={warehouses.data ? `${warehouses.data.list.length} 个仓库` : undefined}>
        {d ? (
          <StatGrid>
            <StatCard label="今日入库" value={d.stockRecords.todayIn} hint={`累计 ${d.stockRecords.totalIn}`} />
            <StatCard label="今日出库" value={d.stockRecords.todayOut} hint={`累计 ${d.stockRecords.totalOut}`} />
            <StatCard
              label="库存总量"
              value={d.inventory.totalQuantity}
              hint={`${d.inventory.totalProducts} 种商品`}
            />
            <StatCard label="低库存预警" value={d.inventory.lowStock} />
            <StatCard label="仓库数" value={warehouses.data?.list.length ?? '—'} />
            <StatCard label="库存记录" value={inventory.data?.list.length ?? '—'} />
          </StatGrid>
        ) : dash.loading ? (
          <StatSkeleton count={4} />
        ) : (
          <EmptyBox text="仓储数据加载失败" hint={errorText(dash.error)} />
        )}
      </SectionCard>

      <SectionCard title="各仓库存 TOP5" extra="按 SKU 数量">
        {byWarehouse.length === 0 ? (
          <EmptyBox text={inventory.error ? '库存加载失败' : '暂无库存记录'} />
        ) : (
          <RankBars items={byWarehouse} unit=" 件" />
        )}
      </SectionCard>

      <SectionCard title={`仓库（${warehouses.data?.list.length ?? 0}）`}>
        <ListBody
          state={warehouses}
          empty="暂无仓库"
          render={(w) => (
            <>
              <div className="row-top">
                <span className="row-title">{w.name}</span>
                <StatusTag color={w.status === 'ACTIVE' || w.status === 'NORMAL' ? 'success' : undefined}>
                  {w.status === 'ACTIVE' ? '启用' : w.status === 'NORMAL' ? '正常' : w.status || '—'}
                </StatusTag>
              </div>
              <div className="row-meta">
                {w.location || '—'} · 容量 {w.capacity.toLocaleString('zh-CN')}
              </div>
            </>
          )}
        />
      </SectionCard>

      <SectionCard title={`库存明细（${inventory.data?.list.length ?? 0}）`}>
        <ListBody
          state={inventory}
          empty="暂无库存记录"
          render={(i) => (
            <>
              <div className="row-top">
                <span className="row-title">{i.productName || '—'}</span>
                <span className="row-amount">{i.quantity} 件</span>
              </div>
              <div className="row-meta">
                {i.warehouseName || '未指定仓库'} · {i.sku || '无 SKU'}
              </div>
            </>
          )}
        />
      </SectionCard>

      <SectionCard title={`出入库记录（${records.data?.list.length ?? 0}）`}>
        {records.error ? (
          <EmptyBox text="记录加载失败" hint={errorText(records.error)} />
        ) : !records.data ? (
          <RowSkeleton count={4} />
        ) : records.data.list.length === 0 ? (
          <EmptyBox text="暂无出入库记录" />
        ) : (
          records.data.list.map((r: WmsStockRecord) => (
            <div className="row" key={r.id}>
              <div className="row-top">
                <span className="row-title">{r.productName || '—'}</span>
                <StatusTag color={r.type === 'IN' ? 'primary' : 'warning'}>
                  {stockTypeText(r.type)} {r.quantity}
                </StatusTag>
              </div>
              <div className="row-meta">
                {r.warehouseName || '未指定仓库'} · {dateTime(r.createdAt)}
                {r.orderNo ? ` · 关联订单 ${r.orderNo}` : ''}
              </div>
            </div>
          ))
        )}
      </SectionCard>

      <div style={{ height: 12 }} />
    </div>
  )
}