import { useCallback, useEffect, useState } from 'react'
import { TabBar } from 'antd-mobile'
import { api, clearTokens, getAccessToken, setUnauthorizedHandler } from './lib/api'
import type { Profile } from './lib/types'
import { isModuleEnabled, type ModuleName } from './lib/prefs'
import { canSee, MODULE_DEFS, SYSTEM_MANAGE } from './lib/modules'
import { getBaseUrl } from './lib/api'
import { hapticLight } from './lib/haptics'
import { startWatching, stopWatching, watchDeepLink } from './lib/notify'
import Login from './pages/Login'
import Overview from './pages/Overview'
import Crm from './pages/Crm'
import Approvals from './pages/Approvals'
import Me from './pages/Me'
import Tms from './pages/Tms'
import Wms from './pages/Wms'
import Oms from './pages/Oms'
import CorpDirectory from './pages/CorpDirectory'
import { IconApproval, IconCorp, IconCrm, IconMe, IconOms, IconSystem, IconTms, IconWms } from './components/icons'

/**
 * 应用外壳：登录态 + 底部 Tab。
 *
 * Tab 顺序与原生版一致：**系统管理 / CRM / 审批 / 我的**。CRM 是可选模块
 * （第 4 个功能模块开关，默认关），其余三个是固定的。
 */
export default function App() {
  const [booting, setBooting] = useState(true)
  const [profile, setProfile] = useState<Profile | null>(null)
  const [active, setActive] = useState('overview') // 首选项，会被下面的兜底纠正
  // 四个业务模块开关都存在本机（与账号无关）：关掉 CRM 不该把 TMS 也带走
  const [mods, setMods] = useState<Record<ModuleName, boolean>>({
    tms: isModuleEnabled('tms'),
    wms: isModuleEnabled('wms'),
    oms: isModuleEnabled('oms'),
    crm: isModuleEnabled('crm'),
  })
  // 审批 Tab 的徽标：两类待办之和，批完数字要立刻掉下去
  const [pending, setPending] = useState(0)
  /** 通知点进来要定位的单子 id；用完即置空（定位是辅助，不是标记已读） */
  const [highlightId, setHighlightId] = useState<number | null>(null)

  useEffect(() => {
    setUnauthorizedHandler(() => setProfile(null))
    return () => setUnauthorizedHandler(null)
  }, [])

  // 冷启动：有 token 就直接拉资料；拉不到（token 过期/后端挂了）就回登录页，
  // 别进「空资料 + 满屏 403」的坏状态。
  useEffect(() => {
    let alive = true
    if (!getAccessToken()) {
      setBooting(false)
      return
    }
    api
      .me()
      .then((p) => {
        if (!alive) return
        setProfile(p)
        // 后台待办提醒：把凭据交给原生前台服务（服务读不到 localStorage）
        void startWatching(getAccessToken(), getBaseUrl())
      })
      .catch(() => alive && clearTokens())
      .finally(() => alive && setBooting(false))
    return () => {
      alive = false
    }
  }, [])

  const refreshPending = useCallback(async () => {
    try {
      const n = await api.approvalsPendingCount()
      setPending(n?.count ?? 0)
    } catch {
      // 徽标拿不到就不显示，不打扰用户（没有审批权的人这里必然 403）
      setPending(0)
    }
  }, [])

  useEffect(() => {
    if (!profile) return
    void refreshPending()
    const timer = setInterval(() => void refreshPending(), 60_000)
    return () => clearInterval(timer)
  }, [profile, refreshPending])

  /**
   * 通知点击落点：切到审批页并高亮对应单子（原生版是 ApprovalDeepLink，这里是等价物）。
   *
   * **必须写在所有 early return 之前**——React 要求同一组件每次渲染的 hooks 顺序
   * 一致，写在 `if (!profile) return <Login/>` 后面的话，登录前后 hooks 数量不同，
   * 直接抛 error #310 崩成白屏（模拟器实测踩过）。
   */
  useEffect(() => {
    if (!profile) return
    return watchDeepLink((id) => {
      setHighlightId(id)
      setActive('approvals')
    })
  }, [profile])

  if (booting) return null

  if (!profile) {
    return (
      <Login
        onLoggedIn={(p) => {
          setProfile(p)
          // 登录即开始后台盯待办
          void startWatching(getAccessToken(), getBaseUrl())
          setMods({
            tms: isModuleEnabled('tms'),
            wms: isModuleEnabled('wms'),
            oms: isModuleEnabled('oms'),
            crm: isModuleEnabled('crm'),
          })
        }}
      />
    )
  }

  // Tab 显隐 = 本机开关 ∧ 权限点（规则见 lib/modules.ts）。
  // 普通用户没有的模块**不占 Tab 位**——进去才发现整页 403 是最差的体验；
  // 想开就去「我的 → 功能模块 → 申请权限」。
  const perms = profile.permissionCodes || []
  const isAdmin = (profile.gatewayRoles || []).some((r) => r.toUpperCase() === 'ADMIN')
  const allowed = (m: ModuleName) => canSee(MODULE_DEFS[m], perms, isAdmin)

  // 顺序与原生版一致，另插一个全员可见的「通讯录」：系统管理 / TMS / WMS / OMS / CRM / 通讯录 / 审批 / 我的
  const tabs = [
    ...(canSee(SYSTEM_MANAGE, perms, isAdmin)
      ? [{ key: 'overview', title: '系统管理', icon: <IconSystem /> }]
      : []),
    ...(mods.tms && allowed('tms') ? [{ key: 'tms', title: 'TMS', icon: <IconTms /> }] : []),
    ...(mods.wms && allowed('wms') ? [{ key: 'wms', title: 'WMS', icon: <IconWms /> }] : []),
    ...(mods.oms && allowed('oms') ? [{ key: 'oms', title: 'OMS', icon: <IconOms /> }] : []),
    ...(mods.crm && allowed('crm') ? [{ key: 'crm', title: 'CRM', icon: <IconCrm /> }] : []),
    // 企业通讯录：固定入口、全员可见（后端组织树也只收登录态），与审批/我的同级
    { key: 'corp', title: '通讯录', icon: <IconCorp /> },
    { key: 'approvals', title: '审批', icon: <IconApproval />, badge: pending > 0 ? String(pending) : undefined },
    { key: 'me', title: '我的', icon: <IconMe /> },
  ]
  // 当前页被隐藏（模块关掉 / 权限被收回）时退回**第一个可见 Tab**。
  // 注意兜底不能写死 'overview'：系统管理也可能因权限不足而不存在，写死会导致
  // activeKey 指向一个不存在的 key，所有页面都不渲染 —— 整屏白屏（模拟器实测踩过）。
  const activeKey = tabs.some((t) => t.key === active) ? active : tabs[0].key

  return (
    <>
      {activeKey === 'overview' && <Overview profile={profile} />}
      {activeKey === 'tms' && mods.tms && <Tms />}
      {activeKey === 'wms' && mods.wms && <Wms />}
      {activeKey === 'oms' && mods.oms && <Oms />}
      {activeKey === 'crm' && mods.crm && <Crm profile={profile} />}
      {activeKey === 'corp' && <CorpDirectory />}
      {activeKey === 'approvals' && (
        <Approvals
          profile={profile}
          highlightId={highlightId}
          onHighlightHandled={() => setHighlightId(null)}
          onReviewed={() => {
            void refreshPending()
          }}
        />
      )}
      {activeKey === 'me' && (
        <Me
          profile={profile}
          mods={mods}
          onToggleModule={(m, on) => {
            setMods((prev) => ({ ...prev, [m]: on }))
            // 关掉的正好是当前页，退回第一个 Tab
            const current: string = activeKey
            if (!on && current === m) setActive('overview')
          }}
          onLoggedOut={() => {
            stopWatching()
            clearTokens()
            setProfile(null)
          }}
        />
      )}

      {/* antd-mobile 5.43 的 TabBar 是文档流布局（没有 fixed 属性），自己固定到底部 */}
      <div className="tabbar-dock">
        <TabBar activeKey={activeKey} onChange={(k) => { hapticLight(); setActive(k) }} safeArea>
          {tabs.map((t) => (
            <TabBar.Item key={t.key} title={t.title} icon={t.icon} badge={t.badge as never} />
          ))}
        </TabBar>
      </div>
    </>
  )
}