# RocketMQ on k3s

这个文件给当前仓库提供一套最小可用的 `k3s` 部署方案，目标是让 `order` 服务在集群内通过 `RocketMQ` 异步消费订单消息。

## 版本选择

- `k3s`：你当前节点是 `v1.28.8+k3s1`
- `RocketMQ`：建议固定 `apache/rocketmq:5.3.4`

选择 `5.3.4` 的原因：

- 当前仓库 `galaxy_mall-order` 依赖 `rocketmq-spring-boot-starter:2.3.3`
- `RocketMQ Spring 2.3.2` 已升级到底层 `5.3.1` 客户端线
- `5.3.x` 服务端和当前 Java Remoting 客户端组合更稳妥，避免直接跳到更新分支带来额外验证面

注意：

- 仓库文档里提到过 `RocketMQ 4.8.0`，那是本地旧版联调用法，不是这里推荐的 `k3s` 版本
- 当前应用代码使用的是 `DefaultMQProducer` / `DefaultMQPushConsumer`，走的是 Remoting 客户端模式

## 部署

执行：

```bash
kubectl apply -f deploy/k8s/rocketmq-k3s.yaml
kubectl -n middleware get pods -o wide
kubectl -n middleware get svc
```

正常结果应该至少看到：

- `rocketmq-namesrv`
- `rocketmq-broker-0`

集群内 NameServer 地址固定为：

```text
rocketmq-namesrv.middleware.svc.cluster.local:9876
```

## 把 order 服务切到 RocketMQ

如果你当前 `order` Deployment 就在默认命名空间，直接改环境变量：

```bash
sudo /usr/local/bin/k3s kubectl set env deployment/order \
  GALAXY_ORDER_MESSAGING_BACKEND=rocketmq \
  ROCKETMQ_NAME_SERVER=rocketmq-namesrv.middleware.svc.cluster.local:9876 \
  ROCKETMQ_PRODUCER_GROUP=galaxy-order-producer \
  GALAXY_ORDER_ROCKETMQ_TOPIC=galaxy-order-submitted \
  GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP=galaxy-order-consumer
```

然后检查：

```bash
sudo /usr/local/bin/k3s kubectl rollout status deployment/order
sudo /usr/local/bin/k3s kubectl logs deployment/order | tail -n 50
```

## 30897 端口怎么访问

你当前 `gateway` 的 Service 是：

```text
gateway  NodePort  8080:30897/TCP
```

这表示：

- Pod 内应用监听 `8080`
- k3s 节点对外暴露 `30897`

所以访问格式是：

```text
http://<k3s节点IP>:30897
```

如果你的机器 IP 是 `192.168.31.10`，那么网关基地址就是：

```text
http://192.168.31.10:30897
```

结合当前仓库网关代码，常用接口是：

- `GET /api/gateway/products/{productId}?userId=u100&clientIp=10.0.0.1`
- `POST /api/gateway/orders`
- `GET /edge/products/{productId}`
- `POST /edge/orders`

示例：

```bash
curl "http://192.168.31.10:30897/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
curl "http://192.168.31.10:30897/edge/products/sku-iphone"
```

## 验证顺序

先验证网关：

```bash
curl "http://<k3s节点IP>:30897/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

再验证 RocketMQ：

```bash
sudo /usr/local/bin/k3s kubectl -n middleware logs deploy/rocketmq-namesrv
sudo /usr/local/bin/k3s kubectl -n middleware logs rocketmq-broker-0
```

最后验证下单是否走 MQ：

```bash
curl -X POST "http://<k3s节点IP>:30897/api/gateway/orders" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u100","productId":"sku-iphone","quantity":1,"clientIp":"10.0.0.1"}'
```

如果链路正常：

- `order` 服务会先受理订单
- 后台消费者再异步推进订单状态
- `rocketmq-broker-0` 日志里能看到 topic 路由和消息写入

## 生产化补充

这份清单是单 NameServer + 单 Broker，适合你当前这台 `k3s` 节点先跑通。

如果后面要做更稳的环境，至少改成：

- `2` 个 NameServer
- `1` 主 `1` 从 Broker
- 独立存储卷
- Broker 资源限额按压测结果上调
