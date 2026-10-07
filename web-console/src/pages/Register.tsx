import { Alert, Button, Form, Input, Modal, Space, Typography, message } from 'antd'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import axios from 'axios'
import { api, errorMessage } from '../api/client'
import { JoinResult } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import AuthShell from '../components/AuthShell'

interface CaptchaResponse {
    sent: boolean
    expiresIn: number
    /** 仅当后端开了 REVEAL_CAPTCHA 才返回，生产必须关闭 */
    code?: string
}

/** 注册成功后的可选步骤结果 */
type JoinStepState = 'idle' | 'joining-dingtalk' | 'joining-wecom' | 'done'

export default function Register() {
    const { register } = useAuth()
    const navigate = useNavigate()
    const [form] = Form.useForm()
    const [submitting, setSubmitting] = useState(false)
    const [sending, setSending] = useState(false)
    const [countdown, setCountdown] = useState(0)
    const [error, setError] = useState<string | null>(null)
    const [hint, setHint] = useState<string | null>(null)

    // 注册成功后弹出的「可选加入企业组织」步骤
    const [joinOpen, setJoinOpen] = useState(false)
    const [joinState, setJoinState] = useState<JoinStepState>('idle')
    const [joinResults, setJoinResults] = useState<JoinResult[]>([])

    const sendCaptcha = async () => {
        const phone = form.getFieldValue('phone')
        if (!phone) {
            message.warning('先填手机号')
            return
        }
        setSending(true)
        try {
            const res = await axios.post<CaptchaResponse>('/api/auth/captcha', { phone })
            if (res.data.code) {
                form.setFieldValue('captcha', res.data.code)
                setHint(`验证码已自动填入：${res.data.code}（后端开启了开发环境回显，生产需关闭）`)
            } else {
                setHint('验证码已发送，请查看短信')
            }
            setCountdown(60)
            const timer = setInterval(() => {
                setCountdown((prev) => {
                    if (prev <= 1) {
                        clearInterval(timer)
                        return 0
                    }
                    return prev - 1
                })
            }, 1000)
        } catch (err) {
            setError(errorMessage(err, '验证码发送失败'))
        } finally {
            setSending(false)
        }
    }

    const onFinish = async (values: { phone: string; password: string; captcha: string; nickname: string }) => {
        setSubmitting(true)
        setError(null)
        try {
            await register(values)
            // 注册成功 → 弹出可选的钉钉/企微加入步骤（可直接跳过）
            setJoinOpen(true)
        } catch (err) {
            setError(errorMessage(err, '注册失败'))
        } finally {
            setSubmitting(false)
        }
    }

    /** 加入指定渠道的企业组织；结果追加到列表，失败不阻断 */
    const joinChannel = async (channel: 'DINGTALK' | 'WECOM') => {
        setJoinState(channel === 'DINGTALK' ? 'joining-dingtalk' : 'joining-wecom')
        try {
            const res = await api.post<JoinResult>(`/users/me/organizations/${channel}`)
            setJoinResults((prev) => [...prev.filter((r) => r.channel !== res.data.channel), res.data])
            if (res.data.status === 'JOINED') {
                message.success(
                    channel === 'DINGTALK'
                        ? `已${res.data.mock ? '（mock）' : ''}加入企业钉钉`
                        : `已${res.data.mock ? '（mock）' : ''}加入企业微信`
                )
            } else {
                message.error(`加入失败：${res.data.detail}`)
            }
        } catch (err) {
            message.error(errorMessage(err, '加入企业组织失败'))
        } finally {
            setJoinState('idle')
        }
    }

    const finishJoin = () => {
        setJoinOpen(false)
        navigate('/', { replace: true })
    }

    const joining = joinState !== 'idle'

    return (
        <AuthShell>
            <h2 className="auth-form-title">注册</h2>
            <p className="auth-form-sub">注册即取花名，成功后自动登录并签发令牌</p>

            <Form form={form} layout="vertical" onFinish={onFinish} requiredMark={false}>
                <Form.Item
                    name="nickname"
                    label="花名"
                    rules={[
                        { required: true, message: '请取个花名' },
                        { min: 2, max: 32, message: '花名长度 2-32 字' }
                    ]}
                    extra="全局唯一的花名，代表你在系统里的身份，之后可在「我的身份」里改"
                >
                    <Input placeholder="如：风清扬、代码手艺人" allowClear maxLength={32} />
                </Form.Item>

                <Form.Item
                    name="phone"
                    label="手机号"
                    rules={[
                        { required: true, message: '请输入手机号' },
                        { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }
                    ]}
                >
                    <Input placeholder="11 位手机号" allowClear />
                </Form.Item>

                <Form.Item label="验证码" required style={{ marginBottom: 20 }}>
                    <Space.Compact style={{ width: '100%' }}>
                        <Form.Item name="captcha" noStyle rules={[{ required: true, message: '请输入验证码' }]}>
                            <Input placeholder="6 位验证码" />
                        </Form.Item>
                        <Button onClick={sendCaptcha} loading={sending} disabled={countdown > 0} style={{ width: 116 }}>
                            {countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}
                        </Button>
                    </Space.Compact>
                </Form.Item>

                <Form.Item
                    name="password"
                    label="密码"
                    rules={[
                        { required: true, message: '请输入密码' },
                        { min: 8, max: 64, message: '密码长度 8-64 位' }
                    ]}
                >
                    <Input.Password placeholder="至少 8 位" />
                </Form.Item>

                {hint && (
                    <Form.Item>
                        <Alert type="info" showIcon message={hint} />
                    </Form.Item>
                )}
                {error && (
                    <Form.Item>
                        <Alert type="error" showIcon message={error} />
                    </Form.Item>
                )}

                <Form.Item style={{ marginBottom: 0 }}>
                    <Button type="primary" htmlType="submit" block loading={submitting}>
                        注册并登录
                    </Button>
                </Form.Item>
            </Form>

            <div className="auth-hint">
                <Typography.Text style={{ fontSize: 12, color: '#667085' }}>
                    已有账号？<Link to="/login">去登录</Link>
                </Typography.Text>
            </div>

            {/* 注册成功后的可选步骤：加入钉钉 / 企业微信 */}
            <Modal
                title="欢迎加入！可选同步到企业组织"
                open={joinOpen}
                onCancel={finishJoin}
                onOk={finishJoin}
                okText={joinResults.length > 0 ? '完成' : '跳过'}
                cancelText="稍后再说"
                closable={!joining}
                maskClosable={false}
                cancelButtonProps={{ disabled: joining }}
                okButtonProps={{ disabled: joining }}
                width={480}
            >
                <Typography.Paragraph type="secondary" style={{ fontSize: 13, marginTop: 12 }}>
                    注册已完成，你现在已经是正式成员。可以把花名同步到公司钉钉 / 企业微信通讯录，
                    之后同事就能在企业组织里找到你。这一步完全可选，跳过也能随时在「我的身份」里补加。
                </Typography.Paragraph>

                <Space direction="vertical" style={{ width: '100%' }} size={12}>
                    <Button
                        block
                        loading={joinState === 'joining-dingtalk'}
                        disabled={joining && joinState !== 'joining-dingtalk'}
                        onClick={() => joinChannel('DINGTALK')}
                        style={{ height: 44, textAlign: 'left', paddingLeft: 16 }}
                    >
                        <Space>
                            <span style={{ fontSize: 16 }}>钉</span>
                            <span>加入企业钉钉</span>
                        </Space>
                    </Button>
                    <Button
                        block
                        loading={joinState === 'joining-wecom'}
                        disabled={joining && joinState !== 'joining-wecom'}
                        onClick={() => joinChannel('WECOM')}
                        style={{ height: 44, textAlign: 'left', paddingLeft: 16 }}
                    >
                        <Space>
                            <span style={{ fontSize: 16 }}>微</span>
                            <span>加入企业微信</span>
                        </Space>
                    </Button>
                </Space>

                {joinResults.map((result) => (
                    <Alert
                        key={result.channel}
                        style={{ marginTop: 12 }}
                        type={result.status === 'JOINED' ? 'success' : 'error'}
                        showIcon
                        message={`${result.channel === 'DINGTALK' ? '钉钉' : '企业微信'}：${result.status === 'JOINED' ? '已加入' : '加入失败'}`}
                        description={
                            <Typography.Text style={{ fontSize: 12 }}>
                                {result.detail}
                            </Typography.Text>
                        }
                    />
                ))}
            </Modal>
        </AuthShell>
    )
}
