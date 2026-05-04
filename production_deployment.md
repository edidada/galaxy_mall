# 生产部署说明

```dockerfile
# 与仓库 deploy/docker/Dockerfile.exec-jar 保持一致
FROM eclipse-temurin:17-jre

ARG JAR_FILE
ARG APP_PORT=8080

ENV APP_PORT=${APP_PORT}
ENV JAVA_OPTS=""

WORKDIR /app
COPY ${JAR_FILE} app.jar

EXPOSE ${APP_PORT}

ENTRYPOINT ["sh","-c","exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75 ${JAVA_OPTS:-} -jar /app/app.jar"]
```

说明：当前仓库按服务模块分别打包并构建镜像，`JAR_FILE` 需要传入具体产物路径，例如 `galaxy_mall-gateway/target/galaxy_mall-gateway-1.0-SNAPSHOT-exec.jar`。

## 1. 当前代码已经补上的生产化能力

| 维度 | 当前代码落地 |
| --- | --- |
| 流量削峰 | 网关限流参数全部外置到 `galaxy.gateway.*`，支持令牌桶、漏桶、用户窗口限流、等待室容量与放行速率 |
| 读路径 | 商品服务支持静态页输出，网关和商品接口可前置 CDN / Nginx 缓存 |
| 写路径异步化 | 订单服务默认支持 `local` / `rocketmq` 两套消息后端，订单提交后异步消费库存链路 |
| 库存扣减 | 库存服务支持 `in-memory` / `redis` 两套后端，`redis` 走 Lua 预扣、确认、回补，并补了确认补偿日志与接口幂等兜底 |
| 数据库抗压 | 订单服务支持 `jdbc-sharded`，按 `库分片 + 二次表分片` 路由，分片参数外置 |
| 可观测性 | 所有核心服务暴露 `health / info / prometheus`，支持 K8s liveness / readiness probe |
| 环境化配置 | 已补 `application-uat.yml`、`application-prod.yml`，可直接按 profile 启动 |

需要明确：`100w+ TPS` 不是靠单个 JVM 配置项实现，而是靠单元化、多副本、CDN 静态化、Redis/RocketMQ/MySQL 分片、机房容量规划共同实现。当前代码已经把这些链路的配置入口补齐，但生产指标成立的前提仍然是多实例部署和压测验收。

另一个需要明确的边界：`jdbc-sharded` 实现已经主动禁用 `findAll()` 全量扫描，生产排障和对账必须走分页、游标或分片维度查询，不能再依赖单接口全拉。

## 2. 推荐生产拓扑

请求路径：

`CDN / WAF / Anti-DDoS -> Nginx -> Spring Cloud Gateway -> user/product/order/stock/payment -> Redis / RocketMQ / MySQL`

推荐部署：

- `gateway`：无状态，至少 `8+` 副本，前挂 `Nginx/SLB/Ingress`
- `product`：无状态，至少 `4+` 副本，商品静态页前置 CDN
- `order`：至少 `6+` 副本，开启 `rocketmq + jdbc-sharded`
- `stock`：至少 `6+` 副本，开启 `redis` Lua 后端
- `payment`：至少 `4+` 副本
- `user`：至少 `2+` 副本
- `Redis`：独立集群或云 Redis，不能和应用 Pod 混部
- `RocketMQ`：NameServer 至少 2 个，Broker 主从部署
- `MySQL`：至少 2 个订单分库，按业务继续水平扩展

## 3. 配置文件与环境变量

核心 profile 文件：

- [galaxy_mall-gateway/src/main/resources/application-prod.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-gateway/src/main/resources/application-prod.yml)
- [galaxy_mall-order/src/main/resources/application-prod.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-order/src/main/resources/application-prod.yml)
- [galaxy_mall-stock/src/main/resources/application-prod.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-stock/src/main/resources/application-prod.yml)
- [galaxy_mall-payment/src/main/resources/application-prod.yml](/Users/ibqo/Develop/git/github/java/galaxy_mall/galaxy_mall-payment/src/main/resources/application-prod.yml)

重点环境变量：

- `SPRING_PROFILES_ACTIVE=prod`
- `SERVICES_USER_URL`
- `SERVICES_PRODUCT_URL`
- `SERVICES_STOCK_URL`
- `SERVICES_ORDER_URL`
- `SERVICES_PAYMENT_URL`
- `GALAXY_GATEWAY_QUERY_TOKEN_CAPACITY`
- `GALAXY_GATEWAY_ORDER_TOKEN_CAPACITY`
- `GALAXY_GATEWAY_WAITING_ROOM_CAPACITY`
- `GALAXY_STOCK_BACKEND=redis`
- `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`
- `GALAXY_ORDER_MESSAGING_BACKEND=rocketmq`
- `ROCKETMQ_NAME_SERVER`
- `ROCKETMQ_PRODUCER_GROUP`
- `GALAXY_ORDER_ROCKETMQ_TOPIC`
- `GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP`
- `GALAXY_ORDER_STORE_BACKEND=jdbc-sharded`
- `ORDER_SHARD_0_URL` / `ORDER_SHARD_1_URL`
- `ORDER_SHARD_0_USERNAME` / `ORDER_SHARD_1_USERNAME`
- `ORDER_SHARD_0_PASSWORD` / `ORDER_SHARD_1_PASSWORD`

## 4. K8s 部署步骤

仓库里已经补了 K8s 模板：

- [deploy/k8s/galaxy-mall-prod.yaml](/Users/ibqo/Develop/git/github/java/galaxy_mall/deploy/k8s/galaxy-mall-prod.yaml)
- [deploy/k8s/galaxy-mall-uat.yaml](/Users/ibqo/Develop/git/github/java/galaxy_mall/deploy/k8s/galaxy-mall-uat.yaml)
- [deploy/nginx/galaxy-mall.conf](/Users/ibqo/Develop/git/github/java/galaxy_mall/deploy/nginx/galaxy-mall.conf)
- [deploy/docker/Dockerfile.exec-jar](/Users/ibqo/Develop/git/github/java/galaxy_mall/deploy/docker/Dockerfile.exec-jar)

标准步骤：

1. 打包产物

```bash
mvn -q -DskipTests package
```

2. 为每个服务构建镜像

```bash
docker build -f deploy/docker/Dockerfile.exec-jar \
  --build-arg JAR_FILE=galaxy_mall-gateway/target/galaxy_mall-gateway-1.0-SNAPSHOT-exec.jar \
  -t registry.example.com/galaxy-mall/gateway:1.0.0 .
```

3. 修改 `deploy/k8s/galaxy-mall-prod.yaml` 中的镜像地址、Secret 密码、RocketMQ / Redis / MySQL 地址

4. 发布

```bash
kubectl apply -f deploy/k8s/galaxy-mall-prod.yaml
```

5. 验证

```bash
kubectl -n galaxy-mall-prod get pods
kubectl -n galaxy-mall-prod get ingress
curl -fsS http://<gateway-host>/actuator/health/readiness
curl -fsS http://<gateway-host>/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1
```

## 5. 边缘层与 Nginx

Nginx 模板已提供：

- [deploy/nginx/galaxy-mall.conf](/Users/ibqo/Develop/git/github/java/galaxy_mall/deploy/nginx/galaxy-mall.conf)

建议：

- CDN 缓存 `/api/products/*/page` 静态页
- Nginx 对 `/api/gateway/orders` 和 `/edge/orders` 做 `limit_req`
- `gateway` 前至少双层负载均衡
- 对非核心查询接口设置更激进的缓存和限速

## 6. 容量与扩缩容建议

生产环境不要把 profile 默认值直接理解成集群总吞吐。正确做法是：

- 先按单 Pod 基准压测测出 `gateway/order/stock` 的单实例极限
- 再根据目标峰值倒推副本数和 Redis / MQ / DB 集群规模
- 使用 `HPA` 基于 CPU、RT、队列堆积做扩容
- 订单库和库存 Redis 必须按热点商品分流，不能只靠一组分片

建议至少监控：

- `gateway`：QPS、限流数、等待室人数、5xx、P99
- `order`：提交数、MQ 堆积、消费重试、死信数、订单状态分布
- `stock`：Lua 失败数、预扣成功率、回补数、库存偏差、补偿日志积压
- `payment`：支付成功率、回调延迟
- `mysql`：慢 SQL、连接池、分片热点
- `rocketmq`：消费延迟、重试、DLQ

## 7. 回滚策略

- 镜像回滚：`kubectl rollout undo deployment/<service>`
- 配置回滚：保留上一版 ConfigMap / Secret
- 降级开关：
  - `GALAXY_ORDER_MESSAGING_BACKEND=local` 仅限紧急本地兜底，不建议长期生产使用
  - `GALAXY_STOCK_BACKEND=in-memory` 仅限开发演示，不允许生产使用

## 8. 上线前必须满足的验收项

- `uat` 环境通过完整链路测试
- 真 Redis / 真 RocketMQ / 真 MySQL 分片联调通过
- 压测达到目标副本下的预估峰值余量
- `health/readiness/prometheus` 已接入监控平台
- 已验证等待室过期回收、缓存 singleflight、库存确认补偿日志在压测和故障注入场景下正常工作
- 已完成灰度发布、回滚演练、Redis/RocketMQ/MySQL 故障演练
