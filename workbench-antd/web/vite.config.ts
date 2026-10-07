import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Vite 配置。
 *
 * `base: './'` 是硬要求：Capacitor 把 `dist/` 当本地资源用 `https://localhost` 提供，
 * 绝对路径 `/assets/xxx.js` 会 404，必须相对化。
 */
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    // WebView 里只跑一个壳，产物越小启动越快
    outDir: 'dist',
    assetsDir: 'assets',
    chunkSizeWarningLimit: 1200,
  },
  server: {
    host: true,
    port: 5173,
  },
})