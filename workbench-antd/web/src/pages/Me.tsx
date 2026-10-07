import { useEffect, useState } from 'react'
import { Button, Dialog, Input, List, Popup, Switch, TextArea } from 'antd-mobile'
import { api, clearTokens, DEFAULT_BASE_URL, getBaseUrl, setBaseUrl } from '../lib/api'
import { errorText, useAsync } from '../components/useAsync'
import { FieldRow, SectionCard, StatusTag } from '../components/ui'
import { roleText } from '../lib/format'
import { MODULES, type ModuleName } from '../lib/prefs'
import { applyableCodes, canSee, MODULE_DEFS, SYSTEM_MANAGE, type ModuleDef } from '../lib/modules'
import { hapticLight } from '../lib/haptics'
import { notifyStatus, setNotifyEnabled, startWatching } from '../lib/notify'
import { toastErr, toastInfo, toastOk } from '../lib/toast'
import { getAccessToken } from '../lib/api'
import type { PermissionRow, Profile } from '../lib/types'

/**
 * 一个可开关的业务模块行。
 *
 * 三种状态，说法各不相同：
 *  - 没权限：**不显示开关**，显示「无权限 + 申请权限」。Tab 也不会出现
 *    （见 `lib/modules.ts` 的说明）——"看得见却点进去全是 403"是最差的体验
 *  - 有权限、开关关：可以打开
 *  - 有权限、开关开：正在用
 */
function ModuleRow({
  def,
  perms,
  isAdmin,
  enabled,
  pendingCodes,
  onToggle,
  onApply,
}: {
  def: ModuleDef
  perms: string[]
  isAdmin: boolean
  enabled: boolean
  /** 我已提交且还在审批中的权限码 */
  pendingCodes: string[]
  onToggle: (on: boolean) => void
  onApply: () => void
}) {
  const allowed = canSee(def, perms, isAdmin)
  const pending = def.viewCodes.some((c) => pendingCodes.includes(c))

  if (!allowed) {
    return (
      <List.Item
        extra={
          pending ? (
            // 已经提过、还在审批中：给"审批中"而不是让人再点一次申请
            <StatusTag color="warning">审批中</StatusTag>
          ) : (
            <Button size="mini" shape="rounded" color="primary" fill="none" onClick={onApply}>
              申请权限
            </Button>
          )
        }
      >
        <div>
          <div>
            {def.title}
            {/* pending 时右侧已经有「审批中」了，行内不再重复挂一个标签 */}
            {!pending && <StatusTag color="warning">无权限</StatusTag>}
          </div>
          <div className="row-meta">
            {def.desc}。{pending ? '已提交申请，等管理员审批，通过后页签自动出现。' : '当前账号没有查看权限，页签不会显示。'}
          </div>
        </div>
      </List.Item>
    )
  }

  return (
    <List.Item
      extra={
        pending ? (
          <StatusTag color="warning">审批中</StatusTag>
        ) : (
          <Switch checked={enabled} onChange={onToggle} />
        )
      }
    >
      <div>
        <div>{def.title}</div>
        <div className="row-meta">
          {def.desc}。{pending ? '你已提交申请，等审批通过后这里会自动打开。' : '关闭后底部不显示该页签。'}
        </div>
      </div>
    </List.Item>
  )
}

/** 我的：身份、权限、模块、设置。 */
export default function Me({
  profile,
  mods,
  onToggleModule,
  onLoggedOut,
}: {
  profile: Profile
  mods: Record<ModuleName, boolean>
  onToggleModule: (m: ModuleName, on: boolean) => void
  onLoggedOut: () => void
}) {
  const [showPerms, setShowPerms] = useState(false)
  const [showUrl, setShowUrl] = useState(false)
  const [urlDraft, setUrlDraft] = useState(getBaseUrl())
  const [applyFor, setApplyFor] = useState<ModuleDef | null>(null)

  const perms = profile.permissionCodes || []
  const isAdmin = (profile.gatewayRoles || []).some((r) => r.toUpperCase() === 'ADMIN')
  const tenant = profile.tenantName || profile.dbTenantId

  // 后台提醒状态：开关 / 有没有通知权限 / 服务有没有在盯
  const [notify, setNotify] = useState({ enabled: true, canNotify: true, watching: false })
  useEffect(() => {
    void notifyStatus().then(setNotify)
  }, [])

  // 我的申请单：判断哪些权限码还在审批中，避免重复提交
  const mine = useAsync(() => api.myPermissions(), [])
  const pendingCodes = (mine.data?.requests ?? [])
    .filter((r) => r.status === 'PENDING')
    .map((r) => r.permissionCode)

  const logout = () => {
    void Dialog.confirm({
      title: '退出登录',
      content: '退出后需要重新输入账号密码。',
      confirmText: '退出',
    }).then(async (confirmed) => {
      if (!confirmed) return
      // 通知后端作废 refreshToken；失败也要清本地，否则用户会卡在半登录状态
      try {
        await api.logout()
      } catch (e) {
        toastErr(errorText(e))
      }
      clearTokens()
      onLoggedOut()
    })
  }

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">我的</div>
        <div className="page-head-sub">账号、功能模块与设置</div>
      </div>

      <SectionCard>
        <div className="me-top">
          <div className="me-avatar">{(profile.nickname || profile.account || '?').slice(0, 1)}</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="me-name">{profile.nickname || '未设置花名'}</div>
            <div className="me-account">{profile.account}</div>
            <div className="row-tags">
              {(profile.gatewayRoles || []).map((r) => (
                <StatusTag key={r} color="primary">
                  {roleText(r)}
                </StatusTag>
              ))}
            </div>
          </div>
        </div>
        <FieldRow label="租户">{tenant || '—'}</FieldRow>
        <FieldRow label="权限点">
          {perms.length} 个
          <Button size="mini" shape="rounded" fill="none" onClick={() => setShowPerms(true)} style={{ marginLeft: 8 }}>
            查看
          </Button>
        </FieldRow>
      </SectionCard>

      <SectionCard title="功能模块" extra="只影响这台设备">
        {/* 系统管理是固定页，没有开关；但没权限时同样给申请入口 */}
        {!canSee(SYSTEM_MANAGE, perms, isAdmin) && (
          <List>
            <ModuleRow
              def={SYSTEM_MANAGE}
              perms={perms}
              isAdmin={isAdmin}
              enabled={false}
              pendingCodes={pendingCodes}
              onToggle={() => undefined}
              onApply={() => setApplyFor(SYSTEM_MANAGE)}
            />
          </List>
        )}

        <List>
          {MODULES.map((m) => (
            <ModuleRow
              key={m}
              def={MODULE_DEFS[m]}
              perms={perms}
              isAdmin={isAdmin}
              enabled={mods[m]}
              pendingCodes={pendingCodes}
              onToggle={(on) => {
                hapticLight()
                onToggleModule(m, on)
              }}
              onApply={() => setApplyFor(MODULE_DEFS[m])}
            />
          ))}
        </List>
        <div className="row-meta" style={{ marginTop: 8 }}>
          没有权限的模块不会出现在底部导航里；点「申请权限」提交后由管理员审批，
          审批进度在「审批 → 我的申请」里看。
        </div>
      </SectionCard>

      <SectionCard title="提醒">
        <List>
          <List.Item
            extra={
              <Switch
                checked={notify.enabled}
                onChange={(on) => {
                  setNotify((prev) => ({ ...prev, enabled: on }))
                  void setNotifyEnabled(on)
                  // 打开开关时若服务没在盯（可能被系统杀过），顺手拉起来
                  if (on && !notify.watching) void startWatching(getAccessToken(), getBaseUrl())
                  toastOk(on ? '已开启待办提醒' : '已关闭待办提醒')
                }}
              />
            }
          >
            <div>
              <div>待办提醒</div>
              <div className="row-meta">
                后台盯着审批待办，有新单子发通知。
                {!notify.canNotify
                  ? '（系统通知权限未开启，提醒发不出来）'
                  : notify.enabled
                    ? notify.watching
                      ? '正在后台盯。'
                      : '已开启，后台服务稍后会启动。'
                    : '已关闭。'}
              </div>
            </div>
          </List.Item>
        </List>
      </SectionCard>

      <SectionCard title="设置">
        <List>
          <List.Item
            arrow
            extra={getBaseUrl()}
            onClick={() => {
              setUrlDraft(getBaseUrl())
              setShowUrl(true)
            }}
          >
            服务地址
          </List.Item>
        </List>
      </SectionCard>

      <div style={{ padding: '16px 12px' }}>
        <Button block shape="rounded" color="danger" fill="none" onClick={logout}>
          退出登录
        </Button>
      </div>

      <Popup visible={showPerms} onMaskClick={() => setShowPerms(false)} position="bottom" bodyStyle={{ maxHeight: '60%', borderRadius: 16 }}>
        <div className="detail">
          <div className="detail-head">
            <div className="detail-title">权限点（{perms.length}）</div>
            <div className="row-meta">页面上能看到的分段与按钮，都由这份权限点决定。</div>
          </div>
          {perms.length === 0 ? (
            <div className="row-meta">当前账号没有任何权限点。在下面的「功能模块」里可申请。</div>
          ) : (
            perms.map((p) => (
              <FieldRow key={p} label="权限点">
                <span style={{ wordBreak: 'break-all' }}>{p}</span>
              </FieldRow>
            ))
          )}
        </div>
      </Popup>

      <ApplyDialog
        def={applyFor}
        pendingCodes={pendingCodes}
        onClose={() => setApplyFor(null)}
        onSubmitted={() => {
          void mine.reload()
          toastOk('申请已提交，等管理员审批')
        }}
      />

      <Popup visible={showUrl} onMaskClick={() => setShowUrl(false)} position="bottom">
        <div style={{ padding: 20 }}>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 12 }}>服务地址</div>
          <Input value={urlDraft} placeholder={DEFAULT_BASE_URL} onChange={setUrlDraft} aria-label="服务地址" />
          <div style={{ margin: '8px 0 16px', fontSize: 12, color: 'var(--wb-text-3)' }}>
            只影响这台设备，不上传。换地址后重新登录一次。
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button block fill="none" onClick={() => setShowUrl(false)}>
              取消
            </Button>
            <Button
              block
              color="primary"
              onClick={() => {
                if (urlDraft.trim()) {
                  setBaseUrl(urlDraft)
                  setShowUrl(false)
                  toastInfo('服务地址已更新')
                }
              }}
            >
              保存
            </Button>
          </div>
        </div>
      </Popup>
    </div>
  )
}

/** 申请权限弹窗：可选该模块的权限点（目录里的全部），理由选填。 */
function ApplyDialog({
  def,
  pendingCodes,
  onClose,
  onSubmitted,
}: {
  def: ModuleDef | null
  pendingCodes: string[]
  onClose: () => void
  onSubmitted: () => void
}) {
  const [codes, setCodes] = useState<string[]>([])
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const catalog = useAsync(() => api.permissionCatalog(), [])
  const options: PermissionRow[] = def ? applyableCodes(catalog.data ?? [], def) : []

  // 打开时默认勾主码。用 key 强制重挂，避免上一次的勾选残留到下一个模块
  const submit = async () => {
    if (!def || codes.length === 0 || busy) return
    setBusy(true)
    try {
      await Promise.all(codes.map((c) => api.submitPermissionRequest(c, note)))
      setNote('')
      setCodes([])
      onClose()
      onSubmitted()
    } catch (e) {
      toastErr(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Popup visible={def !== null} onMaskClick={onClose} position="bottom" bodyStyle={{ borderRadius: 18 }}>
      {def && (
        <div className="detail" key={def.title}>
          <div className="detail-head">
            <div className="detail-title">申请「{def.title}」权限</div>
            <div className="row-meta">{def.desc}。提交后由管理员审批，通过后页签自动出现。</div>
          </div>

          {catalog.error ? (
            <div className="row-meta">权限点目录加载失败，可先只提交默认权限点。</div>
          ) : null}

          <div className="apply-list">
            {options.map((o) => {
              const on = codes.includes(o.code)
              const pending = pendingCodes.includes(o.code)
              return (
                <button
                  key={o.code}
                  type="button"
                  disabled={pending}
                  className={`apply-chip${on ? ' on' : ''}${pending ? ' pending' : ''}`}
                  onClick={() => setCodes((prev) => (on ? prev.filter((c) => c !== o.code) : [...prev, o.code]))}
                >
                  <span className="apply-chip-name">{o.name || o.code}</span>
                  <span className="apply-chip-code">
                    {o.code}
                    {pending ? ' · 审批中' : ''}
                  </span>
                </button>
              )
            })}
          </div>

          <div className="detail-block-title">申请理由（选填）</div>
          <TextArea
            placeholder="例如：需要处理 TMS 运单查询"
            rows={2}
            maxLength={200}
            showCount
            value={note}
            onChange={setNote}
          />

          <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
            <Button block fill="none" onClick={onClose}>
              取消
            </Button>
            <Button
              block
              color="primary"
              loading={busy}
              disabled={codes.length === 0}
              onClick={submit}
            >
              {codes.length > 1 ? `提交 ${codes.length} 项申请` : '提交申请'}
            </Button>
          </div>
          {codes.length === 0 && (
            <div className="row-meta" style={{ marginTop: 8, textAlign: 'center' }}>
              先选一个权限点
            </div>
          )}
        </div>
      )}
    </Popup>
  )
}