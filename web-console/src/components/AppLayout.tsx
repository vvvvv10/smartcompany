import {
    ApiOutlined,
    ApartmentOutlined,
    AppstoreOutlined,
    AuditOutlined,
    CarOutlined,
    ContainerOutlined,
    DownOutlined,
    GlobalOutlined,
    LockOutlined,
    LogoutOutlined,
    SettingOutlined,
    ShoppingCartOutlined,
    ShopOutlined,
    TeamOutlined,
    UserOutlined
} from '@ant-design/icons'
import { Avatar, Dropdown, Layout, Menu, Tag } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

const { Header, Sider, Content } = Layout

const PAGE_META: Record<string, { title: string; sub: string }> = {
    '/': { title: '网关工作台', sub: '网关路由、服务注册与运行健康' },
    '/profile': { title: '我的身份', sub: '花名变更申请与网关鉴权链路验证' },
    '/users': { title: '用户管理', sub: '账号新增、状态与角色分配' },
    '/approvals': { title: '审批中心', sub: '花名变更；权限申请两级审批（组织上一级 → 系统管理员）' },
    '/permissions': { title: '权限中心', sub: '权限点、角色与授权矩阵' },
    '/corp': { title: '企业通讯录', sub: '钉钉 / 企业微信组织架构只读视图；建号可勾选同步进企业' },
    '/integrations': { title: '集成配置', sub: '钉钉 / 企业微信 AppKey 等凭据维护，保存即热生效' },
    '/my-team': { title: '我的团队', sub: '自己带队的团队与成员（只读）' },
    '/crm': { title: 'CRM 工作台', sub: '我的今日待办与团队经营概览' },
    '/crm/customers': { title: '客户管理', sub: '客户、联系人与跟进记录' },
    '/crm/opportunities': { title: '商机管理', sub: '商机阶段流转与金额统计' },
    '/tms': { title: 'TMS 运输管理', sub: '运输订单、车辆与司机管理' },
    '/wms': { title: 'WMS 仓储管理', sub: '仓库、库存与出入库管理' },
    '/oms': { title: 'OMS 订单管理', sub: '服装商品、多渠道订单与销售统计' },
    '/intl/export-orders': { title: '出口订单（国际物流）', sub: '出口子单与母单：组批、拼箱与跨库状态对照' },
    '/intl/packing': { title: '备货装箱（国际物流）', sub: '拣货/装箱/贴标/交接、库存批次效期与在途库存' },
    '/intl/shipments': { title: '国际运单', sub: '国际运单、箱与分单、轨迹节点与出口报关' },
    '/intl/crm': { title: '客户与渠道（国际物流）', sub: '出口客户国际属性、货代渠道商与异常工单' }
}

export default function AppLayout() {
    const { profile, roles, hasPermission, logout, plugins } = useAuth()
    const navigate = useNavigate()
    const location = useLocation()

    // 插件化菜单：国际物流等非基础能力，由各服务 /api/<svc>/plugin.json 描述、
    // 用户中心的基础功能仍写死（见 menuItems）。插件菜单按 group.key 合并成一个分组。
    const pluginChildrenByGroup = new Map<string, { label: string; icon: string; children: { key: string; label: string }[] }>()
    plugins.forEach((p) => {
        const visible = p.menus.filter((m) => hasPermission(m.permission))
        if (visible.length === 0) return
        const g = pluginChildrenByGroup.get(p.group.key) ?? { label: p.group.label, icon: p.group.icon, children: [] }
        visible.forEach((m) => g.children.push({ key: m.path, label: m.label }))
        pluginChildrenByGroup.set(p.group.key, g)
    })
    const dynamicPluginMeta: Record<string, { title: string; sub: string }> = {}
    plugins.forEach((p) => p.menus.forEach((m) => (dynamicPluginMeta[m.path] = { title: m.metaTitle, sub: m.metaSub })))

    const meta = PAGE_META[location.pathname] ?? dynamicPluginMeta[location.pathname] ?? { title: '控制台', sub: '' }

    // 管理台组内的入口：逐条带权限门槛，凑不齐就整组不渲染（见下方 menuItems 注释）
    const consoleChildren = [
        // team:view 只能从 team_members.is_lead 派生，不进 permissions 表，
        // 因此它勾不出来也删不掉——只有真带队的人能看到这一项
        ...(hasPermission('team:view')
            ? [{ key: '/my-team', icon: <ApartmentOutlined />, label: '我的团队' }]
            : []),
        ...(hasPermission('user:list')
            ? [{ key: '/users', icon: <TeamOutlined />, label: '用户管理' }]
            : []),
        // 企业通讯录与用户管理同权限口径：能看到人员列表的人能看到组织架构
        ...(hasPermission('user:list')
            ? [{ key: '/corp', icon: <GlobalOutlined />, label: '企业通讯录' }]
            : []),
        // 集成配置（钉钉/企微凭据）：改凭据是管理员动作，与后端 role:manage 同口径
        ...(hasPermission('role:manage')
            ? [{ key: '/integrations', icon: <SettingOutlined />, label: '集成配置' }]
            : []),
        // 审批权：花名走 nickname:review、权限申请走 permission:review，
        // 与后端 require 的权限点一一对应，任一命中即显示入口
        ...(hasPermission('nickname:review') || hasPermission('permission:review')
            ? [{ key: '/approvals', icon: <AuditOutlined />, label: '审批中心' }]
            : [])
    ]

    // 菜单显隐与页面守卫用同一份权限点，保证「看得见就能进」
    const menuItems = [
        ...(hasPermission('dashboard:view')
            ? [{ key: '/', icon: <ApiOutlined />, label: '网关工作台' }]
            : []),
        ...(hasPermission('crm:read')
            ? [
                  {
                      key: 'crm-group',
                      icon: <ShopOutlined />,
                      label: 'CRM 管理',
                      children: [
                          { key: '/crm', label: 'CRM 工作台' },
                          { key: '/crm/customers', label: '客户管理' },
                          { key: '/crm/opportunities', label: '商机管理' }
                      ]
                  }
              ]
            : []),
        ...(hasPermission('tms:view')
            ? [{ key: '/tms', icon: <CarOutlined />, label: 'TMS 运输管理' }]
            : []),
        ...(hasPermission('wms:view')
            ? [{ key: '/wms', icon: <ContainerOutlined />, label: 'WMS 仓储管理' }]
            : []),
        ...(hasPermission('oms:view')
            ? [{ key: '/oms', icon: <ShoppingCartOutlined />, label: 'OMS 订单管理' }]
            : []),
        // 插件分组：国际物流等非基础功能从各服务的 plugin.json 拉取。
        // 一块插件服务停了/没装，这块菜单整组消失——基础功能不受影响。
        ...[...pluginChildrenByGroup.entries()].map(([key, g]) => ({
            key,
            icon: <GlobalOutlined />,
            label: g.label,
            children: g.children
        })),
        // 「管理台」是权限开关，和 CRM 管理 / 国际物流同一套写法：一个管理权限都
        // 没有的人，这一组整个不渲染（不是渲染出来再置灰——没有的东西就不占侧栏空间）。
        // 组里只放管理类入口；「我的身份」「权限中心」人人都有，关掉它们等于关掉
        // 权限自助入口，所以提到下面平铺，不进开关。
        ...(consoleChildren.length > 0
            ? [
                  {
                      key: 'console-group',
                      icon: <AppstoreOutlined />,
                      label: '管理台',
                      children: consoleChildren
                  }
              ]
            : []),
        // 个人入口平铺在侧栏底部（紧邻用户卡片）：人人可用，不属于任何权限开关
        { key: '/profile', icon: <UserOutlined />, label: '我的身份' },
        // 权限中心对所有登录用户开放：「权限点」页签是自助申请/移除权限的入口，
        // 管理页签（角色、矩阵等）在页内按 role:manage 显隐，菜单不再替页面把关
        { key: '/permissions', icon: <LockOutlined />, label: '权限中心' }
    ]

    const handleLogout = async () => {
        await logout()
        navigate('/login', { replace: true })
    }

    const roleTag = (role: string) => (
        <Tag key={role} color={role === 'ADMIN' ? 'purple' : 'blue'} style={{ marginInlineEnd: 4 }}>
            {role}
        </Tag>
    )

    return (
        <Layout style={{ minHeight: '100vh' }}>
            <Sider className="app-sider" theme="light" breakpoint="lg" width={216}>
                <div className="brand">
                    <div className="brand-mark">GW</div>
                    <div>
                        <div className="brand-name">统一网关</div>
                        <div className="brand-sub">用户中心控制台</div>
                    </div>
                </div>
                <Menu
                    mode="inline"
                    // 默认展开两个分组：合组是为了「收拢到一起」，默认收起就会让原本
                    // 一次点击能到的页面变成两次，那是拿易用性换整齐
                    defaultOpenKeys={['crm-group', 'console-group', 'intl-group']}
                    selectedKeys={[location.pathname]}
                    items={menuItems}
                    onClick={({ key }) => navigate(key)}
                    style={{ border: 'none' }}
                />
                <div className="sider-footer">
                    <div className="sider-user">
                        <Avatar
                            size={34}
                            style={{ background: 'linear-gradient(135deg, #4f46e5 0%, #7c3aed 100%)', flex: 'none' }}
                        >
                            {(profile?.nickname || profile?.account || 'U').slice(0, 1)}
                        </Avatar>
                        <div style={{ overflow: 'hidden' }}>
                            <div className="sider-user-name">{profile?.nickname || profile?.account}</div>
                            <div className="sider-user-role">{roles.join(' · ') || '无角色'}</div>
                        </div>
                    </div>
                </div>
            </Sider>

            <Layout>
                <Header className="app-header">
                    <div>
                        <div className="header-title">{meta.title}</div>
                        <div className="header-sub">{meta.sub}</div>
                    </div>
                    <Dropdown
                        trigger={['click']}
                        menu={{
                            items: [
                                {
                                    key: 'logout',
                                    icon: <LogoutOutlined />,
                                    label: '退出登录',
                                    onClick: handleLogout
                                }
                            ]
                        }}
                    >
                        <div className="header-user">
                            {roles.map(roleTag)}
                            <Avatar size={30} style={{ background: '#eef2ff', color: '#4f46e5' }}>
                                {(profile?.nickname || profile?.account || 'U').slice(0, 1)}
                            </Avatar>
                            <span style={{ fontSize: 13, color: '#475467' }}>{profile?.account}</span>
                            <DownOutlined style={{ fontSize: 10, color: '#98a2b3' }} />
                        </div>
                    </Dropdown>
                </Header>
                <Content className="app-content">
                    <Outlet />
                </Content>
            </Layout>
        </Layout>
    )
}
