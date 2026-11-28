import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：前端请求统一走 /api 前缀转发到后端 8080，浏览器视角同源，无 CORS 问题；
// SSE 走 http-proxy 默认不缓冲，流式可用
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 局域网访问：手机连同一 WiFi 可访问 http://<本机IP>:5173（IP 用 ipconfig 查看）
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
