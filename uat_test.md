# UAT 测试说明

## 1. UAT 目标

UAT 不是只验证接口能通，而是验证这 4 条关键生产链路在准生产配置下是否成立：

- `gateway` 限流与等待室是否按 `uat` profile 生效
- `stock` 是否真的走 `Redis Lua`
- `order` 是否真的走 `RocketMQ + MySQL 分片`
- `payment` 是否能闭环推进订单状态

## 2. UAT 环境前置条件

- 已部署 `gateway / user / product / stock / order / payment`
- `SPRING_PROFILES_ACTIVE=uat`
- `Redis` 可用
- `RocketMQ` 可用
- `MySQL` 两个订单分库已可连接
- UAT 网关地址可访问，例如 `http://uat-gateway.example.com`

代码中的 `uat` profile：

- [galaxy_mall-gateway/src/main/resources/application-uat.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-gateway/src/main/resources/application-uat.yml)
- [galaxy_mall-order/src/main/resources/application-uat.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-order/src/main/resources/application-uat.yml)
- [galaxy_mall-stock/src/main/resources/application-uat.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-stock/src/main/resources/application-uat.yml)
- [galaxy_mall-payment/src/main/resources/application-uat.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-payment/src/main/resources/application-uat.yml)

## 3. 自动化测试入口

仓库里提供了两个入口：

1. 集成测试

```bash
mvn -q test
mvn -q test -Dgalaxy.test.realInfra=true
```

第二条会启用真实 `Redis + MySQL + RocketMQ` 链路，并且在测试中激活 `uat` profile。

2. 面向已部署 UAT 网关的脚本

```bash
BASE_URL=http://uat-gateway.example.com ./scripts/uat_smoke_test.sh
```

脚本文件：

- [scripts/uat_smoke_test.sh](/Users/ibqo/Develop/git/github/java/galaxy_mall/scripts/uat_smoke_test.sh)

## 4. UAT 场景矩阵

| 场景 | 验证点 | 通过标准 |
| --- | --- | --- |
| 商品查询 | 读路径可用 | 网关返回商品详情，静态页可访问 |
| 异步下单 | 写路径异步化 | 订单先 `SUBMITTED`，后推进到 `WAITING_PAYMENT` |
| 支付闭环 | 状态流转 | 支付后订单变 `PAID` |
| 库存不足 | 异步失败与回补 | 订单最终 `FAILED`，库存没有负数 |
| 等待室 | 削峰与排队 | 高频同用户下单能返回 `QUEUED` 与 `queueToken` |
| 健康检查 | 运维可见性 | `/actuator/health` 返回 `UP` |
| 监控指标 | Prometheus 接入 | `/actuator/prometheus` 可抓取 |

## 5. 建议执行顺序

1. 先跑健康检查

```bash
curl -fsS $BASE_URL/actuator/health/readiness
curl -fsS $BASE_URL/actuator/prometheus | head
```

2. 再跑 UAT 脚本

```bash
BASE_URL=$BASE_URL ./scripts/uat_smoke_test.sh
```

3. 再跑真实链路集成测试

```bash
mvn -q test -Dgalaxy.test.realInfra=true
```

4. 最后做压测与故障演练

## 6. 压测建议

UAT 环境建议分三轮：

- 轮次 1：`1x` 基线压测，验证功能与 RT
- 轮次 2：`3x` 峰值压测，观察 Redis / RocketMQ / MySQL 热点
- 轮次 3：故障压测，主动下线一部分 `gateway/order/stock` Pod，观察恢复能力

重点指标：

- `gateway`：限流命中率、等待室积压、P99
- `order`：MQ 消费滞后、失败订单比例
- `stock`：Lua 错误率、库存偏差
- `mysql`：连接池、慢 SQL、行锁等待
- `rocketmq`：消费重试与堆积

## 7. 故障演练清单

- 关闭一台 `gateway` Pod，验证入口是否继续可用
- 暂停 `order` 消费者，验证 MQ 是否堆积但请求不雪崩
- 临时让 `stock` 连接错误 Redis，验证下单是否失败且无超卖
- 暂停一个订单分片库，验证报警和回滚流程

## 8. UAT 通过标准

- 主链路场景全部通过
- `health/prometheus` 均可用
- Redis / RocketMQ / MySQL 真链路已走通
- 压测期间没有出现库存负数、订单乱序、MQ 大量积压
- 故障演练后系统能恢复，且回滚步骤可执行
