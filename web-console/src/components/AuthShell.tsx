import { ReactNode } from 'react'

/**
 * 登录/注册共用的左右分栏外壳。
 * 左侧是品牌区（窄屏自动隐藏），右侧放表单。
 */
export default function AuthShell({ children }: { children: ReactNode }) {
    return (
        <div className="auth-shell">
            <div className="auth-brand">
                <div className="auth-brand-logo">
                    <div className="auth-brand-mark">GW</div>
                    <span>统一网关 · 用户中心</span>
                </div>
                <div className="auth-brand-body">
                    <h1 className="auth-brand-title">
                        一个入口
                        <br />
                        管住所有服务的身份
                    </h1>
                    <p className="auth-brand-desc">
                        请求统一经过网关完成鉴权与身份注入，业务服务只信任网关注入的身份头，不自己解析令牌。
                    </p>
                    <div className="auth-brand-points">
                        <div className="auth-brand-point">
                            <span className="auth-brand-dot" />
                            JWT 双令牌，登出即吊销
                        </div>
                        <div className="auth-brand-point">
                            <span className="auth-brand-dot" />
                            伪造的身份头在网关被剥离
                        </div>
                        <div className="auth-brand-point">
                            <span className="auth-brand-dot" />
                            ADMIN / USER 角色隔离
                        </div>
                    </div>
                </div>
            </div>
            <div className="auth-form-side">
                <div className="auth-form-box">{children}</div>
            </div>
        </div>
    )
}
