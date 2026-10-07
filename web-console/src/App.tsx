import { Navigate, Route, Routes } from 'react-router-dom'
import { Result, Spin } from 'antd'
import { ReactNode } from 'react'
import { AuthProvider, useAuth } from './auth/AuthContext'
import AppLayout from './components/AppLayout'
import Home from './pages/Home'
import CrmHome from './pages/crm/CrmHome'
import CustomersPage from './pages/crm/CustomersPage'
import OpportunitiesPage from './pages/crm/OpportunitiesPage'
import Login from './pages/Login'
import ProfilePage from './pages/ProfilePage'
import Register from './pages/Register'
import UsersPage from './pages/UsersPage'
import CorpDirectoryPage from './pages/CorpDirectoryPage'
import IntegrationsPage from './pages/IntegrationsPage'
import PermissionsPage from './pages/PermissionsPage'
import ApprovalsPage from './pages/ApprovalsPage'
import MyTeamPage from './pages/MyTeamPage'
import TmsPage from './pages/tms/TmsPage'
import WmsPage from './pages/wms/WmsPage'
import OmsPage from './pages/oms/OmsPage'
import IntlOrdersPage from './pages/intl/IntlOrdersPage'
import IntlShipmentsPage from './pages/intl/IntlShipmentsPage'
import IntlPackingPage from './pages/intl/IntlPackingPage'
import IntlCrmPage from './pages/intl/IntlCrmPage'

function RequireAuth({ children }: { children: ReactNode }) {
    const { profile, loading } = useAuth()
    if (loading) {
        return (
            <div className="center-screen">
                <Spin size="large" />
            </div>
        )
    }
    if (!profile) {
        return <Navigate to="/login" replace />
    }
    return <>{children}</>
}

/**
 * 按权限点守卫页面。
 *
 * 权限点由后端按角色矩阵解析（见 /api/users/me 的 permissionCodes），
 * 菜单显隐与这里用的是同一份数据，保证「看得见就能进」。
 *
 * `permission`（单个权限点）与 `anyOf`（若干权限点任一命中即放行）二选一：
 * 审批中心同时收花名（nickname:review）与权限申请（permission:review）两类单子，
 * 只认前者会把没有花名审批权、但有权限审批权的管理员挡在门外。
 */
function RequirePermission({
    permission,
    anyOf,
    children
}: {
    permission?: string
    anyOf?: string[]
    children: ReactNode
}) {
    const { hasPermission, profile } = useAuth()
    const need = anyOf ?? (permission ? [permission] : [])
    const allowed = need.length === 0 || need.some((code) => hasPermission(code))
    if (!allowed) {
        return (
            <Result
                status="403"
                title="403"
                subTitle={
                    anyOf
                        ? `该页面需要以下权限点之一：${anyOf.join(' / ')}，当前账号均未被授予（权限可在「权限中心」提交申请，由管理员在「审批中心」批准）`
                        : `该页面需要权限点 ${permission}，当前账号未被授予（权限可在「权限配置」页由管理员分配）`
                }
                extra={
                    profile && (
                        <div style={{ fontSize: 12, color: '#98a2b3' }}>
                            当前权限点：{profile.permissionCodes.join(', ') || '无'}
                        </div>
                    )
                }
            />
        )
    }
    return <>{children}</>
}

export default function App() {
    return (
        <AuthProvider>
            <Routes>
                <Route path="/login" element={<Login />} />
                <Route path="/register" element={<Register />} />
                <Route
                    path="/"
                    element={
                        <RequireAuth>
                            <AppLayout />
                        </RequireAuth>
                    }
                >
                    <Route
                        index
                        element={
                            <RequirePermission permission="dashboard:view">
                                <Home />
                            </RequirePermission>
                        }
                    />
                    <Route path="profile" element={<ProfilePage />} />
                    <Route path="crm" element={<CrmHome />} />
                    <Route path="crm/customers" element={<CustomersPage />} />
                    <Route path="crm/opportunities" element={<OpportunitiesPage />} />
                    <Route
                        path="users"
                        element={
                            <RequirePermission permission="user:list">
                                <UsersPage />
                            </RequirePermission>
                        }
                    />
                    {/* 企业通讯录（钉钉/企业微信）：与用户管理同一权限点（后端 /admin/corp/tree
                        也按 user:list 复验），看得见用户列表的人才看得到组织 */}
                    <Route
                        path="corp"
                        element={
                            <RequirePermission permission="user:list">
                                <CorpDirectoryPage />
                            </RequirePermission>
                        }
                    />
                    {/* 集成配置：钉钉/企微凭据维护（role:manage），后端同样复验——
                        菜单、路由、接口三方同一权限点 */}
                    <Route
                        path="integrations"
                        element={
                            <RequirePermission permission="role:manage">
                                <IntegrationsPage />
                            </RequirePermission>
                        }
                    />
                    {/* 权限中心放开给所有登录用户：「概览」页签（我的权限点 + 全目录
                        自助申请/删除）人人可见，角色管理、授权矩阵等管理页签仍在页内
                        按 role:manage 过滤——页面级守卫一刀切会把普通用户挡在
                        「申请权限」的门外。 */}
                    <Route path="permissions" element={<PermissionsPage />} />
                    {/* 审批中心两类单子并列：花名变更（nickname:review）、权限申请（permission:review），
                        任一命中即放行，页内页签再按各自权限显隐 */}
                    <Route
                        path="approvals"
                        element={
                            <RequirePermission anyOf={['nickname:review', 'permission:review']}>
                                <ApprovalsPage />
                            </RequirePermission>
                        }
                    />
                    {/* team:view 只能从 team_members.is_lead 派生，勾不到也删不掉，
                        所以普通成员既看不到菜单也进不来——门槛与数据范围是两道独立的关 */}
                    <Route
                        path="my-team"
                        element={
                            <RequirePermission permission="team:view">
                                <MyTeamPage />
                            </RequirePermission>
                        }
                    />
                    <Route
                        path="tms"
                        element={
                            <RequirePermission permission="tms:view">
                                <TmsPage />
                            </RequirePermission>
                        }
                    />
                    <Route
                        path="wms"
                        element={
                            <RequirePermission permission="wms:view">
                                <WmsPage />
                            </RequirePermission>
                        }
                    />
                    <Route
                        path="oms"
                        element={
                            <RequirePermission permission="oms:view">
                                <OmsPage />
                            </RequirePermission>
                        }
                    />
                    {/* 国际跨境物流（M1）。每页只需一个读取权限点就能进页面，
                        页内写按钮按各自的 :intl:edit 显隐——与现有「tms 页写操作另需
                        crm:write」是同一套写法。权限点只管前端显隐，后端一律登录即可读写。 */}
                    <Route
                        path="intl/export-orders"
                        element={
                            <RequirePermission permission="oms:intl:view">
                                <IntlOrdersPage />
                            </RequirePermission>
                        }
                    />
                    <Route
                        path="intl/packing"
                        element={
                            <RequirePermission permission="wms:intl:view">
                                <IntlPackingPage />
                            </RequirePermission>
                        }
                    />
                    <Route
                        path="intl/shipments"
                        element={
                            <RequirePermission permission="tms:intl:view">
                                <IntlShipmentsPage />
                            </RequirePermission>
                        }
                    />
                    <Route
                        path="intl/crm"
                        element={
                            <RequirePermission permission="crm:intl:view">
                                <IntlCrmPage />
                            </RequirePermission>
                        }
                    />
                </Route>
                <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
        </AuthProvider>
    )
}
