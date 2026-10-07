import { useMemo, useState } from 'react'
import {
  ActionSheet,
  CapsuleTabs,
  DotLoading,
  List,
  Popup,
  PullToRefresh,
  SearchBar,
  Switch,
} from 'antd-mobile'
import { api } from '../lib/api'
import { errorText, isForbidden, useAsync } from '../components/useAsync'
import { EmptyBox, ErrorBox, FieldRow, PermissionDenied, RowSkeleton, SectionCard, StatusTag } from '../components/ui'
import { hapticSelect } from '../lib/haptics'
import { toastErr, toastOk } from '../lib/toast'
import { customerStatusColor, keepByOwner, stageColor } from '../lib/crm'
import { customerLevelText, customerStatusText, dateOnly, dateTime, money, stageText } from '../lib/format'
import type { CrmFollowUp, Profile } from '../lib/types'

const STAGES = ['LEAD', 'PROPOSAL', 'NEGOTIATION', 'WON', 'LOST']

/** CRM 页：客户池 + 商机池。 */
export default function Crm({ profile }: { profile: Profile }) {
  const [onlyMine, setOnlyMine] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [customerId, setCustomerId] = useState<number | null>(null)
  const [opportunityId, setOpportunityId] = useState<number | null>(null)
  const canWrite = (profile.permissionCodes || []).includes('crm:write')
  const myName = profile.nickname?.trim() || profile.account

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">CRM</div>
        <div className="page-head-sub">客户、商机与跟进记录</div>
      </div>

      {/* 口径说明：这一段必须留着，否则用户会把「池子里的全部」当成「我负责的」 */}
      <div className="crm-note">
        <div className="crm-note-text">
          以下为<b>全员口径</b>；「系统管理」页的数字是你个人的待办。
        </div>
        <div className="crm-note-row">
          <span>只看我的</span>
          <Switch checked={onlyMine} onChange={setOnlyMine} />
        </div>
      </div>

      <div className="search-wrap">
        <SearchBar
          value={keyword}
          placeholder="搜索名称"
          onChange={setKeyword}
          onClear={() => setKeyword('')}
        />
      </div>

      {/* 注意：CapsuleTab 必须传字符串 key —— antd-mobile 的 traverseReactNode
          里 `typeof key !== 'string'` 就直接 return，不传的 tab 会被静默丢掉
          （整排分段控件都不显示，且不报错）。 */}
      <CapsuleTabs onChange={hapticSelect}>
        <CapsuleTabs.Tab key="customers" title="客户">
          <CustomerList
            keyword={keyword}
            onlyMine={onlyMine}
            myName={myName}
            onOpen={(id) => setCustomerId(id)}
          />
        </CapsuleTabs.Tab>
        <CapsuleTabs.Tab key="opportunities" title="商机">
          <OpportunityList
            keyword={keyword}
            onlyMine={onlyMine}
            myName={myName}
            canWrite={canWrite}
            onOpen={(id) => setOpportunityId(id)}
          />
        </CapsuleTabs.Tab>
      </CapsuleTabs>

      <CustomerDetail id={customerId} onClose={() => setCustomerId(null)} />
      <OpportunityDetail
        id={opportunityId}
        canWrite={canWrite}
        onClose={() => setOpportunityId(null)}
      />
    </div>
  )
}

function useCustomerList(keyword: string) {
  return useAsync(() => api.customers({ keyword: keyword.trim() || undefined, size: 50 }), [keyword])
}

function CustomerList({
  keyword,
  onlyMine,
  myName,
  onOpen,
}: {
  keyword: string
  onlyMine: boolean
  myName: string
  onOpen: (id: number) => void
}) {
  const { data, error, loading, reload } = useCustomerList(keyword)

  const rows = useMemo(() => {
    const list = data?.list ?? []
    return onlyMine ? keepByOwner(list, myName) : list
  }, [data, onlyMine, myName])

  if (error && isForbidden(error)) return <PermissionDenied message={errorText(error)} />
  if (error) return <ErrorBox error={error} onRetry={reload} />
    if (!data) return loading ? <RowSkeleton count={5} /> : null

  return (
    <PullToRefresh onRefresh={reload}>
      <SectionCard title="客户" extra={onlyMine ? `只看我的 ${rows.length}/${data.list.length}` : `${rows.length} 家`}>
        {rows.length === 0 ? (
          <EmptyBox
            text={onlyMine && data.list.length > 0 ? '池子里没有你负责的客户' : '没有符合条件的客户'}
            hint={keyword ? '换个关键词试试' : undefined}
          />
        ) : (
          rows.map((c) => (
            <div className="row" key={c.id} onClick={() => onOpen(c.id)}>
              <div className="row-top">
                <span className="row-title">{c.name}</span>
                <StatusTag color={customerStatusColor(c.status)}>{customerStatusText(c.status)}</StatusTag>
              </div>
              <div className="row-meta">
                {customerLevelText(c.level)}客户
                {c.ownerName ? ` · 负责人 ${c.ownerName}` : ' · 未分配负责人'}
                {c.contactCount > 0 ? ` · ${c.contactCount} 联系人` : ''}
                {c.opportunityCount > 0 ? ` · ${c.opportunityCount} 商机` : ''}
              </div>
            </div>
          ))
        )}
        <div style={{ height: 12 }} />
      </SectionCard>
    </PullToRefresh>
  )
}

function OpportunityList({
  keyword,
  onlyMine,
  myName,
  canWrite,
  onOpen,
}: {
  keyword: string
  onlyMine: boolean
  myName: string
  canWrite: boolean
  onOpen: (id: number) => void
}) {
  const { data, error, loading, reload } = useAsync(
    () => api.opportunities({ keyword: keyword.trim() || undefined, size: 50 }),
    [keyword],
  )

  const rows = useMemo(() => {
    const list = data?.list ?? []
    return onlyMine ? keepByOwner(list, myName) : list
  }, [data, onlyMine, myName])

  if (error && isForbidden(error)) return <PermissionDenied message={errorText(error)} />
  if (error) return <ErrorBox error={error} onRetry={reload} />
    if (!data) return loading ? <RowSkeleton count={5} /> : null

  return (
    <PullToRefresh onRefresh={reload}>
      <SectionCard
        title="商机"
        extra={onlyMine ? `只看我的 ${rows.length}/${data.list.length}` : `${rows.length} 条`}
      >
        {rows.length === 0 ? (
          <EmptyBox
            text={onlyMine && data.list.length > 0 ? '池子里没有你负责的商机' : '没有符合条件的商机'}
          />
        ) : (
          rows.map((o) => (
            <div className="row" key={o.id} onClick={() => onOpen(o.id)}>
              <div className="row-top">
                <span className="row-title">{o.name}</span>
                <span className="row-amount">{money(o.amount)}</span>
              </div>
              <div className="row-meta">
                {o.customerName} · 赢率 {o.probability ?? 0}%
                {o.ownerName ? ` · ${o.ownerName}` : ''}
                {o.expectedCloseDate ? ` · 预计 ${dateOnly(o.expectedCloseDate)}` : ''}
              </div>
              <div className="row-tags">
                <StatusTag color={stageColor(o.stage)}>{stageText(o.stage)}</StatusTag>
                {canWrite && <span style={{ fontSize: 12, color: 'var(--adm-color-primary)' }}>点开可推进阶段 ›</span>}
              </div>
            </div>
          ))
        )}
        <div style={{ height: 12 }} />
      </SectionCard>
    </PullToRefresh>
  )
}

function CustomerDetail({ id, onClose }: { id: number | null; onClose: () => void }) {
  // 详情、跟进、联系人、商机一起拉：后端没有聚合端点，串行会白等三轮
  const { data } = useAsync(async () => {
    if (id === null) return null
    const [customer, followUps, contacts, opps] = await Promise.all([
      api.customer(id),
      api.followUps(id),
      api.contacts(id),
      api.opportunities({ customerId: id, size: 20 }),
    ])
    return { customer, followUps, contacts, opps: opps.list }
  }, [id])

  return (
    <Popup
      visible={id !== null}
      onMaskClick={onClose}
      position="bottom"
      bodyStyle={{ height: '78%', borderRadius: 16 }}
    >
      {!data ? (
        <DotLoading style={{ padding: 40 }} />
      ) : (
        <div className="detail">
          <div className="detail-head">
            <div className="detail-title">{data.customer.name}</div>
            <div className="row-tags">
              <StatusTag color={customerStatusColor(data.customer.status)}>
                {customerStatusText(data.customer.status)}
              </StatusTag>
              <StatusTag>{customerLevelText(data.customer.level)}客户</StatusTag>
            </div>
          </div>

          <FieldRow label="行业">{data.customer.industry || '—'}</FieldRow>
          <FieldRow label="来源">{data.customer.source || '—'}</FieldRow>
          <FieldRow label="负责人">{data.customer.ownerName || '未分配'}</FieldRow>
          <FieldRow label="电话">
            {data.customer.phone ? <a href={`tel:${data.customer.phone}`}>{data.customer.phone}</a> : '—'}
          </FieldRow>
          <FieldRow label="邮箱">{data.customer.email || '—'}</FieldRow>
          <FieldRow label="地址">{data.customer.address || '—'}</FieldRow>
          <FieldRow label="备注">{data.customer.remark || '—'}</FieldRow>
          <FieldRow label="创建">{dateTime(data.customer.createdAt)}</FieldRow>

          <div className="detail-block-title">联系人（{data.contacts.length}）</div>
          {data.contacts.length === 0 ? (
            <div className="row-meta" style={{ padding: '4px 0' }}>
              暂无联系人
            </div>
          ) : (
            data.contacts.map((p) => (
              <FieldRow key={p.id} label={p.isPrimary ? '主要' : '联系人'}>
                {p.name}
                {p.position ? ` · ${p.position}` : ''}
                {p.phone ? ` · ${p.phone}` : ''}
              </FieldRow>
            ))
          )}

          <div className="detail-block-title">商机（{data.opps.length}）</div>
          {data.opps.length === 0 ? (
            <div className="row-meta" style={{ padding: '4px 0' }}>
              暂无商机
            </div>
          ) : (
            data.opps.map((o) => (
              <FieldRow key={o.id} label={stageText(o.stage)}>
                {o.name} · {money(o.amount)}
              </FieldRow>
            ))
          )}

          <div className="detail-block-title">跟进记录（{data.followUps.length}）</div>
          {data.followUps.length === 0 ? (
            <div className="row-meta" style={{ padding: '4px 0' }}>
              还没有跟进记录
            </div>
          ) : (
            data.followUps.map((f) => <FollowUpRow key={f.id} followUp={f} />)
          )}
        </div>
      )}
    </Popup>
  )
}

function FollowUpRow({ followUp }: { followUp: CrmFollowUp }) {
  return (
    <div className="row" style={{ borderBottom: '1px solid var(--adm-color-border)' }}>
      <div className="row-top">
        <span className="row-title" style={{ fontSize: 14 }}>
          {followUp.creatorName || '—'}
        </span>
        <span style={{ fontSize: 12, color: 'var(--adm-color-text-tertiary)' }}>
          {dateTime(followUp.createdAt)}
        </span>
      </div>
      <div className="row-meta">{followUp.content}</div>
    </div>
  )
}

function OpportunityDetail({ id, canWrite, onClose }: { id: number | null; canWrite: boolean; onClose: () => void }) {
  const { data: opp, reload } = useAsync(async () => {
    if (id === null) return null
    return api.opportunity(id)
  }, [id])

  const [moving, setMoving] = useState(false)

  /** 阶段流转：走专用端点 PATCH，后端会按阶段带出默认赢率。 */
  const move = async (stage: string) => {
    if (id === null || moving) return
    setMoving(true)
    try {
      await api.moveStage(id, stage)
      toastOk(`已推进到「${stageText(stage)}」`)
      void reload()
    } catch (e) {
      toastErr(errorText(e))
    } finally {
      setMoving(false)
    }
  }

  return (
    <Popup visible={id !== null} onMaskClick={onClose} position="bottom" bodyStyle={{ maxHeight: '70%', borderRadius: 16 }}>
      {!opp ? (
        <DotLoading style={{ padding: 40 }} />
      ) : (
        <div className="detail">
          <div className="detail-head">
            <div className="detail-title">{opp.name}</div>
            <div className="detail-amount">{money(opp.amount)}</div>
            <div className="row-tags">
              <StatusTag color={stageColor(opp.stage)}>{stageText(opp.stage)}</StatusTag>
              <StatusTag>赢率 {opp.probability ?? 0}%</StatusTag>
            </div>
          </div>

          <FieldRow label="客户">{opp.customerName}</FieldRow>
          <FieldRow label="负责人">{opp.ownerName || '未分配'}</FieldRow>
          <FieldRow label="预计成交">{opp.expectedCloseDate ? dateOnly(opp.expectedCloseDate) : '—'}</FieldRow>
          <FieldRow label="备注">{opp.remark || '—'}</FieldRow>
          <FieldRow label="创建">{dateTime(opp.createdAt)}</FieldRow>

          {canWrite ? (
            <List>
              <List.Item
                arrow
                onClick={() =>
                  ActionSheet.show({
                    // ActionSheet 没有 title 字段，顶部说明走 extra
                    extra: '推进到哪个阶段',
                    actions: STAGES.map((s) => ({
                      key: s,
                      text: s === opp.stage ? `${stageText(s)}（当前）` : stageText(s),
                      disabled: s === opp.stage,
                      onClick: () => void move(s),
                    })),
                    cancelText: '取消',
                  })
                }
                extra={stageText(opp.stage)}
              >
                推进阶段
              </List.Item>
            </List>
          ) : (
            <div className="row-meta">当前账号没有 `crm:write` 权限，只能查看。</div>
          )}
        </div>
      )}
    </Popup>
  )
}