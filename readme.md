# galaxy_mall

基于 `design.md`，本仓库已经从单模块示例工程升级为 Spring Boot 3 + Spring Cloud 的 Maven 多模块微服务工程。当前实现重点不再只是“服务拆分”，而是把大促核心链路往“流量削峰、读静态化、写异步化、库存本地化”推进，并把 `Redis Lua`、`RocketMQ`、`Spring Cloud Gateway`、`JDBC 分片订单库` 这 4 条生产化路径补进了代码。

## 这次我实际做了什么

1. 把根工程改成 Spring Boot / Spring Cloud 依赖管理：

- Spring Boot 父工程：`3.2.5`
- Spring Cloud BOM：`2023.0.1`
- 根聚合模块：`[pom.xml]`

2. 保留并整理多模块结构：

- `galaxy_mall-common`：公共 DTO、Feign Client、领域模型
- `galaxy_mall-cache`：多级缓存、布隆过滤器、缓存预热
- `galaxy_mall-db`：内存仓储、本地 Binlog
- `galaxy_mall-monitor`：指标计数、Trace 日志
- `galaxy_mall-user`：用户服务
- `galaxy_mall-product`：商品服务
- `galaxy_mall-stock`：库存服务
- `galaxy_mall-order`：订单服务
- `galaxy_mall-payment`：支付服务
- `galaxy_mall-gateway`：网关编排服务
- 其他模块继续保留为扩展位

3. 把基础服务改成独立 Spring Boot 应用：

- 用户服务启动类：
  `[UserServiceApplication.java](galaxy_mall/galaxy_mall-user/src/main/java/cn/wdidada/galaxymall/user/UserServiceApplication.java)`
- 商品服务启动类：
  `[ProductServiceApplication.java](galaxy_mall/galaxy_mall-product/src/main/java/cn/wdidada/galaxymall/product/ProductServiceApplication.java)`
- 库存服务启动类：
  `[StockServiceApplication.java](galaxy_mall/galaxy_mall-stock/src/main/java/cn/wdidada/galaxymall/stock/StockServiceApplication.java)`
- 订单服务启动类：
  `[OrderServiceApplication.java](galaxy_mall/galaxy_mall-order/src/main/java/cn/wdidada/galaxymall/order/OrderServiceApplication.java)`
- 支付服务启动类：
  `[PaymentServiceApplication.java](galaxy_mall/galaxy_mall-payment/src/main/java/cn/wdidada/galaxymall/payment/PaymentServiceApplication.java)`
- 网关服务启动类：
  `[GatewayApplication.java](galaxy_mall/galaxy_mall-gateway/src/main/java/cn/wdidada/galaxymall/gateway/GatewayApplication.java)`

4. 新增并统一了公共 API 契约：

- `ApiResponse`
- `CreateOrderRequest`
- `GatewayOrderRequest`
- `ProductView`
- `UserView`
- `OrderView`
- `PaymentView`
- `StockDeductRequest`
- `StockView`
- `UserClient`
- `ProductClient`
- `StockClient`
- `OrderClient`
- `PaymentClient`

对应目录：
`[galaxy_mall-common/src/main/java/cn/wdidada/galaxymall/common/api](galaxy_mall/galaxy_mall-common/src/main/java/cn/wdidada/galaxymall/common/api)`
`[galaxy_mall-common/src/main/java/cn/wdidada/galaxymall/common/client](galaxy_mall/galaxy_mall-common/src/main/java/cn/wdidada/galaxymall/common/client)`

5. 落了真正的 HTTP 调用链路：

- `gateway -> user/product/order/payment` 走 `RestClient`
- `order -> product`
- `order -> stock`
- `payment -> order`

其中：

- 网关不再依赖 `OpenFeign`，避免 `Spring Cloud Gateway` 和 Feign 子上下文相互污染
- 订单、支付服务之间仍保留 `OpenFeign`，作为内部服务调用实现

核心编排代码：

- 网关工作流：
  `[GatewayWorkflowService.java](galaxy_mall/galaxy_mall-gateway/src/main/java/cn/wdidada/galaxymall/gateway/GatewayWorkflowService.java)`
- 订单工作流：
  `[CloudOrderService.java](galaxy_mall/galaxy_mall-order/src/main/java/cn/wdidada/galaxymall/order/CloudOrderService.java)`
- 支付工作流：
  `[CloudPaymentService.java](galaxy_mall/galaxy_mall-payment/src/main/java/cn/wdidada/galaxymall/payment/CloudPaymentService.java)`

6. 这轮重点补强了高并发核心链路：

- 网关层新增令牌桶 + 漏桶双层限流，核心订单流量走“快车道 + 等待室”两段式削峰
- 等待室支持 `queueToken`，超出快车道的请求不会直接失败，而是进入排队页并在放行时间后重试
- 网关正式切到 `Spring Cloud Gateway`，补了 `/edge/users/**`、`/edge/products/**`、`/edge/stocks/**`、`/edge/orders/**`、`/edge/payments/**` 路由，以及 `X-Trace-Id` 全局透传过滤器
- 订单服务改成“提交成功即返回，后台异步消费处理”的模型
- 订单提交后会先写本地 Binlog，再发布 `OrderSubmittedEvent`
- 消息层新增双实现：
  - `local`：继续走内存消息总线，便于本地和测试环境自测
  - `rocketmq`：新增 `RocketMqOrderMessagePublisher`，并在订单服务里显式装配 `DefaultMQProducer + DefaultMQPushConsumer`
- 订单存储新增双实现：
  - `local`：内存仓储
  - `jdbc-sharded`：`JdbcShardedOrderStore`，按 `库分片 + 表分片` 路由订单
- 库存服务拆成 `reserve / confirm / release` 三段接口，补了 `Redis Lua` 后端：
  - `in-memory`：本地预扣 + 本地限流
  - `redis`：`reserve_stock.lua / confirm_stock.lua / release_stock.lua`
- 商品服务补了静态页直出接口，保留多级缓存和布隆过滤器能力
- 消息总线改成后台持续消费，不再依赖手工 `drain`

7. 清理了已经被 Spring 化实现替代的旧类：

- 删除了旧的单 JVM 编排入口和过期的控制台自测入口
- 删除了旧的 `GatewayFacade`、旧订单异步演示类等残留代码
- 避免新旧两套主线同时存在，导致聚合构建污染

## 当前服务端口

- gateway: `8080`
- user: `8081`
- product: `8082`
- stock: `8083`
- order: `8084`
- payment: `8085`

对应配置文件：

- `[galaxy_mall-gateway/src/main/resources/application.yml](galaxy_mall/galaxy_mall-gateway/src/main/resources/application.yml)`
- `[galaxy_mall-user/src/main/resources/application.yml](galaxy_mall/galaxy_mall-user/src/main/resources/application.yml)`
- `[galaxy_mall-product/src/main/resources/application.yml](galaxy_mall/galaxy_mall-product/src/main/resources/application.yml)`
- `[galaxy_mall-stock/src/main/resources/application.yml](galaxy_mall/galaxy_mall-stock/src/main/resources/application.yml)`
- `[galaxy_mall-order/src/main/resources/application.yml](galaxy_mall/galaxy_mall-order/src/main/resources/application.yml)`
- `[galaxy_mall-payment/src/main/resources/application.yml](galaxy_mall/galaxy_mall-payment/src/main/resources/application.yml)`

## 产物说明

为了解决“服务模块既要能作为依赖使用，又要能产出可执行 fat jar”的问题，我把 Spring Boot 可执行包改成了 `exec` 分类产物：

- 普通依赖 jar：`galaxy_mall-xxx-1.0-SNAPSHOT.jar`
- 可执行服务 jar：`galaxy_mall-xxx-1.0-SNAPSHOT-exec.jar`

已实际产出：

- `galaxy_mall-user-1.0-SNAPSHOT-exec.jar`
- `galaxy_mall-product-1.0-SNAPSHOT-exec.jar`
- `galaxy_mall-stock-1.0-SNAPSHOT-exec.jar`
- `galaxy_mall-order-1.0-SNAPSHOT-exec.jar`
- `galaxy_mall-payment-1.0-SNAPSHOT-exec.jar`
- `galaxy_mall-gateway-1.0-SNAPSHOT-exec.jar`

## 自测和验证

我实际执行过以下命令：

```bash
mvn -q test
mvn -q -DskipTests package
```

结果：

- `test` 通过
- `package` 通过

如果本机已经有真实中间件，也可以直接跑真实链路测试：

```bash
mvn -q test -Dgalaxy.test.realInfra=true
```

这条命令会把自测切到：

- `Redis`：库存后端走 `galaxy.stock.backend=redis`
- `MySQL`：订单后端走 `jdbc-sharded`
- `RocketMQ`：订单异步消息走 `rocketmq`

默认读取这些参数：

- `galaxy.test.mysql.host=127.0.0.1`
- `galaxy.test.mysql.port=3306`
- `galaxy.test.mysql.username=root`
- `galaxy.test.mysql.password=5%Edidada`
- `galaxy.test.redis.host=127.0.0.1`
- `galaxy.test.redis.port=6379`
- `galaxy.test.rocketmq.namesrv=127.0.0.1:9876`

这些值都可以用 `-D...` 覆盖。

测试内容不是单元级 mock，而是启动 6 个 Spring Boot 服务上下文做网关级冒烟验证。

测试文件：
`[SpringCloudSmokeTest.java](galaxy_mall/galaxy_mall-web/src/test/java/cn/wdidada/galaxymall/web/SpringCloudSmokeTest.java)`

覆盖场景：

- 通过网关查询商品和商品静态页
- 通过 `Spring Cloud Gateway` 正式路由访问商品接口
- 通过网关异步下单，轮询直到订单进入 `WAITING_PAYMENT`
- 通过网关支付
- 库存不足场景下，订单会先被受理，再在后台异步失败
- 同一用户高频下单触发等待室，拿到 `queueToken` 后再次放行
- 订单服务在自测中使用 `jdbc-sharded` 存储路径，验证了分片仓储链路
- 在 `-Dgalaxy.test.realInfra=true` 模式下，实际验证 `Redis + MySQL + RocketMQ` 真链路

## 如何启动

先构建：

```bash
mvn -q -DskipTests package
```

然后分别启动：

```bash
java -jar galaxy_mall-user/target/galaxy_mall-user-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-product/target/galaxy_mall-product-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-stock/target/galaxy_mall-stock-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-order/target/galaxy_mall-order-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-payment/target/galaxy_mall-payment-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-gateway/target/galaxy_mall-gateway-1.0-SNAPSHOT-exec.jar
```

如果你要在本机起 `RocketMQ 4.8.0` 跑这套测试，可以直接复用仓库里的 broker 配置：

- `[dev/rocketmq-broker.conf](galaxy_mall/dev/rocketmq-broker.conf)`

这份配置已经把 broker 广播地址固定为 `127.0.0.1`，并把测试数据目录落到 `/tmp/galaxy-rocketmq`。

## 现在这版的边界

这版已经不是“单 JVM 演示骨架”，而是实打实的 Spring Boot / Spring Cloud 服务化工程，具备：

- 独立服务进程
- REST API
- Spring Cloud Gateway 正式路由
- 网关层 `RestClient` 调用
- 内部 Feign 调用
- 配置文件
- 可执行服务包
- Spring 集成冒烟测试
- 异步订单受理与后台消费
- 等待室与排队令牌
- 库存预扣 / 确认 / 回补三段模型
- Redis Lua 库存脚本实现
- RocketMQ 消息发布 / 消费实现
- JDBC 分库分表订单存储实现

但它还不是完整的生产中间件接入版，当前仍然有这些边界：

- 注册中心还没有接 `Nacos/Eureka`
- `Redis`、`RocketMQ`、分片订单库当前已经有代码路径和配置开关，但这次自测跑的是本地可回退实现，没有连真实外部中间件
- `jdbc-sharded` 当前使用 `JdbcTemplate + H2(MySQL 模式)` 自测，生产落地还需要换成真实 `MySQL/TiDB/PolarDB-X`
- 商品和用户服务当前仍是内存仓储，没有继续落到真实数据库
- Sentinel、链路追踪、配置中心还没接入

如果继续往“生产版”推进，下一步应该优先做：

1. `Nacos` 服务注册与配置中心
2. `Redis` 集群与库存 Lua 脚本联调
3. `RocketMQ` NameServer / Broker 联调和重试 / 死信策略
4. `MySQL/TiDB` 真实分片建表、路由规则和补偿任务
5. `Sentinel` 限流熔断
6. `OpenTelemetry` 链路追踪
