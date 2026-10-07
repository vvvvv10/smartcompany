import { useState } from 'react'
import { Button, Form, Input, Popup } from 'antd-mobile'
import { hapticLight } from '../lib/haptics'
import { toastErr, toastInfo } from '../lib/toast'
import { api, DEFAULT_BASE_URL, getBaseUrl, setBaseUrl, setTokens } from '../lib/api'
import type { Profile } from '../lib/types'

/**
 * 测试账号一键填入。
 *
 * 与 web 管理台登录页同一套做法（`web-console/src/pages/Login.tsx` 的
 * `fillDemo`）：把演示账号摆在明面上，省得每次手打 11 位手机号。
 *
 * **每个账号带自己的密码**：13800000006 是 initdb 种的，密码 `DEMO_PASSWORD`；
 * 13800000006 是后来注册出来的（花名「搞事情」，USER 角色），密码不同——
 * 之前这里对所有账号统一填 `DEMO_PASSWORD`，那个号一点就登录失败，实际是
 * "填了但用不了"。现在按账号各填各的。
 *
 * 安全上说一句：这是明文写进 JS bundle 的，任何人反编译 APK 都能看到。
 * 之所以可以接受——都是测试账号，`DEMO_PASSWORD` 同样明文躺在 web 管理台里，
 * 而这两条数据本来就是演示用的。**接生产账号前必须换成"填账号不填密码"。**
 */
const QUICK_FILL: { account: string; label: string; role: string; password: string }[] = [
  { account: '13800000006', label: '风清扬', role: '管理员', password: 'DEMO_PASSWORD' },
  { account: '13800000006', label: '搞事情', role: '普通用户', password: 'DEMO_PASSWORD' },
]

/** 登录页。 */
export default function Login({ onLoggedIn }: { onLoggedIn: (p: Profile) => void }) {
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)
  const [showUrl, setShowUrl] = useState(false)
  const [urlDraft, setUrlDraft] = useState(getBaseUrl())

  const submit = async () => {
    if (busy) return
    const { account, password } = await form.validateFields()
    setBusy(true)
    try {
      const pair = await api.login(account.trim(), password)
      setTokens(pair)
      const profile = await api.me()
      onLoggedIn(profile)
    } catch (e: any) {
      toastErr(e?.message || '登录失败')
    } finally {
      setBusy(false)
    }
  }

  const saveUrl = () => {
    const next = urlDraft.trim()
    if (!next) return
    setBaseUrl(next)
    setShowUrl(false)
    toastInfo('服务地址已更新')
  }

  return (
    <div className="login">
      {/* 品牌区：整页渐变底 + 白色标题，表单浮起来压住渐变下沿 */}
      <div className="login-hero">
        <div className="login-brand">工作台</div>
        <div className="login-sub">个人工作台 · 审批 · CRM</div>
      </div>

      <div className="login-card">
        <Form
          form={form}
          footer={
            <Button
              block
              color="primary"
              shape="rounded"
              size="large"
              loading={busy}
              onClick={submit}
              style={{ marginTop: 6, fontWeight: 600 }}
            >
              登 录
            </Button>
          }
        >
          <Form.Item name="account" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
            <Input
              placeholder="手机号 / 登录名"
              clearable
              autoComplete="username"
              style={{ background: '#f7f8fa', fontWeight: 500, borderRadius: 10 }}
            />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input
              placeholder="密码"
              type="password"
              clearable
              autoComplete="current-password"
              style={{ background: '#f7f8fa', borderRadius: 10 }}
            />
          </Form.Item>
        </Form>

        <div className="quick-fill">
          <div className="quick-fill-label">测试账号（一键填入）</div>
          <div className="quick-fill-row">
            {QUICK_FILL.map((a) => (
              <button
                key={a.account}
                type="button"
                className="quick-fill-chip"
                onClick={() => {
                hapticLight()
                form.setFieldsValue({ account: a.account, password: a.password })
              }}
              >
                <span className="quick-fill-account">{a.account}</span>
                <span className="quick-fill-meta">
                  {a.label} · {a.role}
                </span>
              </button>
            ))}
          </div>
        </div>

        <div className="login-foot">
          服务地址：{getBaseUrl()}
          <br />
          <Button
            size="mini"
            fill="none"
            onClick={() => {
              setUrlDraft(getBaseUrl())
              setShowUrl(true)
            }}
          >
            修改服务地址
          </Button>
        </div>
      </div>

      <Popup visible={showUrl} onMaskClick={() => setShowUrl(false)} position="bottom">
        <div style={{ padding: 20 }}>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 12 }}>服务地址</div>
          <Input
            value={urlDraft}
            placeholder={DEFAULT_BASE_URL}
            onChange={setUrlDraft}
            aria-label="服务地址"
          />
          <div style={{ margin: '8px 0 16px', fontSize: 12, color: 'var(--wb-text-3)' }}>
            改完点「保存」生效。只影响这台设备，不上传。
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button block fill="none" onClick={() => setShowUrl(false)}>
              取消
            </Button>
            <Button block color="primary" onClick={saveUrl}>
              保存
            </Button>
          </div>
        </div>
      </Popup>
    </div>
  )
}