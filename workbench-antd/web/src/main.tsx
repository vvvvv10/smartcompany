import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { applyStatusBarHeight } from './lib/systembars'
// antd-mobile v5 的全局样式（CSS 变量、字体）必须手动引入一次
import 'antd-mobile/es/global'
import './styles.css'

// 必须在首帧前起跑：页头上内边距等这个值，晚了标题会先压在状态栏图标上闪一下。
// 它是异步的，不阻塞渲染；期间 CSS 退回 env(safe-area-inset-top)。
void applyStatusBarHeight()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)