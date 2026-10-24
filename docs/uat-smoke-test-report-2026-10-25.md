# UAT Smoke Test 报告（galaxy_mall on r630 k3s）

- 测试日期：2026-10-25（UTC 时间 2026-10-24T19:04 ~ 19:24 实际执行）
- 被测脚本：[`scripts/uat_smoke_test.sh`](../scripts/uat_smoke_test.sh)（本次执行前做了两处必要修复，见第 4 节）
- 测试结论：**通过**（修复脚本 + 修复环境后，7 个检查全部通过，耗时约 6 秒）

## 1. 执行环境

| 项目 | 值 |
| --- | --- |
| 集群 | Dell R630 / Proxmox 上的 k3s（参考 `r630-proxmox8` 仓库 `r630-vm-k3s-cluster.md`） |
| k3s 版本 | v1.35.4+k3s1 |
| 节点 | k3s-master(77) / worker1(211) / worker2(251) / worker3(88) / worker4(243)，全部 Ready |
| 命名空间 | `galaxy-mall`（业务 6 服务 + front）+ `middleware`（MySQL / Redis / RocketMQ / Nacos 等） |
| 业务镜像 | 全部 `registry.galaxy.local:30500/galaxy-mall/<svc>:git-5534813`，`READY=REPLICAS` |
| 副本 | gateway 2、order 2、stock 2，user/product/payment/front 各 1 |
| Spring Profile | 集群实际为 `SPRING_PROFILES_ACTIVE=prod`（不是 `uat`，见第 5 节说明） |
| 流量入口 | Traefik Ingress `mall.192.168.31.77.nip.io` → `galaxy-mall-gateway:8080`（ClusterIP，无 NodePort） |
| SSH 入口 | Windows `~/.ssh/config` 中 `k3s-master`（192.168.31.77，root + ed25519 免密） |

执行方式：脚本在 **k3s-master 上以 `BASE_URL=http://<gateway-pod-ip>:8080` 直连单个 gateway Pod** 执行（Pod 网段从 master 可路由）。先在 Windows/WSL 侧用 ingress 域名跑通过主链路，仅等待室一段在双 Pod 负载均衡下无法稳定触发（详见第 4.2 节），改直连单 Pod 后全链路稳定。

## 2. 测试项与结果

| ID | 场景 | 请求 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| T01 | 健康检查 | `GET /actuator/health/readiness` | 通过 | `{"status":"UP"}` |
| T02 | Prometheus 指标 | `GET /actuator/prometheus` | 通过 | 响应含 `jvm_threads_live_threads` |
| T03 | 商品查询 | `GET /api/gateway/products/sku-iphone?userId=u100` | 通过 | 响应含 `"productId":"sku-iphone"` |
| T04 | 异步下单 | `POST /api/gateway/orders`（u100 × 1） | 通过 | `success:true`，订单最终 `WAITING_PAYMENT`（30s 内轮询确认） |
| T05 | 支付闭环 | `POST /api/gateway/payments/<orderId>` | 通过 | `"status":"SUCCESS"` |
| T06 | 等待室/削峰 | 单 Pod 32 并发 `POST /api/gateway/orders`（u101） | 通过 | 返回 `QUEUED` + `queueToken` |
| T07 | 排队重放 | 携带 `queueToken` 重放同 businessKey 下单 | 通过 | 放行并创建订单，推进到 `WAITING_PAYMENT` |

执行输出（k3s-master，2026-10-24T19:24:17Z ~ 19:24:23Z）：

```text
[uat-smoke] check health
[uat-smoke] check prometheus
[uat-smoke] query product
[uat-smoke] submit order
[uat-smoke] await WAITING_PAYMENT
[uat-smoke] pay order
[uat-smoke] trigger queue flow
[uat-smoke] replay queued request
[uat-smoke] all checks passed
EXIT=0
```

## 3. 环境侧发现（本次修复）

| ID | 现象 | 根因 | 处置 |
| --- | --- | --- | --- |
| E01 | 首轮 T04 订单最终 `FAILED` | `sku-iphone` Redis 库存为 0：本次联调前的并发预热把库存耗尽 | 通过 stock 服务 `/api/stocks/restock` 补 500（adjustmentId=`uat-smoke-restock-20261025`） |
| E02 | 补库存后新订单仍 `FAILED`，且 `confirm` 阶段失败 | `RedisLuaStockBackend` 的“DB 账本”是**每个 stock Pod 进程内存**的 `StockLedgerRepository`，`sku-iphone` 每副本只播种 10；运行 6 天后两副本账本均耗尽，Redis 有库存但 `confirm` 走 `db-confirm-failed` → 订单 FAILED | `rollout restart deploy/galaxy-mall-stock` 重新播种（每副本重置为 10）。**结构性问题，见第 5 节建议** |
| E03 | 部分订单 FAILED 后 Redis 中残留 `galaxy:stock:reservation:<orderId>` | confirm 失败路径的 release/补偿未完成 | 依赖 `StockCompensationTask` 周期补偿；未手工清理，不影响冒烟判定 |

## 4. 脚本侧发现（本次已修复 `scripts/uat_smoke_test.sh`）

### 4.1 排队用户不存在（脚本 bug）

原脚本硬编码 `userId=queue-u1`，但 user 服务只播种了 `u100/u101/u200/u300`（`UserServiceApplication.java`），网关在 `placeOrder` 第一步 `getUser` 校验，返回 `user-not-found`，**等待室场景永远不可能触发**。修复：排队用户改为可配置的已播种账号，默认 `u101`（`QUEUE_USER` 环境变量）。

### 4.2 串行 8 次无法触发每秒窗口（脚本 bug）

等待室由网关的按 userId **每秒窗口限流**触发（`WindowRateLimiter`，`prod` profile 阈值 8 次/秒/Pod）。原脚本串行 POST 8 次（每次 100~300ms），单 Pod 每秒请求数始终 ≤ 8，永远进不了等待室。修复：用 `xargs -P32` 32 路并发一轮下单（约 0.4s 内完成），单 Pod 内必然超出 8/s 窗口，稳定返回 `QUEUED + queueToken`；最多重试 3 轮。副作用：触发现实单流量前会先创建少量真实 `SUBMITTED` 订单（单 Pod 直连约 8~16 单），属 UAT 演示数据。

### 4.3 CRLF 行尾（环境说明）

仓库 `core.autocrlf=true` 导致工作区 `.sh` 为 CRLF，直接 `bash` 执行报 `$'\r': command not found`。执行前需 `tr -d '\r'` 转换（本次即如此操作）。建议后续用 `.gitattributes` 对 `*.sh` 固定 LF（本次未改，避免牵连其它文件行尾变更）。

### 4.4 调试过程摘要（复现路径）

1. 脚本原样执行：T01~T05 通过，T06 报 `queue flow did not return queueToken`。
2. 手工 POST `queue-u1` → `user-not-found`；改用播种用户后串行下单仍不触发排队。
3. 60 路并发验证等待室功能本身正常（44/60 返回 `QUEUED`）。
4. 第二轮冒烟 T04 失败 → 查 Redis 发现 `sku-iphone` 库存 0 → 补 500 仍失败 → 定位到 stock Pod 内存 DB 账本耗尽 → 重启 stock 副本。
5. 直连单 gateway Pod 复跑：全链路通过。

## 5. 遗留问题与建议

1. **库存“DB 账本”不应是进程内存**（E02 的根因）：每个 stock Pod 独立内存账本导致 confirm 判定依赖“请求恰好落在账本未耗尽的 Pod”，且重启即回滚账本，与 Redis/MySQL 的事实数据不一致。建议落 MySQL（middleware 库）或至少提高播种量并把 `reconcile` 排入定时任务。
2. **集群 profile 是 `prod` 不是 `uat`**：`uat_smoke_test.sh` 名义上面向 `uat` profile，而 k3s 上跑的是 `prod`（限流阈值为 prod 值：user-window-limit 8/s，query/order 令牌桶也是 prod 大额度）。若要验证 uat profile 下的限流/等待室参数，需另发 `SPRING_PROFILES_ACTIVE=uat` 的部署，本次按现状记录。
3. **等待室重放验证依赖单一 Pod**：`canPass(businessKey, queueToken)` 的票据在网关进程内存中（`WaitingRoomService`），双 Pod 下拿到的 token 重放到另一 Pod 会失败。当前脚本直连单 Pod 规避；网关多副本下等待室需要共享存储（Redis）才能生产可用。
4. **冒烟会写入演示订单**：每轮冒烟产生 10~30 条 u101/u300 的 SUBMITTED/QUEUED 记录与若干未支付订单，属预期演示数据；如需干净环境可清 `galaxy_order_0/1` 分库表。
5. `rocketmq` 消费失败原因（`order-consumer fail <orderId> <reason>`）只进内存 `TraceLogger`，排障不可见；建议接回 logback stdout。

## 6. 变更清单（随本报告一起提交）

- `scripts/uat_smoke_test.sh`：修复排队用户与并发触发方式（4.1/4.2）。
- `docs/uat-smoke-test-report-2026-10-25.md`：本报告。
