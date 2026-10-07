import { Tabs } from 'antd'
import { DashboardOutlined, RocketOutlined } from '@ant-design/icons'
import { useState } from 'react'
import CrmDashboard from './CrmDashboard'
import CrmWorkbench from './CrmWorkbench'

/**
 * CRM 首页：默认停在「个人工作台」（只看我的数据），
 * 「团队看板」保留原有的全量经营概览，两个视图用 Tab 切换。
 */
export default function CrmHome() {
    const [tab, setTab] = useState('workbench')

    return (
        <div className="fade-in">
            <Tabs
                activeKey={tab}
                onChange={setTab}
                items={[
                    {
                        key: 'workbench',
                        label: <span><RocketOutlined />个人工作台</span>,
                        children: <CrmWorkbench />
                    },
                    {
                        key: 'team',
                        label: <span><DashboardOutlined />团队看板</span>,
                        children: <CrmDashboard />
                    }
                ]}
            />
        </div>
    )
}
