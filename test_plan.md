# Galaxy Mall Test Plan

## Goal

验证当前多模块商城工程在两类环境下都可用：

1. 默认本地回退链路
2. 真实中间件链路

覆盖重点：

- `Spring Cloud Gateway` 正式路由和编排接口
- 异步下单状态推进
- `Redis Lua` 库存预扣、确认、回补
- `RocketMQ` 订单异步消息发布和消费
- `MySQL` 分片订单库存储
- 高并发入口的等待室和限流主路径

## Scope

### In Scope

- 构建验证
- 网关 HTTP 主链路验证
- 订单异步处理验证
- 支付状态流转验证
- 库存不足失败链路验证
- 等待室放行验证
- `Redis + MySQL + RocketMQ` 真实基础设施联调验证

### Out Of Scope

- 压测与 TPS 基准
- 分布式事务补偿任务的长期稳定性
- `Nacos / Sentinel / OpenTelemetry` 联调
- 多机房、多实例、多 Broker 高可用测试

## Test Levels

### Level 1: Build Verification

目标：

- 确保多模块聚合工程可完整打包

命令：

```bash
mvn -q -DskipTests package
```

通过标准：

- 所有模块打包成功
- 可执行 `exec.jar` 产物正常产出

### Level 2: Default Fallback Integration

目标：

- 验证不依赖外部中间件时，默认回退链路仍然可用

命令：

```bash
mvn -q test
```

验证点：

- 网关商品查询
- Spring Cloud Gateway 正式路由转发
- 异步下单到 `WAITING_PAYMENT`
- 支付成功
- 库存不足异步失败
- 等待室 `queueToken` 放行
- `jdbc-sharded` 在 H2(MySQL mode) 下的分片存储路径

通过标准：

- `SpringCloudSmokeTest` 全部通过

### Level 3: Real Infra Integration

目标：

- 验证代码在真实 `Redis + MySQL + RocketMQ` 下的主链路

前置条件：

- `MySQL` 可访问
- `Redis` 可启动
- `RocketMQ 4.8.0` 可启动，使用兼容 `JDK 8`

命令：

```bash
mvn -q test -Dgalaxy.test.realInfra=true
```

默认参数：

- `galaxy.test.mysql.host=127.0.0.1`
- `galaxy.test.mysql.port=3306`
- `galaxy.test.mysql.username=root`
- `galaxy.test.mysql.password=5%Edidada`
- `galaxy.test.redis.host=127.0.0.1`
- `galaxy.test.redis.port=6379`
- `galaxy.test.rocketmq.namesrv=127.0.0.1:9876`

验证点：

- 订单分片库自动建库、清表、落单
- `Redis Lua` 预扣库存
- `RocketMQ` 异步消费推进订单状态
- 网关到下游服务的完整主链路

通过标准：

- `SpringCloudSmokeTest` 在真实中间件模式下通过

## Scenario Matrix

1. 商品查询成功
2. 正式网关路由 `/edge/products/{productId}` 成功转发
3. 正常下单，状态从 `SUBMITTED` 推进到 `WAITING_PAYMENT`
4. 支付接口将订单推进到 `PAID`
5. 库存不足订单进入 `FAILED`
6. 高频请求进入等待室并通过 `queueToken` 二次放行
7. 分片订单库能查询到订单状态更新
8. Redis 库存脚本能完成 `reserve / confirm / release`
9. RocketMQ 消息能触发异步消费

## Execution Order

1. 执行 `mvn -q test`
2. 启动真实 `Redis`
3. 启动真实 `RocketMQ nameserver`
4. 启动真实 `RocketMQ broker`
5. 验证 `MySQL` 可连接
6. 执行 `mvn -q test -Dgalaxy.test.realInfra=true`
7. 执行 `mvn -q -DskipTests package`

## Exit Criteria

- 三条命令全部成功：

```bash
mvn -q test
mvn -q test -Dgalaxy.test.realInfra=true
mvn -q -DskipTests package
```

- 无测试失败
- 无构建失败

## Artifacts

- 测试方案文件：`test_plan.md`
- 测试代码：`galaxy_mall-web/src/test/java/cn/wdidada/galaxymall/web/SpringCloudSmokeTest.java`
- RocketMQ 本地测试配置：`dev/rocketmq-broker.conf`
