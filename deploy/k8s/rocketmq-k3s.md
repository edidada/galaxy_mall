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

## 怎么访问网关（2026-10-17 重写）

> ⚠️ 本节原文基于「`gateway` 是 NodePort `8080:30897`」，该 Service **已不存在**（实测 `30897` 端口关闭）。
> 现在 `galaxy-mall-gateway` 是 **ClusterIP:8080**，由 **Traefik Ingress** 对外暴露。
> 完整入口说明见 `docs/k3s-access-from-mac.md`。

当前集群（4 节点，Ingress 已覆盖全部节点）：

| 节点 | IP |
|---|---|
| k3s-master | `192.168.31.77` |
| k3s-worker1 | `192.168.31.211` |
| k3s-worker2 | `192.168.31.251` |
| k3s-worker3 | `192.168.31.88` |

三种访问方式：

**① Ingress 域名（首选）** —— 先写 hosts：

```bash
echo "192.168.31.77 mall.192.168.31.77.nip.io" | sudo tee -a /etc/hosts
curl "http://mall.192.168.31.77.nip.io/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

**② IP + Host 头（不依赖 DNS）** —— 压测推荐，4 个节点 IP 任选：

```bash
curl -H "Host: mall.192.168.31.77.nip.io" \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

**③ front NodePort（最省事）** —— 同源转发 `/api/gateway/**`，无需 Host 头：

```bash
curl "http://192.168.31.77:30080/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

常用接口：

- `GET /api/gateway/products/{productId}?userId=u100&clientIp=10.0.0.1`
- `POST /api/gateway/orders`
- `GET /api/gateway/orders/{orderId}`
- `POST /api/gateway/payments/{orderId}`
- `GET /edge/products/{productId}`
- `POST /edge/orders`

示例：

```bash
curl -H "Host: mall.192.168.31.77.nip.io" \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
curl -H "Host: mall.192.168.31.77.nip.io" \
  "http://192.168.31.77/edge/products/sku-iphone"
```

## 验证顺序

先验证网关：

```bash
curl -H "Host: mall.192.168.31.77.nip.io" \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

再验证 RocketMQ：

```bash
sudo /usr/local/bin/k3s kubectl -n middleware logs deploy/rocketmq-namesrv
sudo /usr/local/bin/k3s kubectl -n middleware logs rocketmq-broker-0
```

最后验证下单是否走 MQ：

```bash
curl -X POST "http://192.168.31.77/api/gateway/orders" \
  -H "Host: mall.192.168.31.77.nip.io" \
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
