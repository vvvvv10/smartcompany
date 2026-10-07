import type { CapacitorConfig } from '@capacitor/cli'

/**
 * Capacitor 配置。
 *
 * 两个关键项：
 *  - `CapacitorHttp.enabled`：**跨域是这个壳能不能跑的前提**。后端网关没有放行 CORS
 *    （`OPTIONS /api/users/me` 直接 403），开了这个开关后 fetch/XHR 由原生层发出去，
 *    完全不经过浏览器的同源策略，所以不用去改网关。
 *  - `server.cleartext`：后端是 `http://` 明文（workbench.example.com:8080），Android 9+ 默认
 *    禁止明文，不开这个开关所有请求都失败。
 */
const config: CapacitorConfig = {
  appId: 'com.example.workbench.antd',
  appName: '工作台',
  webDir: 'dist',
  server: {
    cleartext: true,
  },
  plugins: {
    CapacitorHttp: {
      enabled: true,
    },
  },
}

export default config