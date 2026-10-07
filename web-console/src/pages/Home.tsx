import { ApiOutlined, DashboardOutlined } from '@ant-design/icons'
import { Tabs } from 'antd'
import { useState } from 'react'
import Dashboard from './Dashboard'
import GatewayWorkbench from './GatewayWorkbench'

/**
 * 首页：默认停在「网关工作台」——先看网关本身跑得健不健康、请求会被路由到哪，
 * 「数据看板」保留原有的用户运营概览，两个视图用 Tab 切换（与 CRM 首页同一套交互）。
 */
export default function Home() {
    const [tab, setTab] = useState('gateway')

    return (
        <div className="fade-in">
            <Tabs
                activeKey={tab}
                onChange={setTab}
                items={[
                    {
                        key: 'gateway',
                        label: (
                            <span>
                                <ApiOutlined />
                                网关工作台
                            </span>
                        ),
                        children: <GatewayWorkbench />
                    },
                    {
                        key: 'dashboard',
                        label: (
                            <span>
                                <DashboardOutlined />
                                数据看板
                            </span>
                        ),
                        children: <Dashboard />
                    }
                ]}
            />
        </div>
    )
}
