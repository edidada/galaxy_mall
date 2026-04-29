# galaxy_mall

基于 `design.md`，本仓库已经从单模块示例工程升级为 Spring Boot 3 + Spring Cloud OpenFeign 的 Maven 多模块微服务工程。当前实现重点是把核心服务边界、HTTP 接口、服务间调用、网关编排和可执行服务产物真正落下来，而不是只保留单 JVM 的演示骨架。

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

5. 落了真正的 HTTP + Feign 调用链路：

- `gateway -> user`
- `gateway -> product`
- `gateway -> order`
- `gateway -> payment`
- `order -> product`
- `order -> stock`
- `payment -> order`

核心编排代码：

- 网关工作流：
  `[GatewayWorkflowService.java](galaxy_mall/galaxy_mall-gateway/src/main/java/cn/wdidada/galaxymall/gateway/GatewayWorkflowService.java)`
- 订单工作流：
  `[CloudOrderService.java](galaxy_mall/galaxy_mall-order/src/main/java/cn/wdidada/galaxymall/order/CloudOrderService.java)`
- 支付工作流：
  `[CloudPaymentService.java](galaxy_mall/galaxy_mall-payment/src/main/java/cn/wdidada/galaxymall/payment/CloudPaymentService.java)`

6. 保留并复用了之前骨架里有价值的能力：

- 商品服务继续使用多级缓存和布隆过滤器
- 库存服务继续使用本地限流和预扣模型
- 订单服务继续写本地 Binlog
- 网关层继续保留按 IP / 用户 / 接口三维限流和排队页返回

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
mvn -q clean test
mvn -q -DskipTests package
```

结果：

- `clean test` 通过
- `package` 通过

测试内容不是单元级 mock，而是启动 6 个 Spring Boot 服务上下文做网关级冒烟验证。

测试文件：
`[SpringCloudSmokeTest.java](galaxy_mall/galaxy_mall-web/src/test/java/cn/wdidada/galaxymall/web/SpringCloudSmokeTest.java)`

覆盖场景：

- 通过网关查询商品
- 通过网关下单
- 通过网关支付
- 库存不足拒单
- 同一 IP 高频访问触发排队页

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

## 现在这版的边界

这版已经不是“单 JVM 演示骨架”，而是实打实的 Spring Boot / Spring Cloud 服务化工程，具备：

- 独立服务进程
- REST API
- Feign 调用
- 配置文件
- 可执行服务包
- Spring 集成冒烟测试

但它还不是完整的生产中间件接入版，当前仍然有这些边界：

- 注册中心还没有接 `Nacos/Eureka`
- 网关还没有换成 `Spring Cloud Gateway + 路由规则`
- 库存、订单、商品当前仍是内存仓储，不是 `MySQL + MyBatis`
- 消息链路还没有接真实 `RocketMQ/Kafka`
- 缓存还没有接真实 `Redis`
- Sentinel、链路追踪、配置中心还没接入

如果继续往“生产版”推进，下一步应该优先做：

1. `Nacos` 服务注册与配置中心
2. `Gateway` 正式路由和全局过滤器
3. `MySQL + MyBatis-Plus`
4. `Redis` 缓存和库存脚本
5. `RocketMQ` 订单异步消息
6. `Sentinel` 限流熔断
