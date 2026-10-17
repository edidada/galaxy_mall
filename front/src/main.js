import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import tracker, { installTracker, track } from './track'
import { restore } from './stores/session'

const app = createApp(App)
app.use(router)

// SDK 必须在 app.mount 之前装好：它靠 document 捕获阶段的事件委托工作，
// 挂载后再绑会漏掉首屏那批点击（比如登录页自动聚焦后的回车）。
installTracker(app, router)

// router.afterEach 只在"切换"时触发，首个路由要手动补一条 PV，否则看板首屏永远少一次访问。
const initial = router.currentRoute.value
tracker.setPage(initial.name || 'home')
track('pv', initial.name || 'home', { path: initial.path, from: 'entry' })

// 先用本地令牌换一次登录态再挂载，避免顶栏出现"请先登录 → 突然变成用户名"的闪烁。
// restore() 内部的请求已经把网络异常吞成 success=false，所以后端没起时也会很快返回。
restore().finally(() => {
  app.mount('#app')
})
