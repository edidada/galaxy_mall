# K3s：`galaxy-mall` Namespace 的中间件依赖

## 结论

**有。** `galaxy-mall` Namespace 部署的是商城业务服务，不在该 Namespace 内自行部署中间件；业务运行时通过 Kubernetes Service 跨 Namespace 访问 `middleware` 中的 **MySQL、Redis 和 RocketMQ**。

当前渲染后的业务清单为 [`ci_cd/rendered/galaxy-mall.yaml`](../ci_cd/rendered/galaxy-mall.yaml)，其中的目标 Namespace 是 `galaxy-mall`，中间件目标全部是 `middleware.svc.cluster.local` 下的 Service。

| 中间件 | 是否业务必需 | 使用服务 | 当前 Service 地址 | 用途 |
| --- | --- | --- | --- | --- |
| MySQL | 是 | `galaxy-mall-order` | `mysql.middleware.svc.cluster.local:3306` | 订单分片持久化，库为 `galaxy_order_0`、`galaxy_order_1` |
| Redis | 是 | `galaxy-mall-stock` | `redis.middleware.svc.cluster.local:6379` | 库存后端，`GALAXY_STOCK_BACKEND=redis` |
| RocketMQ NameServer / Broker | 是 | `galaxy-mall-order` | `rocketmq-namesrv.middleware.svc.cluster.local:9876` | 订单提交消息的生产和消费 |
| Nacos | 否，按当前清单 | 无 | 无 | 当前源码、渲染清单和安装脚本均未向业务服务注入 Nacos 地址 |

`gateway`、`user`、`product`、`payment` 的当前部署清单没有直接配置上述中间件连接；它们通过集群内 Service 调用业务服务。不能据此推断它们的功能与中间件无关，例如支付会调用订单服务，最终仍会间接依赖订单存储与消息链路。

## 证据链

### 1. 业务服务的运行时配置

[`ci_cd/rendered/galaxy-mall.yaml`](../ci_cd/rendered/galaxy-mall.yaml) 中的 `galaxy-mall-config` 注入了：

```yaml
REDIS_HOST: redis.middleware.svc.cluster.local
REDIS_PORT: "6379"
ROCKETMQ_NAME_SERVER: rocketmq-namesrv.middleware.svc.cluster.local:9876
GALAXY_STOCK_BACKEND: redis
GALAXY_ORDER_MESSAGING_BACKEND: rocketmq
GALAXY_ORDER_STORE_BACKEND: jdbc-sharded
ORDER_SHARD_0_URL: jdbc:mysql://mysql.middleware.svc.cluster.local:3306/galaxy_order_0?...
ORDER_SHARD_1_URL: jdbc:mysql://mysql.middleware.svc.cluster.local:3306/galaxy_order_1?...
```

该文件由 [`ci_cd/scripts/70_render_business_manifest.sh`](../ci_cd/scripts/70_render_business_manifest.sh) 生成。脚本把 UAT 模板中的 Redis、RocketMQ、MySQL 地址替换为 `${MIDDLEWARE_NAMESPACE}`；当前渲染产物的值为 `middleware`。

### 2. 应用源码的直接依赖

| 业务模块 | 代码/依赖证据 | 结论 |
| --- | --- | --- |
| `galaxy_mall-stock` | `spring-boot-starter-data-redis`；配置读取 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` | Redis 是库存服务的直接依赖 |
| `galaxy_mall-order` | `rocketmq-spring-boot-starter` 和 `mysql-connector-j`；生产 profile 默认 `messaging-backend=rocketmq`、`store-backend=jdbc-sharded` | RocketMQ 和 MySQL 是订单服务的直接依赖 |
| 其它商城模块 | 当前资源配置未发现 Nacos 地址或客户端配置 | 不应把 Nacos 当作当前部署的必需前置条件 |

### 3. 中间件的安装顺序

[`ci_cd/scripts/60_install_business_dependencies.sh`](../ci_cd/scripts/60_install_business_dependencies.sh) 会先在 `${MIDDLEWARE_NAMESPACE}` 部署并等待：

1. `deployment/mysql`
2. `deployment/redis`
3. `deployment/rocketmq-namesrv`
4. `statefulset/rocketmq-broker`
5. `deployment/rocketmq-dashboard`，仅用于管理观察，不是业务调用目标

业务清单随后才由 [`ci_cd/scripts/80_apply_business_stack.sh`](../ci_cd/scripts/80_apply_business_stack.sh) 应用。因此正确顺序是：**中间件 Ready -> `galaxy-mall` 业务 Deployment -> 业务验收**。

## Kubernetes 访问关系

```text
galaxy-mall namespace
  galaxy-mall-stock  ------> redis.middleware.svc.cluster.local:6379
  galaxy-mall-order  ------> mysql.middleware.svc.cluster.local:3306
                    └-----> rocketmq-namesrv.middleware.svc.cluster.local:9876

  gateway/user/product/payment
                    └-----> galaxy-mall 内的业务 Service
```

跨 Namespace 时，`redis`、`mysql` 这类短 Service 名只会在调用方同 Namespace 内解析。`galaxy-mall` 业务配置必须保留完整域名 `服务名.middleware.svc.cluster.local`，不能改成仅 `redis` 或 `mysql`，除非把中间件迁入 `galaxy-mall` Namespace。

## 上线前核验

以下命令只读检查实际集群状态，不输出 Secret 内容：

```bash
# 1. 中间件工作负载与 Service 均应存在且 Ready。
kubectl -n middleware get deploy,statefulset,pod,svc

# 2. 确认中间件 Service 有 Ready endpoint。
kubectl -n middleware get endpointslices \
  -l kubernetes.io/service-name=mysql
kubectl -n middleware get endpointslices \
  -l kubernetes.io/service-name=redis
kubectl -n middleware get endpointslices \
  -l kubernetes.io/service-name=rocketmq-namesrv

# 3. 只读取业务 ConfigMap 中的非敏感连接目标。
kubectl -n galaxy-mall get configmap galaxy-mall-config -o yaml

# 4. 确认订单和库存 Pod 已拿到对应变量；不要执行 printenv 全量输出。
kubectl -n galaxy-mall exec deploy/galaxy-mall-stock -- \
  sh -c 'printenv | grep -E "^(REDIS_HOST|REDIS_PORT|GALAXY_STOCK_BACKEND)="'
kubectl -n galaxy-mall exec deploy/galaxy-mall-order -- \
  sh -c 'printenv | grep -E "^(ROCKETMQ_NAME_SERVER|GALAXY_ORDER_MESSAGING_BACKEND|GALAXY_ORDER_STORE_BACKEND)="'
```

若任一中间件未 Ready，优先检查 `middleware` Namespace；不要通过删除 `galaxy-mall` Pod 来掩盖依赖不可用。秒杀业务的额外部署、消息和监控验收见 [k3s-seckill-message-monitor-deploy-plan.md](k3s-seckill-message-monitor-deploy-plan.md)。

## 注意事项

- `ci_cd/rendered/` 是部署渲染产物，包含环境值；它可以用于核对地址，但不要在文档、终端记录或提交说明中复制密码。
- 中间件目前由单副本工作负载和 local-path 存储构成的部分较多。MySQL、Redis、RocketMQ Broker 不可用会分别影响订单持久化、库存处理和异步订单处理。
- 代码仓库中可能仍保留旧模板、单机压测脚本或 `*-prod.yaml` 示例；判断当前 K3s `galaxy-mall` 部署依赖时，应优先以 `ci_cd/rendered/galaxy-mall.yaml` 与 `60_install_business_dependencies.sh` 为准。
