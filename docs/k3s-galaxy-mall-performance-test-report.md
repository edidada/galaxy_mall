# Galaxy Mall K3s 性能测试报告

- 报告日期：2026-10-25
- 报告范围：仓库已有性能测试记录与当前 K3s 部署配置的对照分析
- 当前结论：**已有单节点历史压测数据，但尚无可用于当前五节点 K3s、真实 MySQL + Redis + RocketMQ 链路的可复现实测基线。**

## 1. 执行摘要

历史报告记录的商品查询峰值约为 `6,017 TPS`、`13,229 TPS` 和 `16,600 TPS`，均来自 2026-05 的单节点环境与不同副本配置。这些结果可说明限流、多副本和缓存路径具有改善效果，但不能用于宣称当前 K3s 集群可达到 `100 万 TPS`，也不能代表真实下单写链路吞吐。

当前 K3s 部署已经将库存和订单主链路接入 Redis、RocketMQ、MySQL：

```text
Gateway -> Stock -> Redis
Gateway -> Order -> MySQL + RocketMQ
```

因此性能验收必须分为两条链路：

| 链路 | 代表接口 | 核心瓶颈 | 不应混用的指标 |
| --- | --- | --- | --- |
| 读链路 | 商品查询 | Gateway、Product、Redis、入口网络 | 不能用静态页/CDN 吞吐代表业务查询 TPS |
| 写链路 | 创建订单/支付 | 热点库存、MySQL 分片写入、RocketMQ、订单状态机 | 不能用读接口 TPS 代表下单 TPS |

## 2. 历史数据汇总

以下数据来自仓库既有报告，均为历史观察值，未在本报告生成时重新执行。

| 测试时间 | 环境口径 | 最佳 TPS | P99 | 错误率 | 证据文件 |
| --- | --- | ---: | ---: | ---: | --- |
| 2026-05-04 | 初始单节点，商品查询 | 6,016.68 | 44.76ms | 未记录 | [`100w-tps-test-results.md`](../100w-tps-test-results.md) |
| 2026-05-05 | 优化后单节点，8 线程/500 连接/30 秒 | 13,229.12 | 1.01s | 1.93% | [`100w-tps-test-results-updated.md`](../100w-tps-test-results-updated.md) |
| 2026-05-05 | 短期优化后单节点，8 线程/300 连接/30 秒 | 16,600.48 | 847.54ms | 报告称 <0.1% | [`short-term-optimization-results.md`](../short-term-optimization-results.md) |

### 2.1 数据解释

1. `16,600.48 TPS` 是仓库中最大的历史值，但只有汇总结论，没有对应的 wrk 原始输出、HTTP 状态分布、机器资源曲线或服务日志，不能单独作为发布门禁。
2. `13,229.12 TPS` 虽然吞吐较高，但错误率 `1.93%` 已超过通常的服务稳定性目标，不能判定为合格容量。
3. 初始测试 `6,016.68 TPS` 的 P99 为 `44.76ms`，延迟较好，但仅说明低并发单节点读路径的观测值。
4. 三组数据来自不同副本数、限流参数和中间件状态，不能直接计算线性提升比例，也不能外推到五节点集群。

## 3. 测试方法可信度

历史文档把工具写为 `wrk 4.2.0`，同时测试方案又使用 `-R` 固定请求速率参数。`-R` 是 `wrk2` 的常用参数，而标准 `wrk` 不支持该固定速率模型。

因此，历史数据必须附带以下限制：

- 未保留每次执行使用的是 `wrk` 还是 `wrk2` 的版本输出。
- 未保留完整命令、压测机 CPU/网络使用率和原始结果。
- 不能判断压测机是否已先于服务端达到 CPU、连接或带宽上限。
- 未明确区分 HTTP 200 与业务响应体 `success:true`；当前 Gateway 可能将下游失败包装为 HTTP 200，单看 HTTP 状态码会高估成功率。

结论：历史数据可作为回归参考，不可作为当前集群 SLA、容量规划或“100 万 TPS 已达成”的证据。

## 4. 当前 K3s 性能前置条件

当前业务清单通过 `galaxy-mall-config` 注入以下真实中间件路径：

| 服务 | 关键配置 | 性能影响 |
| --- | --- | --- |
| `galaxy-mall-stock` | `GALAXY_STOCK_BACKEND=redis`、`redis.middleware.svc.cluster.local:6379` | Redis 延迟、热点 SKU 锁/脚本争用直接影响库存请求 |
| `galaxy-mall-order` | `GALAXY_ORDER_MESSAGING_BACKEND=rocketmq`、`rocketmq-namesrv.middleware.svc.cluster.local:9876` | 消息生产、消费滞后和 Broker 磁盘影响异步订单流转 |
| `galaxy-mall-order` | `GALAXY_ORDER_STORE_BACKEND=jdbc-sharded`、`mysql.middleware.svc.cluster.local:3306` | MySQL 写入、连接池和分片热点限制下单吞吐 |

中间件地址及部署顺序的静态验证见 [k3s-galaxy-mall-middleware-dependencies-test-report.md](k3s-galaxy-mall-middleware-dependencies-test-report.md)。

压测开始前必须满足：

- `middleware` 中 MySQL、Redis、RocketMQ NameServer/Broker 都为 Ready，且 Service 有 Ready Endpoint。
- `galaxy-mall` 业务 Deployment 均 Ready，观察窗口内无重启增长。
- 固定镜像 digest 或 `git-<sha>`，记录实际副本数、资源 requests/limits 与节点分布。
- 压测机至少一台独立于 K3s 工作节点；超过单机压测能力时使用多台压测机分摊连接和网卡负载。
- 监控同时记录节点、Pod、Redis、MySQL、RocketMQ 指标，不能只记录 wrk 结果。

## 5. 建议的可复现测试矩阵

### 5.1 公共规则

- 工具统一为 `wrk2`，先执行 `wrk --version` 并写入结果文件。
- 请求以 `Host: mall.192.168.31.77.nip.io` 进入 Traefik；入口节点应轮换 `.77`、`.211`、`.251`、`.88`、`.243`，避免单一入口掩盖瓶颈。
- 每一档先预热 60 秒，再测量 180 秒；稳定性档位至少运行 30 分钟。
- 成功率按业务响应体计算：除 HTTP 状态外，校验 JSON 中 `success:true`；Gateway 返回的 `downstream-unavailable` 必须计为失败。
- 每档测试前后记录 Git SHA、镜像、Deployment 副本、限流配置、压测机硬件与中间件指标。

### 5.2 商品查询读链路

| 阶段 | 目标速率 | 持续时间 | 通过标准 |
| --- | ---: | ---: | --- |
| 预热 | 1,000 RPS | 60s | 无连续 5xx，缓存和 JVM 完成预热 |
| 基线 | 5,000 RPS | 180s | 业务成功率 >= 99.9%，P99 <= 100ms |
| 容量递增 | 10,000 起，每档 +5,000 RPS | 每档 180s | 记录首个违反 SLO 的档位 |
| 稳定性 | 已通过最大档位的 80% | 30min | 业务成功率 >= 99.9%，无 Pod 重启/驱逐 |

示例命令：

```bash
wrk -H 'Host: mall.192.168.31.77.nip.io' \
  -t8 -c1000 -d180s -R5000 \
  'http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1'
```

仅当实际二进制确认是 `wrk2` 时才使用 `-R`。若使用标准 `wrk`，删除 `-R`，并在报告中将结果标记为“尽力而为压测”，不能与固定速率数据并列比较。

### 5.3 创建订单写链路

| 阶段 | 目标速率 | 持续时间 | 通过标准 |
| --- | ---: | ---: | --- |
| 预热 | 100 RPS | 60s | 订单可从创建推进到预期状态 |
| 基线 | 500 RPS | 180s | 业务成功率 >= 99.9%，订单无丢失 |
| 容量递增 | 每档 +500 RPS | 每档 180s | 消费滞后、MySQL 延迟与库存失败率受控 |
| 稳定性 | 已通过最大档位的 70% | 30min | RocketMQ 不持续堆积，订单终态可核对 |

写链路通过标准不能只看 HTTP 响应。抽样订单必须核对：订单分片已持久化、库存状态正确、RocketMQ 消费组没有持续积压、支付/订单终态符合预期。

## 6. 推荐监控采集

```bash
# 业务与中间件运行状态。
kubectl -n galaxy-mall get deploy,pod -o wide
kubectl -n middleware get deploy,statefulset,pod -o wide

# 节点与 Pod 资源；需要 metrics-server。
kubectl top nodes
kubectl top pods -A --sort-by=cpu

# 发布或压测期间持续观察异常事件。
kubectl -n galaxy-mall get events --sort-by=.lastTimestamp
kubectl -n middleware get events --sort-by=.lastTimestamp
```

每档结果至少采集：压测机 CPU/网络、Gateway/Order/Stock 的 CPU、内存、重启次数、MySQL 连接/慢查询、Redis 延迟/命中率、RocketMQ topic 堆积和消费延迟。

## 7. 风险与容量判断

- `100 万 TPS` 只能作为静态资源/CDN 命中等极窄场景的远期目标，不能与 Gateway -> Order -> MySQL + RocketMQ 的真实写链路共用一个指标。
- RocketMQ 用于削峰和异步解耦，不会消除 MySQL 写入与热点库存的上限。
- 单台 R630 上运行的多 VM、K3s、中间件和压测机可能互相争用 CPU、内存、磁盘与网卡。压测机与被测服务同宿主时，结果只能说明该宿主的混合负载上限。
- 中间件当前的单副本与 local-path 存储会影响稳定性测试；单节点故障会同时影响性能与可用性。

## 8. 最终判定

当前仓库能够证明：历史单节点读链路达到过约 `16.6k TPS` 的记录，且业务配置已经具备 Redis、RocketMQ、MySQL 的真实中间件路径。

当前仓库不能证明：当前五节点 K3s 的读链路或写链路达到任何固定 TPS，更不能证明达到 `100 万 TPS`。

下一次正式压测应按第 5 节执行，并将原始 wrk2 输出、业务成功率、集群资源曲线和中间件指标一并归档；只有满足这些证据链，结果才可用于容量决策。
