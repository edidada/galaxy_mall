# galaxy_mall

基于 `design.md`，我把仓库从单模块示例工程改成了 Maven 多模块项目，并实现了一套可运行的高并发商城简化骨架，重点覆盖文档里的核心链路：三级限流、多级缓存、异步下单、库存预扣、消息重试和秒杀隔离。

## 本次完成内容

1. 根工程改成聚合父 POM，按设计文档拆分出以下子模块：

- `galaxy_mall-common`
- `galaxy_mall-cache`
- `galaxy_mall-message`
- `galaxy_mall-db`
- `galaxy_mall-monitor`
- `galaxy_mall-user`
- `galaxy_mall-product`
- `galaxy_mall-stock`
- `galaxy_mall-order`
- `galaxy_mall-payment`
- `galaxy_mall-gateway`
- `galaxy_mall-seckill`
- `galaxy_mall-web`

2. 实现了核心业务骨架：

- 公共域模型：`Product`、`User`、`Order`、`OrderStatus`、`StockReservation`
- 缓存层：布隆过滤器 + L1/L2/L3 多级缓存 + 热点预热
- 数据层：商品、用户、订单分片仓储，库存账本，本地 Binlog
- 消息层：内存消息总线、失败重试、死信队列
- 库存层：单机限流、Redis 预扣模拟、数据库确认扣减、库存回补/对账入口
- 订单层：异步下单、写 Binlog、投递订单消息、消费端确认库存
- 网关层：按 IP / 用户 / 接口三个维度限流，超限返回排队页
- 秒杀层：独立秒杀限流，复用订单能力做隔离下单
- 监控层：基础计数器、延迟记录、Trace 日志

3. 新增可执行自测入口：

- 主类：`cn.wdidada.galaxymall.web.MallConsoleApplication`
- 模拟并校验以下场景：
- 商品查询与缓存命中
- 异步下单重试后成功
- 支付成功后订单状态流转
- 库存不足拒单
- 网关排队页触发
- 秒杀接口限流
- 死信队列落盘

## 自测执行

执行了聚合打包：

```bash
mvn -q -DskipTests package
```

执行了场景自测：

```bash
java -cp "<all-modules-target-classes>" cn.wdidada.galaxymall.web.MallConsoleApplication
```

实际输出结果：

```text
Galaxy Mall self-check passed
paid-order=ORD-1777452598225-1001
binlog-events=2
metrics={stock.redis.reserve.success=2, stock.db.confirm.success=2, stock.redis.reject=1, payment.success=1, order.place.accepted=2}
orders=2
```

说明：

- 自测已验证一次订单异步消费首次失败、重试后成功
- 商品缓存查询命中正常，静态页可返回
- 库存不足请求被明确拒绝，不会超卖
- 同一 IP 连续请求会被网关转入排队页
- 毒消息会进入死信队列

## 代码入口

- 聚合配置：`/pom.xml`
- 自测入口：`/galaxy_mall-web/src/main/java/cn/wdidada/galaxymall/web/MallConsoleApplication.java`
- 订单异步消费：`/galaxy_mall-order/src/main/java/cn/wdidada/galaxymall/order/OrderFulfillmentConsumer.java`
- 网关限流：`/galaxy_mall-gateway/src/main/java/cn/wdidada/galaxymall/gateway/GatewayFacade.java`
- 多级缓存：`/galaxy_mall-cache/src/main/java/cn/wdidada/galaxymall/cache/MultiLevelCache.java`
- 库存防护：`/galaxy_mall-stock/src/main/java/cn/wdidada/galaxymall/stock/StockService.java`

## 当前说明

这版实现是面向架构设计文档的可运行骨架，不是完整的 Spring Cloud 生产系统。当前用纯 Java 内存实现把核心链路先落地，后续如果继续扩展，可以再把消息总线、Redis、数据库、网关和监控替换成真实中间件实现。
