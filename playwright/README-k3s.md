# K3s 网页与整条业务链路验收

这是可运行的测试工程。入口为 `http://192.168.31.77:30080`，通过 front nginx 调用 Gateway 与下游业务服务。工程覆盖浏览器回归、API 合约、真实演示下单支付、安全负例、k6 读写长稳、MySQL/Redis/MQ 对账和隔离 K3s 故障演练。

本轮已执行的结果见 [交易、安全、对账与故障报告](reports/2026-11-01-trade-security-resilience.md)。**已复现订单越权和并发支付双成功，当前不能通过生产发布门禁。**

设计参考大型互联网团队常用的分层测试、发布门禁、隔离数据与 SLO 验收方式；没有证据表明它等同于某家公司的内部方案。当前代码仍是演示商城，下面的生产能力缺口需要逐项补齐。

## 1. 直接运行

要求 Node.js 22+，不依赖 Java 构建；业务后端已部署到 K3s。以下命令从本目录执行：

```powershell
npm ci
npx playwright install chromium
$env:E2E_BASE_URL='http://192.168.31.77:30080'
npm run typecheck
npm run test:unit
npm run test:smoke
```

`npm test` 默认执行 tooling、API、浏览器故障恢复、Chromium 桌面和 Chromium 手机视口，共 18 项；`npm run test:unit` 另外执行 3 项订单日志解析自检。每次浏览器测试使用独立 BrowserContext，不复用 Cookie、localStorage 或购物车。页面会自然发送埋点；默认用例不会下单或支付。`.env.example` 仅作变量说明，脚本不自动加载 `.env`。

测试通过必须同时满足 HTTP 成功、`success:true`、响应数据合约和页面可见状态。不能把 HTTP 200、页面加载成功或 `Pod Ready` 单独当作业务通过。

报告输出在 `artifacts/report.md`、`artifacts/junit.xml`、`artifacts/html/`；浏览器网络附件只保存路径、方法、状态码和耗时，不保存查询参数、请求体、密码或令牌。`npm run report` 打开本地报告。

## 2. 覆盖矩阵

| 层 | 当前代码 | 通过判据 |
| --- | --- | --- |
| API | `tests/api.spec.ts` | 商品唯一 ID/金额/详情/图片、未知商品、失效会话业务拒绝 |
| 桌面/手机 | `tests/storefront.spec.ts` | 实际商品图、搜索/空结果、购物车数量/刷新持久化/删除、未登录结算跳转 |
| 认证 | `tests/auth.spec.ts` | UI 登录、刷新恢复、退出后旧 token 无效 |
| 写链路 | `tests/transaction.spec.ts` | UI 结算 -> 真订单 -> 支付可用 -> SUCCESS -> 订单 PAID |
| 安全 | `tests/security.spec.ts`、`tests/payment-race.spec.ts` | 匿名/跨账号拒绝、路由别名保护、顺序重复支付拒绝、并发恰好一个成功 |
| 浏览器故障 | `tests/resilience.spec.ts` | 503/超时显式报错，撤除故障后恢复真实商品 |
| 性能 | `performance/catalog.k6.js` | 固定到达率，业务成功率 >99.9%，无 dropped iterations，p95<800ms/p99<2s |
| 写长稳 | `performance/checkout.k6.js` | 每分钟一单，完整下单/等待/支付/PAID，成功率 100%，无漏发 |
| 持久化对账 | `scripts/cluster-audit.py`、`soak-order-audit.py` | 8 张分表单行、PAID、Redis CONFIRMED、MQ 体与消费 offset 关联 |
| MQ 重放 | `scripts/mq-replay-check.py` | 只重放一条本轮终态测试订单事件，消费后库存/订单不变 |
| K3s 故障 | `scripts/k3s-canary-fault.py` | 仅随机临时 Namespace，删单 Pod 保持可用，停两副本后恢复与清理 |
| 集群 | `scripts/k3s-check.mjs` | 节点 Ready、7 个 Deployment 收敛、MySQL/Redis/MQ Ready endpoints |
| CI | `../.github/workflows/k3s-web-tests.yml` | 公网 runner 工具验证；手动调用内网 runner 验收现网 UAT |

手机项目测试的是功能与触控布局下的可操作性，当前没有全页面溢出或完整无障碍合规验收。可用以下方式加跑浏览器引擎：

```powershell
npx playwright install firefox webkit
$env:E2E_BROWSERS='all'
npm test
```

## 3. 登录与一次下单支付

先准备独立 UAT 账号和有库存的普通测试商品。不要在代码中写账号密码。当前实现没有安全的数据回收 API，订单号会保存到测试附件中，账目和库存应由测试数据维护流程核对、保留或补偿；不能用删业务表的方法清理。

```powershell
$env:E2E_ENV='uat'
$env:E2E_AUTH='1'
$env:E2E_USERNAME='<专用测试账号>'
$env:E2E_PASSWORD='<从密钥管理获取>'
npm run test:auth

$env:E2E_ALLOW_WRITES='1'
$env:E2E_PRODUCT_ID='<专用测试普通商品>'
npm run test:transaction
```

单独的 `test:transaction` 串行、关闭重试，一次最多提交一单。安全测试会另创建两单；交易长稳每分钟一单。若失败，请先按报告订单号查询状态，再决定是否重新运行。缺变量时直接失败，不会用默认账号悄悄跳过。`QUEUED` 不算已落单成功。测试不会注册账号；该站注册密码位于查询参数，容易进入代理日志，需要业务改成 JSON body。

默认 trace/video 关闭，因为原生 trace 含 DOM、请求体和会话信息，不能声称已脱敏。仅在受控 UAT 设置 `E2E_DIAGNOSTICS=1` 保留失败 trace/video，限制报告访问和保留期。登录态只存在用例自己的 BrowserContext，不落 `auth.json`。安全测试以两个不同账号和独立 token 调用，仅探测本轮测试订单。

```powershell
$env:E2E_SECURITY='1'
$env:E2E_OTHER_USERNAME='<另一专用测试账号>'
$env:E2E_OTHER_PASSWORD='<从密钥管理获取>'
$env:E2E_ORDER_ID='<本轮刚创建的 PAID 测试订单>'
$env:E2E_REPORT_DIR='artifacts/security'
npm run test:security
```

当前部署这些安全断言会失败，属于发现真实缺陷，不应修改成“允许越权就是通过”。`artifacts/orders/` 保存白名单订单字段，不保存请求/令牌。

## 4. 真正的性能验收

Playwright 适合交互回归；大量并发浏览器会让压测机先成为瓶颈。本工程用独立 k6 CLI 生成请求。安装 k6 后从本目录执行：

```powershell
New-Item -ItemType Directory -Force artifacts | Out-Null
$env:K6_ALLOW_LOAD='1'
$env:K6_RATE='5'
$env:LOAD_DURATION='30s'
k6 run performance/catalog.k6.js
```

默认是 5 RPS 的目录列表基线。`artifacts/performance-report.md` 与 `artifacts/k6-summary.json` 是本轮结果。逐档增加速率时保持压测机独立、版本和资源一致，同时收集 p95/p99、业务成功率、CPU throttle、GC、Redis 延迟、MySQL 锁/连接池和 MQ 消费滞后。`dropped_iterations>0` 会失败，防止漏发请求后误报达标。

目录 30 分钟：`./scripts/run-soak.ps1 -K6 '<k6.exe路径>' -Duration 30m -Rate 5 -Output artifacts/soak`。不要设置原生 `K6_DURATION/K6_VUS`，它们会覆盖场景配置；自定义变量为 `LOAD_DURATION/LOAD_VUS`。

低频交易 30 分钟（会真实消耗 30 件演示库存）：

```powershell
# 先按上一节设置 UAT、账号、密码、商品，并确认库存和写入范围。
New-Item -ItemType Directory -Force artifacts/checkout-soak | Out-Null
$env:LOAD_ALLOW_ORDER_WRITES='1'
$env:LOAD_DURATION='30m'
$env:K6_OUTPUT_DIR='artifacts/checkout-soak'
k6 run --quiet --console-output=artifacts/checkout-soak/orders.log performance/checkout.k6.js
if ($LASTEXITCODE -ne 0) { throw 'Checkout soak failed; reconcile existing order IDs before rerunning' }
$ids = node scripts/soak-manifest.mjs artifacts/checkout-soak/orders.log 30
if ($LASTEXITCODE -ne 0) { throw 'Checkout log incomplete; do not claim success' }
```

`orders-manifest.json` 验证日志中恰好 30 笔、ID 唯一、全部完成；`summary.json/report.md` 验证 k6 阈值。随后还必须执行下一节批量对账。此低频长稳不测峰值写容量，不证明支付并发安全，更不是财务结算验收。订单 ID 保留到消息查询窗口内；默认样例脚本要求 MQ 每队列保留消息少于 2000，超限需有范围的审计方案，不能全量扫生产 Topic。

## 5. 集群与流水线

只读集群检查需要目标 kubeconfig 和读取 nodes/deployments/pods/endpointslices 的 RBAC：

```powershell
node scripts/k3s-check.mjs
```

脚本输出 `artifacts/cluster-snapshot.json`，不读 Secret、不部署/重启。测试前后各跑一次并比较 restartCount；单次快照不证明压测期间无重启。没有 kubeconfig 时网页测试仍可运行，但不代表集群层已验收。

本轮从 Windows 经 SSH 在主节点执行审计。`remote-python.ps1` 依赖 PuTTY plink、已验证的主机指纹和远端 sudo/python3；口令只从进程环境读取，不写文件：

```powershell
$env:CLUSTER_SSH_PASSWORD='<从密钥管理注入>'
./scripts/remote-python.ps1 -Script ./scripts/cluster-audit.py -Argument $env:E2E_ORDER_ID -Output artifacts/audit.json
node scripts/verify-audit.mjs artifacts/audit.json $env:E2E_ORDER_ID
./scripts/remote-python.ps1 -Script ./scripts/soak-order-audit.py -Argument $ids -Output artifacts/checkout-soak/reconciliation.json
```

审计读取 MySQL 8 张分表、Redis reservation、MQ 保留消息及消费 offset，不删除数据、不重置消费者。当前审计断言固定为本演示夹具 `u100/sku-coffee/数量1/12800分`，换账号/商品必须先调整审计契约，不能把不匹配当成成功。

长稳期间可独立运行 `./scripts/remote-python.ps1 -Script ./scripts/cluster-observe.py -Argument 1200 -Output artifacts/cluster-observation.json`，每分钟只读采样节点 Ready、业务 Pod UID/重启与 CPU/内存。它阻塞到 20 分钟结束再输出 JSON，点采样不能证明采样间无短暂抖动。

以下两项有副作用，必须在受控 UAT 明确执行：

```powershell
# 仅向真实 Topic 追加一条本轮已 PAID 订单的重复事件，核对库存/订单不变。
./scripts/remote-python.ps1 -Script ./scripts/mq-replay-check.py -Argument $env:E2E_ORDER_ID -Output artifacts/mq-replay.json
# 创建/缩容/删除仅属于本轮的临时 Namespace，不删除原业务 Pod。
./scripts/remote-python.ps1 -Script ./scripts/k3s-canary-fault.py -Output artifacts/canary-fault.json
```

隔离故障脚本为获取私有镜像，临时复制 front 的 imagePullSecret，仅在内存处理 Secret 内容，演练结束随自有 Namespace 删除；需要相应高权限，不能包装成纯只读。MQ 重放只证明该终态样本的幂等行为；隔离 nginx 故障不等于业务服务或数据库/Redis/Broker 宕机演练。

GitHub 托管 runner 访问不到家庭局域网。需在独立内网测试机配置标签 `self-hosted, linux, k3s-e2e`，并建受保护 Environment `k3s-uat`：

- Variable `K3S_WEB_BASE_URL`：实际 front 页面入口，不能填只路由 API 的 Ingress。
- Secrets `E2E_USERNAME/E2E_PASSWORD`：专用测试账号。
- Secrets `E2E_OTHER_USERNAME/E2E_OTHER_PASSWORD`：安全验收使用的第二账号，必须与第一账号不同。
- Variable `E2E_PRODUCT_ID`：专用商品；只在写验收启用时必填。
- 内网 runner 预装 k6（仅性能开关启用时需要），浏览器安装需要出网或离线镜像缓存。

PR/push 只运行无目标集群访问的类型检查、工具自检和用例发现；`workflow_dispatch` 才在受保护内网 runner 上执行浏览器回归。写、目录负载、读/写长稳各自有开关，安全/写长稳要求同时开启 transactions。流水线默认失败即阻断后续负载；不会自动重试下单。SSH 高权限审计/故障脚本需要运维显式运行，未隐式放进 CI。报告保留 7 天，不能声称本机执行等同于已完成远端 CI。

```powershell
gh workflow run k3s-web-tests.yml
gh run list --workflow k3s-web-tests.yml --limit 5
```

## 6. 对标生产验收还缺什么

整个 Java 程序的验证应与本工程分层执行：根目录 `mvn test` 负责现有单元测试和本地 HTTP/H2 集成；本目录验证已经部署的网页/API；独立基础设施环境负责 Redis/MySQL/MQ 真实联调。`SpringCloudSmokeTest` 的 `realInfra` 模式会在 `prepareMysqlSchemas/resetShardTables` 中删除订单分表，不能把它直接指向当前 K3s 业务数据库。应先提供专用测试实例，再进行这一层测试。

| 能力 | 当前状态 | 生产落地要求 |
| --- | --- | --- |
| 认证与授权 | 已执行匿名/跨账号负例，实际失败 | 修复认证与订单归属、分布式会话/撤销、风控、TLS |
| 交易正确性 | UI 交易及终态 MQ 重放通过；并发支付双成功 | 修复持久化幂等/CAS，库存不超卖、故障补偿与真实财务对账 |
| 持久化证据 | 订单分片/Redis/MQ 关联审计已执行 | 独立只读审计身份、更多业务状态与对账异常告警 |
| 韧性 | 浏览器故障与隔离前端 Pod 演练已执行 | 专用基础设施环境下 Broker/Redis/MySQL 故障、恢复后不丢单 |
| 发布门禁 | 可执行 CI 框架 | 绑定实际镜像 digest/部署版本、canary 自动验收与回滚策略 |
| 性能 | 目录与交易 30 分钟脚本及逐单审计 | 见实测报告；峰值/阶梯/24-72小时、独立压测机/多机、SLO 和容量余量 |
| 可观测 | 安全网络摘要、失败截图、JUnit/MD | traceId 关联后端日志与 MQ、告警演练、受控证据保留 |
| 兼容与体验 | Chromium 桌面/手机、可选其它引擎 | iOS/Android 真机、a11y、视觉基线、Web Vitals 与用户体验 SLO |

这些仍不构成生产保证。尤其不能用低频长稳通过掩盖已复现的安全、并发支付和手机布局缺陷。

初始执行见 [网页与目录基线](reports/2026-11-01-k3s-acceptance.md)：15/16 通过、手机删除失败；后续新增执行以 [交易、安全、对账与故障报告](reports/2026-11-01-trade-security-resilience.md) 为准。

## 7. 官方依据

- [Playwright CI](https://playwright.dev/docs/ci)：CI worker 与浏览器安装配置。
- [Playwright 重试](https://playwright.dev/docs/test-retries)：失败会重启 worker，因此交易副作用必须独立控制。
- [Playwright 认证状态](https://playwright.dev/docs/auth)：会话文件包含敏感数据，不能提交。
- [k6 API load testing](https://grafana.com/docs/k6/latest/testing-guides/api-load-testing/) 与 [dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/)：固定到达率与漏发检测。

原始 Network/Cookie 说明保留在 `readme.md`。其中 HttpOnly 的含义需要纠正：它限制网页 JavaScript 的 Cookie 访问，不表示 Playwright 自动化协议无法观察请求 Cookie。测试工程不持久化 Cookie/请求头，降低凭据泄露面。
