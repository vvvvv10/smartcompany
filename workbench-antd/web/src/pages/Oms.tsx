import { useState } from 'react'
import { CapsuleTabs, Popup, SearchBar } from 'antd-mobile'
import { Donut, RankBars } from '../components/charts'
import { hapticSelect } from '../lib/haptics'
import { errorText, isForbidden, useAsync } from '../components/useAsync'
import { EmptyBox, FieldRow, PermissionDenied, RowSkeleton, SectionCard, StatCard, StatGrid, StatSkeleton, StatusTag } from '../components/ui'
import { api } from '../lib/api'
import {
  categoryText,
  channelText,
  dateTime,
  money,
  moneyShort,
  omsStatusText,
  productStatusText,
  tmsOrderStatusText,
} from '../lib/format'
import { OMS_ORDER_STATUS } from '../lib/labels'
import type { OmsOrder } from '../lib/types'
import { ListBody } from './Tms'

/**
 * OMS 订单管理（只读）。
 *
 * 后端这一块是**服装行业**的多渠道订单（款号 + 颜色 + 尺码），所以商品名不能只
 * 当一个字符串显示——详情里按明细行展开，列表给「款号 + 颜色/尺码」两个维度。
 * 订单还带 TMS 运单状态与 WMS 出库件数，页面上直接显示，方便一眼看出卡在哪一环。
 */
export default function Oms() {
  const dash = useAsync(() => api.omsDashboard(), [])
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>('')
  const [detailId, setDetailId] = useState<number | null>(null)

  const orders = useAsync(
    () => api.omsOrders({ keyword: keyword.trim() || undefined, status: status || undefined }),
    [keyword, status],
  )
  const products = useAsync(() => api.omsProducts({}), [])

  if (dash.error && isForbidden(dash.error)) return <PermissionDenied message={errorText(dash.error)} />

  const d = dash.data

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">OMS 订单</div>
        <div className="page-head-sub">销售订单、商品与热销款</div>
      </div>

      {d ? (
        <>
          <SectionCard title="经营概览">
            <StatGrid>
              <StatCard label="订单总数" value={d.orders.total} hint={`已完成 ${d.orders.done}`} />
              <StatCard label="待发货" value={d.orders.toShip} hint={`已发货 ${d.orders.shipped}`} />
              <StatCard
                label="本月销售额"
                value={moneyShort(d.sales.monthAmount)}
                hint={`今日 ${moneyShort(d.sales.todayAmount)}`}
              />
            </StatGrid>
            <div style={{ marginTop: 16 }}>
              <Donut
                unit="订单"
                slices={Object.entries(OMS_ORDER_STATUS).map(([code, label], i) => ({
                  label,
                  value:
                    code === 'UNPAID'
                      ? d.orders.unpaid
                      : code === 'TO_SHIP'
                        ? d.orders.toShip
                        : code === 'SHIPPED'
                          ? d.orders.shipped
                          : code === 'DONE'
                            ? d.orders.done
                            : code === 'AFTER_SALE'
                              ? d.orders.afterSale
                              : d.orders.cancelled,
                  color: [
                    '#ff8f1f',
                    '#1677ff',
                    '#0fc6c2',
                    '#00b578',
                    '#f5483b',
                    '#c9cdd4',
                  ][i],
                }))}
              />
            </div>
          </SectionCard>

          <SectionCard title="渠道分布">
            {d.orders.channelDistribution.length === 0 ? (
              <EmptyBox text="暂无渠道数据" />
            ) : (
              <RankBars
                normalize="total"
                items={d.orders.channelDistribution
                  .slice()
                  .sort((a, b) => b.count - a.count)
                  .map((c) => ({
                    label: channelText(c.channel),
                    value: c.count,
                    hint: moneyShort(c.amount),
                  }))}
                unit=" 单"
              />
            )}
          </SectionCard>

          <SectionCard title="热销款 TOP5">
            {d.topStyles.length === 0 ? (
              <EmptyBox text="暂无热销款" />
            ) : (
              <RankBars
                items={d.topStyles.map((s) => ({
                  label: `${s.styleNo} ${s.name || ''}`.trim(),
                  value: s.qty,
                  hint: moneyShort(s.amount),
                }))}
                unit=" 件"
              />
            )}
          </SectionCard>
        </>
      ) : dash.loading ? (
        <SectionCard title="经营概览">
          <StatSkeleton count={3} />
        </SectionCard>
      ) : (
        <SectionCard title="经营概览">
          <EmptyBox text="看板数据加载失败" hint={errorText(dash.error)} />
        </SectionCard>
      )}

      <CapsuleTabs onChange={hapticSelect}>
        <CapsuleTabs.Tab key="orders" title="订单">
          <div className="search-wrap">
            <SearchBar
              value={keyword}
              placeholder="搜索订单号 / 客户"
              onChange={setKeyword}
              onClear={() => setKeyword('')}
            />
          </div>

          <div className="filter-row">
            {[{ code: '', label: '全部' }, ...Object.entries(OMS_ORDER_STATUS).map(([c, l]) => ({ code: c, label: l }))].map(
              (f) => (
                <button
                  key={f.code || 'all'}
                  className={`filter-chip${status === f.code ? ' on' : ''}`}
                  onClick={() => setStatus(f.code)}
                >
                  {f.label}
                </button>
              ),
            )}
          </div>

          <SectionCard title={`订单（${orders.data?.total ?? orders.data?.list.length ?? 0}）`}>
            {orders.error ? (
              <EmptyBox text="订单加载失败" hint={errorText(orders.error)} />
            ) : !orders.data ? (
              <RowSkeleton count={4} />
            ) : orders.data.list.length === 0 ? (
              <EmptyBox text={status || keyword ? '没有符合条件的订单' : '暂无订单'} />
            ) : (
              orders.data.list.map((o: OmsOrder) => (
                <div className="row" key={o.id} onClick={() => setDetailId(o.id)}>
                  <div className="row-top">
                    <span className="row-title">{o.orderNo}</span>
                    <span className="row-amount">{money(o.totalAmount)}</span>
                  </div>
                  <div className="row-meta">
                    {o.customerName || '—'} · {channelText(o.channel)} · {o.totalQty} 件
                    <br />
                    {dateTime(o.createdAt)}
                  </div>
                  <div className="row-tags">
                    <StatusTag color={orderColor(o.status)}>{omsStatusText(o.status)}</StatusTag>
                    {o.tmsStatus ? (
                      <StatusTag>运单 {tmsOrderStatusText(o.tmsStatus)}</StatusTag>
                    ) : null}
                    {o.outQty !== null && o.outQty !== undefined ? (
                      <StatusTag color="success">已出库 {o.outQty} 件</StatusTag>
                    ) : null}
                  </div>
                </div>
              ))
            )}
          </SectionCard>
        </CapsuleTabs.Tab>

        <CapsuleTabs.Tab key="products" title="商品">
          <SectionCard title={`商品 SKU（${products.data?.total ?? products.data?.list.length ?? 0}）`}>
            <ListBody
              state={products}
              empty="暂无商品"
              render={(p) => (
                <>
                  <div className="row-top">
                    <span className="row-title">{p.name || p.styleNo}</span>
                    <span className="row-amount">{money(p.price)}</span>
                  </div>
                  <div className="row-meta">
                    {p.styleNo} · {categoryText(p.category)}
                    {p.color ? ` · ${p.color}` : ''}
                    {p.size ? ` · ${p.size}` : ''}
                    {p.season ? ` · ${p.season}` : ''}
                  </div>
                  <div className="row-tags">
                    <StatusTag color={p.status === 'ON' ? 'success' : undefined}>
                      {productStatusText(p.status)}
                    </StatusTag>
                  </div>
                </>
              )}
            />
          </SectionCard>
        </CapsuleTabs.Tab>
      </CapsuleTabs>

      <OrderDetail id={detailId} onClose={() => setDetailId(null)} />
      <div style={{ height: 12 }} />
    </div>
  )
}

/** 订单详情：明细行只有详情接口才带，所以点开时现拉。 */
function OrderDetail({ id, onClose }: { id: number | null; onClose: () => void }) {
  const { data, error, loading } = useAsync(async () => {
    if (id === null) return null
    return api.omsOrderDetail(id)
  }, [id])

  return (
    <Popup
      visible={id !== null}
      onMaskClick={onClose}
      position="bottom"
      bodyStyle={{ maxHeight: '78%', borderRadius: 18 }}
    >
      {!data ? (
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--wb-text-3)' }}>
          {error ? errorText(error) : loading ? '加载中' : ''}
        </div>
      ) : (
        <div className="detail">
          <div className="detail-head">
            <div className="detail-title">{data.orderNo}</div>
            <div className="detail-amount">{money(data.totalAmount)}</div>
            <div className="row-tags">
              <StatusTag color={orderColor(data.status)}>{omsStatusText(data.status)}</StatusTag>
              <StatusTag>{channelText(data.channel)}</StatusTag>
              {data.tmsStatus ? (
                <StatusTag>运单 {tmsOrderStatusText(data.tmsStatus)}</StatusTag>
              ) : null}
              {data.outQty !== null && data.outQty !== undefined ? (
                <StatusTag color="success">已出库 {data.outQty} 件</StatusTag>
              ) : null}
            </div>
          </div>

          <FieldRow label="客户">{data.customerName || '—'}</FieldRow>
          <FieldRow label="电话">{data.phone || '—'}</FieldRow>
          <FieldRow label="地址">{data.address || '—'}</FieldRow>
          <FieldRow label="件数">{data.totalQty} 件</FieldRow>
          <FieldRow label="下单时间">{dateTime(data.createdAt)}</FieldRow>
          {data.tmsOrigin || data.tmsDestination ? (
            <FieldRow label="运单路线">
              {data.tmsOrigin || '—'} → {data.tmsDestination || '—'}
            </FieldRow>
          ) : null}
          {data.remark ? <FieldRow label="备注">{data.remark}</FieldRow> : null}

          <div className="detail-block-title">明细（{data.items?.length ?? 0}）</div>
          {!data.items || data.items.length === 0 ? (
            <div className="row-meta">没有明细行</div>
          ) : (
            data.items.map((it, i) => (
              <div className="row" key={`${it.styleNo}-${i}`}>
                <div className="row-top">
                  <span className="row-title">{it.productName || it.styleNo}</span>
                  <span className="row-amount">
                    {money(it.price * it.quantity)}
                  </span>
                </div>
                <div className="row-meta">
                  {it.styleNo} · {it.color || '—'} / {it.size || '—'} · {it.quantity} 件 ×{' '}
                  {money(it.price)}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </Popup>
  )
}

function orderColor(status: string): 'primary' | 'success' | 'warning' | 'danger' {
  if (status === 'DONE') return 'success'
  if (status === 'UNPAID') return 'warning'
  if (status === 'CANCELLED' || status === 'AFTER_SALE') return 'danger'
  return 'primary'
}