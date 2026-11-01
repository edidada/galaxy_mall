# K3s 网页验收与目录性能实测报告

> 本文记录初始阶段的执行快照。用户要求继续执行后，交易、集群审计、安全、故障及长稳测试已补跑；最新结果见 [后续实测报告](2026-11-01-trade-security-resilience.md)。下文的“未执行”仅指初始阶段，不代表当前覆盖。

## 结果

2026-11-01 对 `http://192.168.31.77:30080` 的实际部署执行测试。默认套件 16 项中 **15 通过、1 失败**；随后独立执行认证用例 **1 通过**。手机端购物车删除失败已独立复跑，重复失败。当前移动端发布门禁不通过。

| 类别 | 结果 | 范围 |
| --- | --- | --- |
| 测试工具自检 | 4/4 通过 | 目标 URL 校验、查询参数脱敏、业务失败识别、生产写入限制 |
| K3s API | 4/4 通过 | 商品/详情/金额/图片、未知商品、无效登录态 |
| Chromium 桌面 | 4/4 通过 | 首页图片、搜索、购物车数量/刷新/删除、匿名结算跳转 |
| Chromium 手机视口 | 3/4 通过 | 购物车删除失败，其余通过 |
| 登录与退出 | 1/1 通过 | UI 登录、刷新恢复、退出后旧 token 被拒绝 |
| k6 目录基线 | 通过 | 5 RPS、30 秒、150 次业务查询，10 VU |
| UI 下单支付 | 未执行 | 测试代码已提供，需要独立 UAT 账号与商品、开启写入 |
| Firefox / WebKit | 未执行 | 项目配置已提供，需安装对应浏览器 |
| Kubernetes 内部检查 | 未执行 | 集群只读脚本已提供，需目标 kubeconfig/RBAC |

## 环境与命令

- 压测机：Windows，Node.js `24.22.1`、npm `11.19.1`。
- 测试器：Playwright `1.64.1`，安装匹配的 Chromium `1248`（Chrome `156.0.8078.4`）。
- 浏览器环境：Desktop Chrome / Pixel 7（手机视口与触控模拟，不是真机）。
- 本地源码 HEAD：`612f92e8547379949f0e54fd210f8748cbfd12d1`，本次新增测试尚未提交。
- 被测部署 image digest 未通过集群 API 采集，不能认为网页部署版本与源码 HEAD 相同。
- 测试器重试：0，worker：1。

从 `playwright/` 执行：

```powershell
npm ci
npm run typecheck
npx playwright install chromium
npm test

# 对手机缺陷定向复跑，不覆盖初次报告。
npx playwright test --project=mobile -g 'detail cart' --reporter=line --output=artifacts/repro
```

认证用例使用站点已公开展示的演示账号执行，凭据通过进程环境变量注入，未写入测试代码或报告。此轮未注册账号、未下单或支付；浏览器自然产生的埋点照常上报。

## 缺陷 MOB-001：手机购物车删除被遮挡

- 优先级：高（若发布目标包含手机网页，则阻断发布）。
- 用例：`tests/storefront.spec.ts` 的 `detail cart quantity persists across reload and can be removed`。
- 条件：Pixel 7 手机视口，普通商品 `sku-coffee`。
- 重现：详情页加入购物车 -> 进入购物车 -> 数量 1 改为 2 -> 刷新 -> 点击“删除”。
- 预期：商品被移除，显示“购物车空空如也”。
- 实际：`[data-track="cart-remove"]` 能定位，但点击超时 10 秒。
- Playwright 证据：点击目标被 `购买数量` input、表格单元格、顶部分类导航截获。
- 截图观察：购物车表格与手机宽度不匹配，顶部导航和部分表格内容发生重叠。
- 复跑：独立执行相同用例再次失败，日志中仍为 pointer events intercepted。

本次保留正常的可点击性检查，没有使用 `force:true`、`dispatchEvent` 或跳过测试来改变结论。修复建议是调整手机断点下的导航和购物车布局，将表格重排为商品卡片，或提供可正常滚动且不会与固定导航重叠的容器，修复后重新部署并运行同一测试。

本地失败证据：

- [独立复跑截图](../artifacts/repro/storefront-detail-cart-qua-7edae-s-reload-and-can-be-removed-mobile/test-failed-1.png)
- [独立复跑错误上下文](../artifacts/repro/storefront-detail-cart-qua-7edae-s-reload-and-can-be-removed-mobile/error-context.md)
- [后续完整回归 Markdown](../artifacts/final-smoke/report.md)（18 项：新增 2 项故障恢复，17 通过、手机删除仍失败）

`artifacts/` 按设计被 Git 忽略，上述复跑附件在本机存在；初次默认输出目录已被后续专项测试更新，因此不再把它链接为初始完整报告。CI 上传后保留 7 天。此报告保留文本结论，避免在 Git 中长期存放页面和会话附件。

## k6 商品目录基线

使用官方 Grafana k6 `v2.3.0` Windows CLI，下载包 SHA-256 为 `112276d495e5741c968e2bc09ea6196099c1275bd6db9ee0875d173c7148ce43`，与 GitHub release asset digest 比对一致。

```powershell
$env:K6_ALLOW_LOAD='1'
# 默认 E2E_BASE_URL=http://192.168.31.77:30080
# 默认 K6_RATE=5，LOAD_DURATION=30s，LOAD_VUS=10
k6 run performance/catalog.k6.js
```

| 指标 | 实测 |
| --- | ---: |
| 正式业务迭代 | 150 |
| 业务成功 | 150/150（100%） |
| 漏发请求 | 0 |
| HTTP 请求总数 | 155（含 5 次 setup 预热） |
| HTTP 失败率 | 0% |
| HTTP 平均耗时 | 30.79 ms |
| HTTP P95 | 39.68 ms |
| HTTP P99 | 58.93 ms |
| HTTP 最大耗时 | 75.42 ms |
| 阈值判定 | 全部通过 |

HTTP 耗时汇总包含 5 次预热；业务成功计数只覆盖 150 次正式迭代。阈值为业务成功率 >99.9%、HTTP 失败率 <0.1%、漏发 0、P95<800ms、P99<2s。这些是本工程初始测试预算，需要产品 SLA 确认后调整。

该结果只覆盖 `front -> nginx -> gateway -> product` 的商品目录链路，没有证明 Redis 库存、MySQL 写入、MQ 消费或订单支付性能，也没有寻找集群极限容量。没有采集服务器资源曲线，不能据此定位吞吐瓶颈。原始指标保存在 [k6-summary.json](../artifacts/k6-summary.json)，自动报告为 [performance-report.md](../artifacts/performance-report.md)。

## 生产验收缺口

已提供登录和单笔交易自动化代码，但当前没有执行交易测试，没有 DB/MQ 对账、故障注入、跨账号订单越权测试、30 分钟稳态或浏览器视觉/无障碍结果。完整生产验收路线及代码入口见 [README-k3s.md](../README-k3s.md)。本报告结论为：桌面与目录接口可用，低速目录性能达标，手机购物车存在已复现阻断缺陷。
