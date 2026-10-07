import { useEffect, useMemo, useState } from 'react'
import { Button, CapsuleTabs, Dialog, PullToRefresh, TextArea } from 'antd-mobile'
import { api } from '../lib/api'
import { errorText, isForbidden, useAsync } from '../components/useAsync'
import { EmptyBox, ErrorBox, PermissionDenied, RowSkeleton, SectionCard, StatusTag } from '../components/ui'
import { stageText, typeText, type ApprovalItem, type Me } from '../lib/approvals'
import { dateTime, requestStatusText } from '../lib/format'
import { hapticSelect } from '../lib/haptics'
import { mineCollapsedCount } from '../lib/prefs'
import { toastErr, toastOk } from '../lib/toast'
import type { Profile } from '../lib/types'

/**
 * 审批中心：花名申请 + 权限申请合并成一个列表。
 *
 * 三块数据的可见性各不相同，页面必须分开处理：
 *  - **我的申请**：不设权限点（谁能看自己的），失败才是真失败
 *  - **审批列表**：花名要 `nickname:review`、权限要 `permission:review`，
 *    缺哪个后端就 403 哪个。两类都缺才给整块占位，只缺一类就静默只显示另一类
 *  - **可见范围**：管理员看全量；普通用户只留自己申请或自己审批过的
 */
export default function Approvals({
  profile,
  highlightId,
  onHighlightHandled,
  onReviewed,
}: {
  profile: Profile
  /** 通知点进来要定位的单子（null = 无） */
  highlightId?: number | null
  onHighlightHandled?: () => void
  onReviewed: () => void
}) {
  const [filter, setFilter] = useState<'PENDING' | 'ALL'>('PENDING')
  const [expanded, setExpanded] = useState(false)

  const me: Me = useMemo(
    () => ({
      account: profile.account,
      id: profile.id,
      isAdmin: (profile.gatewayRoles || []).some((r) => r.toUpperCase() === 'ADMIN'),
    }),
    [profile],
  )

  const mine = useAsync(async () => api.myApprovals(1, 20), [])

  const list = useAsync(async () => {
    const status = filter === 'PENDING' ? 'PENDING' : undefined
    const res = await api.approvalsRequests(status, 1, 50)
    return {
      items: res.list ?? [],
      scopedToMe: !me.isAdmin,
    }
  }, [filter, me])

  // 通知点进来的单子如果不在当前筛选里（常见：它是已办结状态），自动切「全部」，
  // 否则用户点完通知看到的是"没有待你处理的申请"，会以为通知指错了地方。
  useEffect(() => {
    if (highlightId && list.data && filter !== 'ALL') setFilter('ALL')
  }, [highlightId, list.data, filter])

  /**
   * 把通知指向的那一行滚到可视区中间。
   *
   * <p>不只是"高亮"就完事：审批页上面是「我的申请」，动辄二十来条历史，
   * 目标行在屏幕外的话用户点完通知还得自己滑半天，等于没提醒（原版就缺这一步）。
   * 用 `scrollIntoView` 而不是手算 offset——页面滚动容器是 `.page`，
   * 浏览器会自己算好该滚多少。
   */
  useEffect(() => {
    if (!highlightId) return
    // 目标行要等「申请列表」重新拉完才出现在 DOM 里（切分段会触发重新请求），
    // 固定次数的重试会落空——实测 80/240/600ms 三次都没等到元素，滚动位置一直是 0。
    // 所以改成"找到为止"，最多找 4 秒。
    const deadline = Date.now() + 6000
    const timer = setInterval(() => {
      // 审批视角的行优先（那是审批人要动手的地方），找不到再退回"我的申请"
      const el =
        document.getElementById(`list-row-${highlightId}`) ??
        document.getElementById(`mine-row-${highlightId}`)
      if (el) {
        el.scrollIntoView({ block: 'center' })
        clearInterval(timer)
        onHighlightHandled?.()
      } else if (Date.now() > deadline) {
        // 兜底：目标行始终没出现（被筛掉了/接口失败）也得把落点清掉，
        // 否则它会一直把筛选按在「全部」上
        clearInterval(timer)
        onHighlightHandled?.()
      }
    }, 250)
    return () => clearInterval(timer)
    // list.data 进了依赖：切分段会重新拉列表，慢的时候（实测后端能到几秒）
    // 元素是在数据回来之后才出现的，跟着数据变化重开一轮才不会漏掉
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlightId, list.data])

  const mineRows = mine.data ?? []
  const shownMine = expanded ? mineRows : mineRows.slice(0, mineCollapsedCount())

  const act = async (item: ApprovalItem, action: 'approve' | 'reject') => {
    if (action === 'reject') {
      // 驳回必须带理由：后端空理由直接 400。弹输入框而不是让用户先点了再吃报错
      const note = await askRejectNote()
      if (note === null) return
      if (!note.trim()) {
        toastErr('驳回必须填理由')
        return
      }
      try {
        await api.rejectApproval(item.id, note.trim())
        toastOk('已驳回')
      } catch (e) {
        toastErr(errorText(e))
        return
      }
    } else {
      try {
        await api.approveApproval(item.id)
        toastOk('已批准')
      } catch (e) {
        toastErr(errorText(e))
        return
      }
    }
    onReviewed()
    void list.reload()
    void mine.reload()
  }

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">审批</div>
        <div className="page-head-sub">花名变更与权限申请</div>
      </div>

      <SectionCard title={`我的申请（${mineRows.length}）`} extra="含已办结">
        {mine.error ? (
          <ErrorBox error={mine.error} onRetry={mine.reload} />
        ) : !mine.data ? (
          <RowSkeleton count={3} />
        ) : mineRows.length === 0 ? (
          <EmptyBox text="你还没有提交过申请" hint="在「我的」页可以申请改花名或申请权限" />
        ) : (
          <>
            {shownMine.map((item) => (
              <ApprovalRow
                key={`${item.type}-${item.id}`}
                item={item}
                domPrefix="mine"
                showAccount={false}
                highlight={item.id === highlightId}
              />
            ))}
            {!expanded && mineRows.length > shownMine.length && (
              <Button block size="small" fill="none" shape="rounded" onClick={() => setExpanded(true)}>
                展开全部 {mineRows.length} 条
              </Button>
            )}
            {/* 收起也要给：只出不进的折叠会让用户回不到顶上，长列表滚下去就找不着头了 */}
            {expanded && mineRows.length > mineCollapsedCount() && (
              <Button block size="small" fill="none" shape="rounded" onClick={() => setExpanded(false)}>
                收起
              </Button>
            )}
          </>
        )}
      </SectionCard>

      {/* 分段 key 直接用筛选值：早前写成 pending/all，而下面判断的是 'PENDING'，
          大小写对不上 → 一切换就退化成「全部」，待审批里混着已批准的单子。 */}
      <CapsuleTabs
        onChange={(k) => {
          hapticSelect()
          setFilter(k === 'ALL' ? 'ALL' : 'PENDING')
        }}
      >
        <CapsuleTabs.Tab key="PENDING" title="待审批">
          <ApprovalList state={list} onAct={act} highlightId={highlightId} />
        </CapsuleTabs.Tab>
        <CapsuleTabs.Tab key="ALL" title="全部">
          <ApprovalList state={list} onAct={act} highlightId={highlightId} />
        </CapsuleTabs.Tab>
      </CapsuleTabs>
    </div>
  )
}

/** 驳回理由输入弹窗。返回 null = 用户取消（取消不该被当成驳回）。 */
function askRejectNote(): Promise<string | null> {
  return new Promise((resolve) => {
    let value = ''
    const close = (result: string | null) => {
      resolve(result)
      // Dialog.show 是命令式 API，关掉后要把内容清空，否则下次打开还带着上次的字
      dialogRef.close()
      setTimeout(() => {
        value = ''
      }, 300)
    }
    const dialogRef = Dialog.show({
      title: '驳回理由',
      content: (
        <div>
          <TextArea
            placeholder="写清楚为什么不批，申请人会在「我的申请」里看到"
            rows={3}
            showCount
            maxLength={200}
            onChange={(v) => {
              value = v
            }}
          />
        </div>
      ),
      actions: [
        { key: 'cancel', text: '取消', onClick: () => close(null) },
        { key: 'ok', text: '确定', danger: true, onClick: () => close(value) },
      ],
    })
  })
}

function ApprovalList({
  state,
  onAct,
  highlightId,
}: {
  state: ReturnType<typeof useAsync<{
    items: ApprovalItem[]
    scopedToMe: boolean
  }>>
  onAct: (item: ApprovalItem, action: 'approve' | 'reject') => void
  highlightId?: number | null
}) {
  const { data, error, loading, reload } = state

  if (error && isForbidden(error)) {
    return (
      <SectionCard title="申请列表">
        <PermissionDenied message={errorText(error)} />
      </SectionCard>
    )
  }
  if (error) {
    return (
      <SectionCard title="申请列表">
        <ErrorBox error={error} onRetry={reload} />
      </SectionCard>
    )
  }
  if (!data) return loading ? <RowSkeleton count={4} /> : null

  if (data.items.length === 0) {
    return (
      <SectionCard title="申请列表（0）">
        <EmptyBox
          text={
            data.scopedToMe
              ? '没有待你处理的申请（这里只显示与你相关的单子）'
              : '没有待我审批的申请'
          }
          hint="花名与权限申请都在这里批，管理员可以一次处理完"
        />
      </SectionCard>
    )
  }

  return (
    <PullToRefresh onRefresh={reload}>
      <SectionCard title={`申请列表（${data.items.length}）`}>
        {data.items.map((item) => (
          <ApprovalRow
            key={`${item.type}-${item.id}`}
            item={item}
            domPrefix="list"
            showAccount
            onAct={onAct}
            highlight={item.id === highlightId}
          />
        ))}
        <div style={{ height: 12 }} />
      </SectionCard>
    </PullToRefresh>
  )
}

function ApprovalRow({
  item,
  domPrefix,
  showAccount,
  onAct,
  highlight,
}: {
  item: ApprovalItem
  /**
   * DOM id 前缀。**必须分区块**：两类单子的 id 是各自的自增序列，会撞号
   * （花名申请 #32 与权限申请 #32 同时存在），不加前缀页面里就有两个同 id 的元素，
   * 通知定位会命中"我的申请"里那条无关的记录（模拟器实测定位到了"mmX → mm"）。
   */
  domPrefix: 'mine' | 'list'
  showAccount: boolean
  onAct?: (item: ApprovalItem, action: 'approve' | 'reject') => void
  /** 通知点进来定位到这一行 */
  highlight?: boolean
}) {
  // 高亮是"辅助定位"，看一眼就该消失：渲染一帧后立刻撤掉，避免一直挂着让人以为有未读
  // 只负责"闪一下"。**这里不能消费落点**（onConsumed）：行渲染出来的那一刻
  // 滚动可能还没成功，一消费就把 highlightId 清成 null，滚动重试的定时器
  // 随即被拆掉——实测定位行一直在屏幕外。消费交给滚动那边做。
  const [flash, setFlash] = useState(!!highlight)
  useEffect(() => {
    if (!highlight) return
    setFlash(true)
    const t = setTimeout(() => setFlash(false), 4000)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlight])

  const domId = `${domPrefix}-row-${item.id}`
  const pending = item.status === 'PENDING'
  const title =
    item.type === 'NICKNAME' ? `${item.prevValue || '（空）'} → ${item.target}` : item.target
  return (
    <div id={domId} className={`row${flash ? ' row-flash' : ''}`}>
      <div className="row-top">
        <span className="row-title">{title}</span>
        <StatusTag color={statusColor(item.status)}>{requestStatusText(item.status)}</StatusTag>
      </div>
      <div className="row-meta">
        {showAccount ? `${item.nickname || item.account} · ` : ''}
        {typeText(item)}
        {item.type === 'PERMISSION' ? ` · ${item.module}` : ''}
        <br />
        {dateTime(item.createdAt)}
        {pending ? ` · ${stageText(item)}` : ''}
        {item.reviewNote ? ` · 理由：${item.reviewNote}` : ''}
      </div>
      {pending && onAct && item.canAct && (
        <div className="row-actions">
          <Button size="small" shape="rounded" color="primary" fill="none" onClick={() => onAct(item, 'approve')}>
            批准
          </Button>
          <Button size="small" shape="rounded" color="danger" fill="none" onClick={() => onAct(item, 'reject')}>
            驳回
          </Button>
        </div>
      )}
    </div>
  )
}

const statusColor = (s: string): 'primary' | 'success' | 'danger' => {
  if (s === 'APPROVED') return 'success'
  if (s === 'REJECTED') return 'danger'
  return 'primary'
}
