import { CapsuleTabs, PullToRefresh } from 'antd-mobile'
import { api } from '../lib/api'
import { errorText, isForbidden, useAsync } from '../components/useAsync'
import { EmptyBox, ErrorBox, FieldRow, PageSkeleton, PermissionDenied, RowSkeleton, SectionCard, StatCard, StatGrid, StatusTag } from '../components/ui'
import { hapticSelect } from '../lib/haptics'
import { toastErr } from '../lib/toast'
import { bytes, dateOnly, dateTime, dueText, followTypeText, greeting, money, moneyShort, roleText, stageText, today } from '../lib/format'
import type { AdminDashboard, CrmWorkbench, GatewayWorkbench, Profile } from '../lib/types'

/**
 * 系统管理页。
 *
 * 这一页装的是**系统侧数据**（用户/角色/网关）与**个人业务待办**两类，都按权限点
 * 分段：没有权限的分段**直接不出现**（而不是显示一个 403 占位）——占位留着只会
 * 让人以为自己漏开了什么开关。一个分段都看不到时才给整体说明。
 */
export default function Overview({ profile }: { profile: Profile }) {
  const perms = profile.permissionCodes || []
  const isAdmin = (profile.gatewayRoles || []).some((r) => r.toUpperCase() === 'ADMIN')
  const canOps = perms.includes('user:list') && perms.includes('dashboard:view')
  // 网关工作台按**角色**放行，不走权限点：线上实测 admin 的 permissionCodes 里
  // 没有任何 gateway:* 码，但接口返回 200；普通账号（墨言 5 个权限点）是 403。
  // 所以门控只能按 ADMIN 角色判，写成权限点会让管理员自己都看不到这一段。
  const canGateway = isAdmin
  const canBiz = perms.includes('crm:read')

  const tabs = [
    ...(canOps ? [{ key: 'ops', title: '运营数据' }] : []),
    ...(canGateway ? [{ key: 'gw', title: '网关' }] : []),
    ...(canBiz ? [{ key: 'biz', title: '我的待办' }] : []),
  ]

  const name = profile.nickname?.trim() || profile.account

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">
          {greeting()}，{name}
        </div>
        <div className="page-head-sub">
          {today()} ·{' '}
          {tabs.length === 0
            ? '当前账号没有可查看的数据权限'
            : canOps && canBiz
              ? '系统数据与业务数据，分段切换查看'
              : '按你的权限显示可查看的数据'}
        </div>
      </div>

      {tabs.length === 0 ? (
        <PermissionDenied message="当前账号没有任何数据查看权限（用户运营、网关、CRM 均不可见），需要管理员授权。" />
      ) : (
        <CapsuleTabs onChange={hapticSelect}>
          {tabs.map((t) => (
            <CapsuleTabs.Tab key={t.key} title={t.title}>
              {t.key === 'ops' && <OpsPanel />}
              {t.key === 'gw' && <GatewayPanel />}
              {t.key === 'biz' && <BizPanel />}
            </CapsuleTabs.Tab>
          ))}
        </CapsuleTabs>
      )}
    </div>
  )
}

function OpsPanel() {
  const { data, error, loading, reload } = useAsync<AdminDashboard>(() => api.adminDashboard(14))

  if (error && isForbidden(error)) {
    return <PermissionDenied message={errorText(error)} />
  }
  if (error) {
    return <ErrorBox error={error} onRetry={reload} />
  }
    if (!data) return loading ? <PageSkeleton cols={3} stats={6} rows={4} /> : null

  const { totals, roleDistribution, trend } = data
  const max = Math.max(1, ...trend.map((p) => p.registrations))

  return (
    <PullToRefresh onRefresh={reload}>
      <SectionCard title="用户与活跃" extra={`近 ${trend.length} 天`}>
        <StatGrid>
          <StatCard label="总用户" value={totals.totalUsers} />
          <StatCard label="活跃" value={totals.activeUsers} />
          <StatCard label="禁用" value={totals.disabledUsers} tone="danger" />
          <StatCard label="今日注册" value={totals.todayRegistrations} />
          <StatCard label="本周注册" value={totals.weekRegistrations} />
          <StatCard label="月活" value={totals.monthActiveUsers} />
        </StatGrid>
      </SectionCard>

      <SectionCard title="注册趋势">
        {trend.length === 0 ? (
          <EmptyBox text="这段时间没有注册记录" />
        ) : (
          <>
            <div className="bars">
              {trend.map((p) => (
                <div
                  key={p.date}
                  className="bar"
                  style={{ height: `${Math.max(4, (p.registrations / max) * 100)}%` }}
                  title={`${p.date} ${p.registrations}`}
                />
              ))}
            </div>
            <div className="bar-labels">
              <span>{dateOnly(trend[0]?.date)}</span>
              <span>{dateOnly(trend[trend.length - 1]?.date)}</span>
            </div>
          </>
        )}
      </SectionCard>

      <SectionCard title="角色分布">
        {roleDistribution.length === 0 ? (
          <EmptyBox text="暂无角色数据" />
        ) : (
          roleDistribution.map((r) => (
            <FieldRow key={r.roleCode} label={roleText(r.roleCode)}>
              {r.count} 人
            </FieldRow>
          ))
        )}
      </SectionCard>
    </PullToRefresh>
  )
}

function GatewayPanel() {
  const { data, error, loading, reload } = useAsync<GatewayWorkbench>(() => api.gatewayWorkbench())

  if (error && isForbidden(error)) return <PermissionDenied message={errorText(error)} />
  if (error) return <ErrorBox error={error} onRetry={reload} />
    if (!data) return loading ? <RowSkeleton count={4} /> : null

  const { gateway, routes, services } = data
  const healthy = gateway.reachable && gateway.status === 'UP'

  return (
    <PullToRefresh onRefresh={reload}>
      <SectionCard
        title="网关健康"
        extra={data.generatedAt ? `生成于 ${dateTime(new Date(data.generatedAt).toISOString())}` : undefined}
      >
        <div className="row-top" style={{ marginBottom: 8 }}>
          <StatusTag color={healthy ? 'success' : 'danger'}>{gateway.status || 'UNKNOWN'}</StatusTag>
          <span style={{ fontSize: 12, color: 'var(--adm-color-text-secondary)' }}>
            Redis {gateway.redisStatus || '—'}
            {gateway.redisVersion ? ` ${gateway.redisVersion}` : ''}
          </span>
        </div>
        <FieldRow label="磁盘">
          {gateway.diskFree !== null && gateway.diskFree !== undefined && gateway.diskTotal
            ? `${bytes(gateway.diskFree)} / ${bytes(gateway.diskTotal)}`
            : '—'}
        </FieldRow>
        {gateway.components.map((c) => (
          <FieldRow key={c.name} label={c.name}>
            <StatusTag color={c.status === 'UP' ? 'success' : 'danger'}>{c.status}</StatusTag>
          </FieldRow>
        ))}
      </SectionCard>

      <SectionCard title="路由" extra={`${routes.length} 条`}>
        {routes.length === 0 ? (
          <EmptyBox text="还没有配置路由" hint="网关注册中心里加路由后这里会自动出现" />
        ) : (
          routes.map((r) => (
            <div className="row" key={r.id}>
              <div className="row-top">
                <span className="row-title">{r.id}</span>
                <StatusTag color={r.lb ? 'primary' : undefined}>{r.lb ? '负载均衡' : '直连'}</StatusTag>
              </div>
              <div className="row-meta">
                {r.path ? `${r.path} → ` : ''}
                {r.target || '—'}
                {r.lb ? `（${r.instances} 实例）` : ''}
              </div>
            </div>
          ))
        )}
      </SectionCard>

      <SectionCard title="服务" extra={`${services.length} 个`}>
        {services.length === 0 ? (
          <EmptyBox text="暂无已注册服务" />
        ) : (
          services.map((s) => (
            <div className="row" key={s.name}>
              <div className="row-top">
                <span className="row-title">{s.name}</span>
                <StatusTag color={s.registered && s.instances > 0 ? 'success' : 'warning'}>
                  {s.instances} 实例
                </StatusTag>
              </div>
              {s.endpoints.length > 0 && <div className="row-meta">{s.endpoints.join('\n')}</div>}
            </div>
          ))
        )}
      </SectionCard>
    </PullToRefresh>
  )
}

function BizPanel() {
  const { data, error, loading, reload } = useAsync<CrmWorkbench>(() => api.workbench())

  if (error && isForbidden(error)) return <PermissionDenied message={errorText(error)} />
  if (error) return <ErrorBox error={error} onRetry={reload} />
    if (!data) return loading ? <PageSkeleton cols={3} stats={4} rows={4} /> : null

  const { stats, todos, opportunities, customers, followUps } = data

  return (
    <PullToRefresh onRefresh={reload}>
      <SectionCard title="我的业绩" extra="按负责人统计">
        <StatGrid>
          <StatCard label="我的客户" value={stats.customers} />
          <StatCard label="跟进中" value={stats.following} />
          <StatCard label="已成交" value={stats.deal} />
          <StatCard label="在途商机" value={stats.oppActive} hint={moneyShort(stats.oppAmount)} />
          <StatCard label="赢单金额" value={moneyShort(stats.winAmount)} />
          <StatCard label="本周新增" value={stats.weekNew} />
        </StatGrid>
      </SectionCard>

      <SectionCard title="跟进待办" extra={`逾期 ${stats.overdue}`}>
        {todos.length === 0 ? (
          <EmptyBox text="没有待跟进客户" hint="到客户详情里加一条跟进计划就会出现" />
        ) : (
          todos.map((t) => (
            <div className="row" key={t.id}>
              <div className="row-top">
                <span className="row-title">{t.customerName}</span>
                <StatusTag color={t.due === 'OVERDUE' ? 'danger' : t.due === 'TODAY' ? 'warning' : 'primary'}>
                  {dueText(t.due)}
                </StatusTag>
              </div>
              <div className="row-meta">
                {t.content || '待跟进'} · {dateTime(t.nextFollowAt)}
              </div>
            </div>
          ))
        )}
      </SectionCard>

      <SectionCard title="我的商机">
        {opportunities.length === 0 ? (
          <EmptyBox text="暂无在途商机" />
        ) : (
          opportunities.map((o) => (
            <div className="row" key={o.id}>
              <div className="row-top">
                <span className="row-title">{o.name}</span>
                <span style={{ fontSize: 14, fontWeight: 600 }}>{money(o.amount)}</span>
              </div>
              <div className="row-meta">
                {o.customerName} · {stageText(o.stage)} · 赢率 {o.probability}%
                {o.expectedCloseDate ? ` · 预计 ${dateOnly(o.expectedCloseDate)}` : ''}
              </div>
            </div>
          ))
        )}
      </SectionCard>

      <SectionCard title="最近跟进">
        {followUps.length === 0 ? (
          <EmptyBox text="最近没有跟进记录" />
        ) : (
          followUps.map((f, i) => (
            <div className="row" key={`${f.customerName}-${i}`}>
              <div className="row-top">
                <span className="row-title">{f.customerName}</span>
                <StatusTag>{followTypeText(f.type)}</StatusTag>
              </div>
              <div className="row-meta">
                {f.content}
                <br />
                {dateTime(f.createdAt)}
              </div>
            </div>
          ))
        )}
      </SectionCard>

      <SectionCard title="我的客户">
        {customers.length === 0 ? (
          <EmptyBox text="名下还没有客户" hint="在 CRM 页可以新建客户并填上负责人" />
        ) : (
          <div className="row-meta">
            {customers.map((c) => c.name).join('、')}
          </div>
        )}
      </SectionCard>

      <div style={{ height: 12 }} />
    </PullToRefresh>
  )
}

/** 刷新失败时给个可见反馈（PullToRefresh 静默失败会让人以为没生效）。 */
export function toastError(e: unknown) {
  toastErr(errorText(e))
}