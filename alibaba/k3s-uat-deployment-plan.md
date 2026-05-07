# Galaxy Mall Spring Cloud Alibaba + Dubbo K3s UAT 部署方案

## 目标

本方案面向 `spring_cloud_alibaba` 分支，部署一套适合 UAT 的 `Spring Cloud Alibaba + Dubbo` 组合：

- 服务注册与发现：Nacos
- 网关与 HTTP 北向入口：Spring Cloud Gateway
- 服务间 RPC 调用：Dubbo `triple`
- 熔断限流：Sentinel
- 异步消息：RocketMQ
- 分布式事务：Seata
- 监控：Prometheus + Grafana

当前代码侧的 Dubbo 改造范围是：

- `galaxy-mall-user/product/stock/order/payment` 均暴露 Dubbo service
- `galaxy-mall-gateway/order/payment/seckill` 均通过 Dubbo reference 调用下游
- 对外 HTTP 入口保留，服务内编排不再依赖 Feign

## UAT 拓扑

建议在 k3s 中拆成 3 个命名空间：

- `infra`: Nacos, Sentinel Dashboard, Seata, RocketMQ
- `middleware`: Redis, MySQL
- `galaxy-uat`: 业务服务

业务服务建议副本：

- `galaxy-mall-gateway`: 2
- `galaxy-mall-user`: 1
- `galaxy-mall-product`: 1
- `galaxy-mall-stock`: 1
- `galaxy-mall-order`: 1
- `galaxy-mall-payment`: 1
- `galaxy-mall-seckill`: 1

## 部署顺序

1. 部署 `Nacos`
2. 部署 `MySQL / Redis / RocketMQ / Seata`
3. 在 Nacos 创建 `uat` 命名空间
4. 部署 `Sentinel Dashboard`
5. 推送业务镜像
6. 部署 `product`，确认 Dubbo 服务成功注册
7. 部署 `user / stock / order / payment`
8. 部署 `gateway / seckill`
9. 网关与监控联调

## Nacos 规划

建议：

- Namespace: `uat`
- Group:
  - `GALAXY_HTTP`
  - `GALAXY_DUBBO`
  - `GALAXY_SHARED`

建议 Data ID：

- `galaxy-mall-gateway-uat.yaml`
- `galaxy-mall-user-uat.yaml`
- `galaxy-mall-product-uat.yaml`
- `galaxy-mall-stock-uat.yaml`
- `galaxy-mall-order-uat.yaml`
- `galaxy-mall-payment-uat.yaml`
- `galaxy-mall-seckill-uat.yaml`
- `galaxy-shared-uat.yaml`

## 关键环境变量

所有业务服务共用：

```yaml
env:
  - name: NACOS_DISCOVERY_ENABLED
    value: "true"
  - name: NACOS_DISCOVERY_REGISTER_ENABLED
    value: "true"
  - name: NACOS_SERVER_ADDR
    value: "nacos.infra.svc.cluster.local:8848"
  - name: NACOS_NAMESPACE
    value: "uat"
  - name: NACOS_GROUP
    value: "GALAXY_HTTP"
  - name: SENTINEL_ENABLED
    value: "true"
  - name: SENTINEL_DASHBOARD
    value: "sentinel-dashboard.infra.svc.cluster.local:8080"
```

`product` Dubbo provider 额外需要：

```yaml
env:
  - name: DUBBO_REGISTRY_ADDRESS
    value: "nacos://nacos.infra.svc.cluster.local:8848"
  - name: DUBBO_GROUP
    value: "galaxy-mall"
  - name: DUBBO_PORT
    value: "20882"
```

`order` 启动时需要：

```yaml
env:
  - name: DUBBO_REGISTRY_ADDRESS
    value: "nacos://nacos.infra.svc.cluster.local:8848"
  - name: DUBBO_GROUP
    value: "galaxy-mall"
```

## Product / Order 部署要点

### product

- Spring HTTP 端口仍走 `8082`
- Dubbo Triple 端口独立走 `20882`
- Kubernetes `Service` 需同时暴露：
  - `8082` for HTTP
  - `20882` for Dubbo triple

建议 `Service` 片段：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: galaxy-mall-product
  namespace: galaxy-uat
spec:
  selector:
    app: galaxy-mall-product
  ports:
    - name: http
      port: 8082
      targetPort: 8082
    - name: dubbo-tri
      port: 20882
      targetPort: 20882
```

### order

- 先确认 `product`、`stock` 的 Dubbo provider 已经注册成功
- 再启动 `order`，确认商品查询、库存预扣都走 Dubbo
- 首轮上线建议只放 1 个 `order` Pod 观察调用日志和 RT

## k3s YAML 组织建议

建议后续在 `alibaba/k3s-uat/` 下按下面方式落地：

- `00-namespace.yaml`
- `10-configmap-shared.yaml`
- `11-secret-shared.yaml`
- `20-gateway.yaml`
- `21-user.yaml`
- `22-product.yaml`
- `23-stock.yaml`
- `24-order.yaml`
- `25-payment.yaml`
- `26-seckill.yaml`
- `30-ingress.yaml`

## UAT 验收清单

- Nacos 中同时能看到 HTTP 应用注册信息
- `user / product / stock / order / payment` 在 Dubbo 元数据里可见对应 service
- 下单、支付、秒杀主流程经 Dubbo 编排仍成功
- Gateway `/edge/products/**` 与 `/api/gateway/orders` 正常
- Sentinel Dashboard 能看到应用心跳
- Prometheus 能抓到各服务 `/actuator/prometheus`
- Seata、RocketMQ 不因 Dubbo 改造出现回归

## 灰度建议

1. 先部署 `user / product / stock / order / payment`，确认 Dubbo provider 全部注册
2. 再放量 `gateway / seckill`
3. 观察下单 RT、异常率、Nacos 注册、Dubbo 调用日志
4. 稳定后再扩副本

## 当前边界

这份方案先把：

- 代码改造入口
- 配置开关
- UAT 部署顺序
- Dubbo 暴露端口与灰度路径

全部固定下来。

真正的 k3s YAML 细化和集群内联调，等你后面有空进 UAT 再展开。
