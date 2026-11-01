> K3s 可执行网页/API 测试、性能脚本与生产验收方案见 [README-k3s.md](README-k3s.md)。下文保留为原始背景说明；关于 HttpOnly 的勘误见新文档第 7 节。

Playwright 可以获取 F12 Network 面板中的请求信息，也能管理 Cookie 和 Session。但要用于工业生产环境，核心思路不是“实时抓取每一个请求”，而是通过事件监听做定向采集，并通过 `storageState` 做认证状态的持久化复用。

### 获取 Network 信息（请求/响应）

在 Playwright 中，对应 F12 Network 面板的能力是 `page.on('request')` 和 `page.on('response')` 事件。工业界通常不会全量记录所有静态资源，而是按需过滤。

基础监听方式：
```javascript
page.on('response', async (response) => {
  if (response.url().includes('/api/')) {
    const headers = await response.allHeaders();
    // 注意：response.body() 可能因重定向或资源释放而失败，需捕获异常
  }
});
```
这里有一个关键细节：`request.headers()` 不包含 Cookie 等安全头，要获取完整请求头（包括 Cookie），必须使用 `request.allHeaders()`。但即便如此，对于 `HttpOnly` 的 Cookie，Playwright 的路由拦截层也拿不到，因为这是浏览器的安全边界。要获取完整的 Cookie 状态，应该走 `context.cookies()` 而不是解析请求头。

生产环境建议：
- 不要全量采集。用 `filter` 或 URL 匹配只监听业务 API，避免静态资源（图片、字体）和埋点请求把日志撑爆。
- 异步处理要防阻塞。`page.on('response')` 的回调里如果做耗时操作（如写数据库），会拖慢浏览器事件循环，建议只推入队列，由独立 Worker 消费。

### 获取 Session Cookie 与认证状态

这是工业方案的核心。不要每次运行都重新登录，也不要把密码硬编码在测试脚本里。

标准做法：`storageState` 持久化
Playwright 的 `context.storageState({ path: 'auth.json' })` 会导出当前上下文中的所有 Cookie 和 localStorage（包括 MSAL token 等）。后续创建 `browser.newContext({ storageState: 'auth.json' })` 时，浏览器会直接处于已登录状态。

生产环境落地方案：
1. 隔离准备阶段：在 `globalSetup`（或独立的 Auth Setup Project）中执行一次登录。登录用的账号密码从环境变量或密钥管理服务（如 Azure Key Vault、HashiCorp Vault）注入，绝不写入代码库。
2. 状态文件管理：生成的 `auth.json` 含有有效会话令牌，必须加入 `.gitignore`，且不应在 CI 日志中打印其内容。
3. 过期校验：在复用状态前，检查关键 Cookie 的过期时间或试探性访问一个需鉴权的页面。若失效，触发重新认证流程，而不是直接让测试失败。
4. 多角色隔离：如果业务需要测试不同权限的账号，为每个角色生成独立的 `auth-state-{role}.json`，在测试用例中按需加载对应文件。

### 工业级补充：网络请求的“录制与回放”

如果目的是做稳定的回归测试（避免依赖真实后端波动），可以用 HAR 录制。Playwright 支持 `context.routeFromHAR('archive.har')` 将网络请求回放。生产环境中，HAR 文件可能包含敏感的 `Set-Cookie` 或 Authorization 头，需要在写入磁盘前做脱敏处理（或仅录制在受控环境使用）。

总结一下生产配置清单：
- 网络采集：用 `page.on('response')` 定向监听 API 路径，异步消费，避免阻塞。
- Cookie 获取：用 `context.cookies()` 而非解析请求头，以覆盖 `HttpOnly` Cookie。
- 认证复用：用 `storageState` + `globalSetup`，凭证走环境变量/密钥管理，状态文件不入库。
- 稳定性：若后端不稳定，考虑 HAR 回放，但注意脱敏。
