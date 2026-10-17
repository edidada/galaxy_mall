import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发态用 vite 的反向代理把 /api、/edge、/actuator 转发到网关，
// 这样浏览器里所有请求都是同源的：集群里的 Traefik/nginx 入口没有下发 CORS 头，
// 带 Content-Type 的 POST 会先触发 OPTIONS 预检并直接被 403 挡掉，前端只能靠同源绕开。
//
// 默认目标 http://localhost:18080 —— 本机 8080 被 httpd 占用，所以本地网关用 18080 起。
// 想直连集群：front/.env.local 里写 VITE_API_TARGET=http://192.168.31.77:30080
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_TARGET || 'http://localhost:18080'

  const forward = (path) => ({
    target,
    changeOrigin: true,
    // 后端起来较慢（本地全栈冷启约 40s），连接失败时在响应里写清楚转发到了哪
    configure: (proxy) => {
      proxy.on('error', (err, req) => {
        console.warn(`[proxy] ${req?.method} ${req?.url} -> ${target} 失败: ${err.message}`)
      })
    }
  })

  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': forward('/api'),
        '/edge': forward('/edge'),
        '/actuator': forward('/actuator')
      }
    },
    build: {
      outDir: 'dist',
      chunkSizeWarningLimit: 700
    }
  }
})
