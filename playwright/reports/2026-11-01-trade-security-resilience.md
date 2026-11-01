# K3s 交易、安全、对账与故障演练报告

## 当前判定

本轮已实际执行网页下单支付、MySQL/Redis/RocketMQ 对账、跨账号访问、重复与并发支付、MQ 重放、浏览器网络故障和隔离 K3s Pod 故障。**交易正常路径通过，但生产安全门禁失败。**

| 项目 | 实测结果 |
| --- | --- |
| UI 创建订单 -> 支付 -> PAID | 通过 |
| MySQL 分片订单 + Redis 预留 + MQ 消息关联 | 通过，见订单明细 |
| 已支付订单重放同一 MQ 事件 | 通过，消费完成且库存/订单未再改变 |
| 顺序重复支付 | 拒绝，符合本轮断言 |
| 两次并发支付同一订单 | **失败：两次 SUCCESS，两个 paymentId** |
| 匿名读测试订单 | **失败：返回真实订单** |
| 另一用户读测试订单 | **失败：返回真实订单** |
| `/edge/orders` 绕过所有权 | **失败：返回真实订单** |
| 另一用户支付测试订单 | **失败：支付成功，订单变 PAID** |
| 浏览器 503/超时 + 恢复重试 | 2/2 通过 |
| 补跑完整默认回归 | 17/18 通过；手机删除被遮挡仍失败 |
| 隔离 K3s 删除单 Pod | 通过：51 次请求，0 失败，补出新 Ready Pod |
| 隔离 K3s 完整停止/恢复 | 通过：观测到中断，约 7308.77 ms 恢复 |
| 隔离故障独立复跑 | 通过：51 次请求 0 失败，完整停止后约 4176.15 ms 恢复 |
| 30 分钟目录长稳 | 正在运行，完成后补入实测统计 |
| 30 分钟低频交易长稳 | 正在运行，完成后补入实测统计及批量对账 |

## 环境与执行范围

- 日期：2026-11-01（Asia/Shanghai）。
- 网页入口：`http://192.168.31.77:30080`。
- 实际配置：`SPRING_PROFILES_ACTIVE=uat`，`stock=redis`，`order=rocketmq+jdbc-sharded`。
- 中间件 Namespace：`middleware`。
- MQ topic/group：`galaxy-order-submitted-uat` / `galaxy-order-consumer-uat`。
- 使用演示测试用户 `u100` 和 `u101`，仅对本轮创建的订单进行安全探测。
- 测试未连接真实银行、支付渠道，SUCCESS 表示演示 payment 服务接单；不能将本报告称为真实扣款验证。
- 测试订单保留用于对账，确实消耗演示库存。未删除订单表、未重置 MQ 消费进度。

## 订单证据

| 场景 | 本轮订单号 | MySQL 位置 | 状态/结果 |
| --- | --- | --- | --- |
| 正常网页下单支付 | `ORD-1793494291249-1001` | `galaxy_order_1.t_order_2` | PAID，u100，sku-coffee，数量 1，金额 12800 分，async_attempts=1 |
| 跨用户支付探测 | `ORD-1793494426288-1002` | `galaxy_order_1.t_order_0` | 属于 u100，u101 支付成功，PAID |
| 并发支付探测 | `ORD-1793494872798-1001` | `galaxy_order_1.t_order_3` | 两个请求均成功，持久化终态仍为 PAID |

第一笔订单的原始发布消息位于 broker-a / queue 1 / offset 4；第二笔位于 queue 1 / offset 5。检查时消费进度为 6，均已经越过对应 offset，积压为 0。两笔订单均在 8 张订单分表中恰好出现一次，Redis 预留均为 `CONFIRMED`。

对账代码不会把“没有积压”单独当作消息成功的证据，还会读取保留消息体与 orderId 匹配，再核对消费进度与数据库终态。查询窗口有边界，不宣称全局 exactly-once。

本地原始证据：`artifacts/audit-ui-correlated.json`、`artifacts/audit-security-correlated.json`、`artifacts/audit-payment-race.json` 及其 `.verdict.json`。`scripts/verify-audit.mjs` 的 8 项断言对这三笔订单均通过。第三笔消息位于 broker-a / queue 0 / offset 14，核对时消费进度为 15；这不能抵消并发支付业务缺陷。

## 安全失败

### SEC-001：缺少订单所有权与认证控制

以下用例都期望拒绝访问，但实际返回 `success:true`：

1. 未登录 GET `/api/gateway/orders/{本轮测试订单}`。
2. u101 登录后 GET u100 的同一订单。
3. u101 使用 `/edge/orders/{本轮测试订单}`。
4. u101 POST `/api/gateway/payments/{u100的本轮待支付订单}`，实际推进为 PAID。

优先级：P0（若面向真实生产）。`tests/security.spec.ts` 保留这些失败断言，没有反向改成“能越权就算通过”。读取范围只包含本轮订单，没有扫描其他用户订单。

当前源码 `GatewayController.getOrder/pay` 和 `OrderController.getOrder/markPaid` 没有校验当前用户与订单 userId 的归属关系。修复必须覆盖 `/api/gateway`、`/edge` 以及所有可从外部进入的别名；不能只隐藏网页按钮或检查客户端传入的 userId。

### PAY-001：并发支付双成功

对 `ORD-1793494872798-1001` 同时发出两个支付请求，实际得到两个 SUCCESS：

- `ORD-1793494875031-1004`
- `ORD-1793494875031-1003`

`tests/payment-race.spec.ts` 期望成功次数恰好为 1，实际为 2，因此测试失败。顺序重复支付被拒绝并不能证明并发安全。

优先级：P0。当前源码先读取 `WAITING_PAYMENT` 再更新状态，存在竞争窗口。生产修复需要数据库条件更新/CAS、持久化支付记录及唯一约束、幂等键与渠道回调对账；仅增加 Java 单实例锁不足以覆盖多副本。

## MQ 重放

针对已 PAID 的第一笔订单只追加一条重复事件，broker-a / queue 0 / offset 13 返回 SEND_OK，消费进度随后越过该位置：

- Redis 库存：293 -> 293。
- 数据库：仍只有同一笔 PAID 订单，字段及 async_attempts 不变。
- 结论：本次“终态后重放”幂等检查通过。

该结论不覆盖 PROCESSING 阶段并发消费、跨服务事务原子性、全局恰好一次或故障恢复下不丢消息。代码为 `scripts/mq-replay-check.py`，本地证据为 `artifacts/mq-replay-check.json`。

## 故障注入

### 浏览器侧

`tests/resilience.spec.ts` 分别给商品目录接口注入 HTTP 503 和网络超时，验证显示错误、没有伪造商品成功状态、撤除故障后点击“重试”恢复真实商品。两个用例通过。

### 实际 K3s Pod

`scripts/k3s-canary-fault.py` 创建带随机 run-id 和所有权标签的临时 Namespace，部署两副本 nginx，镜像 digest 与运行中的 front 一致，GET 请求转发到现有 Gateway 商品目录：

1. 故障期间持续查询，删除一个临时 Pod；51 次请求无失败，控制器创建了新 Ready 副本。
2. 只把该临时 Deployment 缩为 0，观察连接拒绝，再恢复为 2；从 scale 命令返回后计时，约 7.31 秒后连续 5 次业务查询成功。这不是整个中断窗口时长。
3. 按 run-id 验证所有权后删除整个临时 Namespace，包括其中独立复制的仓库拉取凭据。

原 `galaxy-mall` Deployment 未被缩容、重启或删 Pod。该演练验证前端副本故障与服务恢复，不等于已经演练 MySQL/Redis/Broker 真实宕机。

初次尝试遇到缺少私有仓库认证、缓存引用不可用，随后补齐临时拉取凭据。另一次立即在 Pod Ready 后查询 Service 时过早失败，测试已改成有时限的端点收敛检查，并记录真实恢复耗时。失败启动没有被算入通过结果，所有临时 Namespace 均执行了清理。

最终证据：`artifacts/k3s-canary-fault-measured.json`，`passed=true`、`cleanup=owned test namespace deleted`。

在增加“镜像 digest 不匹配立即拒绝演练”前置条件后独立复跑，使用另一个随机 Namespace `galaxy-mall-e2e-0b15d4cc0c`。结果仍为单 Pod 删除期间 51 次请求 0 失败、全停后恢复约 4176.15 ms，临时 Namespace 已删除。证据为 `artifacts/k3s-canary-fault-recheck.json`。两次恢复耗时采用同一计时口径。

## 部署一致性风险

两个 Gateway Pod 都声明 `gateway:git-5534813`，但 imageID 分别为：

- `sha256:ad8f15307906377d0be3604ab6008e9911a74bb25ccb2364c486ddec65c5b6e7`
- `sha256:d54d7072927cf47a4ffa33a3a8102976ffed21625cbc246bda75e32817bf3805`

应在发布流水线使用真正不可变的镜像引用，固定 digest 后再复验，不能仅按相同 tag 判断两个副本完全一致。本轮测试结论只代表当前真实流量落到的部署组合。

## 长稳测试

目录轮次：固定 5 RPS、30 分钟，生成统计后补入本节。

交易轮次：固定每分钟一笔订单、30 分钟，每轮登录态对应同一测试用户，下单不自动重试，轮询 WAITING_PAYMENT，再支付并确认 PAID。订单号输出到脱敏日志，结束后按这些明确 ID 批量查询 8 张 MySQL 分表、Redis reservation 与 MQ 消息体/消费进度。

启动长稳时发现原 `K6_DURATION` 会覆盖 k6 场景配置，已停止该启动，改用 `LOAD_DURATION`，并对原生 `K6_DURATION/K6_VUS` 增加冲突检查。被中止的启动不计入长稳通过结果。

## 复验入口

补跑完整默认集时，共 18 项中 17 通过，唯一失败仍为 Pixel 7 购物车删除点击被数量输入框/表格/导航遮挡。证据保存在 `artifacts/final-smoke/`，初始缺陷说明见 [网页基线报告](2026-11-01-k3s-acceptance.md)。此次补跑没有创建订单。

使用 [README-k3s.md](../README-k3s.md) 的交易、安全和运维章节。所有报告、日志与原始 JSON 在 `artifacts/`，被 Git 忽略；CI 应以受控 Artifact 归档。

参考：[RocketMQ 管理命令](https://rocketmq.apache.org/docs/deploymentOperations/02admintool/) 和 [Kubernetes Pod/Endpoint 终止流程](https://kubernetes.io/docs/tutorials/services/pods-and-endpoint-termination-flow/)。
