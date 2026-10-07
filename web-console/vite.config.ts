import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 生产环境下前端和网关同源（网关把 /** 转发到本容器），API 直接走相对路径即可。
// 本地 npm run dev 时才需要这个代理把 /api 打到服务器上。
export default defineConfig({
    plugins: [react()],
    server: {
        host: true,
        port: 5173,
        proxy: {
            '/api': {
                target: process.env.VITE_API_TARGET || 'http://127.0.0.1:8080',
                changeOrigin: true
            }
        }
    },
    build: {
        outDir: 'dist',
        chunkSizeWarningLimit: 1500
    }
})
