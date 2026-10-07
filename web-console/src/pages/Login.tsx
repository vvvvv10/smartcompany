import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Form, Input } from 'antd'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import AuthShell from '../components/AuthShell'

export default function Login() {
    const { login } = useAuth()
    const navigate = useNavigate()
    const [form] = Form.useForm()
    const [submitting, setSubmitting] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const onFinish = async (values: { account: string; password: string }) => {
        setSubmitting(true)
        setError(null)
        try {
            await login(values.account, values.password)
            navigate('/', { replace: true })
        } catch (err) {
            setError(errorMessage(err, '登录失败'))
        } finally {
            setSubmitting(false)
        }
    }

    const fillDemo = () => {
        form.setFieldsValue({ account: '13800000006', password: 'DEMO_PASSWORD' })
    }

    return (
        <AuthShell>
            <h2 className="auth-form-title">登录</h2>
            <p className="auth-form-sub">使用手机号和密码进入控制台</p>

            <Form form={form} layout="vertical" onFinish={onFinish} requiredMark={false} size="large">
                <Form.Item name="account" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
                    <Input prefix={<UserOutlined style={{ color: '#98a2b3' }} />} placeholder="手机号" allowClear />
                </Form.Item>
                <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password prefix={<LockOutlined style={{ color: '#98a2b3' }} />} placeholder="密码" />
                </Form.Item>

                {error && (
                    <Form.Item>
                        <Alert type="error" showIcon message={error} />
                    </Form.Item>
                )}

                <Form.Item style={{ marginBottom: 0 }}>
                    <Button type="primary" htmlType="submit" block loading={submitting}>
                        登录
                    </Button>
                </Form.Item>
            </Form>

            <div className="auth-hint">
                <div>
                    测试管理员：<code>13800000006</code> / <code>DEMO_PASSWORD</code>
                    <Button type="link" size="small" style={{ padding: '0 6px' }} onClick={fillDemo}>
                        一键填入
                    </Button>
                </div>
                <div>
                    没有账号？<Link to="/register">立即注册</Link>
                </div>
            </div>
        </AuthShell>
    )
}
