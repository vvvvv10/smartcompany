import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'
import { themeConfig } from './theme'

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
    <ConfigProvider locale={zhCN} theme={themeConfig}>
        <BrowserRouter>
            <App />
        </BrowserRouter>
    </ConfigProvider>
)
