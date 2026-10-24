# Galaxy Mall K3s 中间件依赖测试报告

- 测试日期：2026-10-25
- 测试范围：`galaxy-mall` Namespace 部署是否依赖集群中间件，以及依赖地址、部署顺序和源码依赖是否一致
- 测试类型：仓库静态配置与构建依赖验证
- 不在本次范围：对 K3s 集群的真实连通性、账号鉴权、数据库读写、Redis 命令、RocketMQ 消息收发

## 1. 测试结论

**通过（静态验证）。**

`galaxy-mall` 业务 Namespace 不部署 MySQL、Redis、RocketMQ 本体，但会跨 Namespace 使用 `middleware` Namespace 中的服务：

| 依赖 | 结果 | 业务用途 |
| --- | --- | --- |
| MySQL | 通过 | `galaxy-mall-order` 的 JDBC 分片订单存储 |
| Redis | 通过 | `galaxy-mall-stock` 的库存后端 |
| RocketMQ | 通过 | `galaxy-mall-order` 的异步订单消息 |
| Nacos | 不适用 | 当前应用配置、Java 源码和部署渲染清单均未发现 Nacos 客户端地址或配置 |

本报告只确认“部署配置和源码声明一致”。中间件是否在实际集群中运行、DNS 是否可解析、密码是否正确，仍需执行第 4 节的集群联调命令确认。

## 2. 测试项与结果

| ID | 测试项 | 证据 | 结果 |
| --- | --- | --- | --- |
| T01 | `galaxy-mall` 使用跨 Namespace Redis Service | 渲染 ConfigMap 配置 `REDIS_HOST=redis.middleware.svc.cluster.local`、`REDIS_PORT=6379` | 通过 |
| T02 | `galaxy-mall` 使用跨 Namespace RocketMQ Service | 配置 `ROCKETMQ_NAME_SERVER=rocketmq-namesrv.middleware.svc.cluster.local:9876` | 通过 |
| T03 | `galaxy-mall` 使用跨 Namespace MySQL Service | 两个订单分片 URL 都指向 `mysql.middleware.svc.cluster.local:3306` | 通过 |
| T04 | 库存模块具有 Redis 客户端依赖 | `galaxy_mall-stock/pom.xml` 包含 `spring-boot-starter-data-redis` | 通过 |
| T05 | 订单模块具有 RocketMQ 与 MySQL 客户端依赖 | `galaxy_mall-order/pom.xml` 包含 `rocketmq-spring-boot-starter` 与 `mysql-connector-j` | 通过 |
| T06 | 中间件先于业务清单部署 | `60_install_business_dependencies.sh` 等待 MySQL、Redis、RocketMQ NameServer、Broker、Dashboard rollout 完成 | 通过 |
| T07 | 当前业务部署是否需要 Nacos | 对 `application*.yml`、`*.properties`、`*.java` 搜索 `nacos` 无匹配 | 通过，当前不依赖 |
| T08 | 用本地 kubectl 对渲染清单进行 client dry-run | 本机 kubeconfig 的 API discovery 返回 `the server could not find the requested resource` | 阻塞，不计为清单失败 |

## 3. 已核对的配置

测试对象为 [`ci_cd/rendered/galaxy-mall.yaml`](../ci_cd/rendered/galaxy-mall.yaml) 中 `galaxy-mall-config` 的非敏感连接项：

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

渲染逻辑来自 [`ci_cd/scripts/70_render_business_manifest.sh`](../ci_cd/scripts/70_render_business_manifest.sh)：它把 UAT 模板中的中间件地址替换为 `${MIDDLEWARE_NAMESPACE}`。安装顺序由 [`ci_cd/scripts/60_install_business_dependencies.sh`](../ci_cd/scripts/60_install_business_dependencies.sh) 保证，业务清单则由 [`ci_cd/scripts/80_apply_business_stack.sh`](../ci_cd/scripts/80_apply_business_stack.sh) 在之后应用。

## 4. 待执行的集群联调

在可访问目标 K3s API 的控制机执行以下只读检查。除第 4 项外不会输出 Secret 内容：

```bash
# 1. 中间件工作负载和 Service 状态。
kubectl -n middleware get deploy,statefulset,pod,svc

# 2. 中间件 Service 必须有 Ready endpoint。
kubectl -n middleware get endpointslices \
  -l kubernetes.io/service-name=mysql
kubectl -n middleware get endpointslices \
  -l kubernetes.io/service-name=redis
kubectl -n middleware get endpointslices \
  -l kubernetes.io/service-name=rocketmq-namesrv

# 3. 业务 Deployment 与 Pod 应全部 Ready。
kubectl -n galaxy-mall get deploy,pod -o wide

# 4. 仅查看订单和库存容器是否注入连接目标，不输出密码。
kubectl -n galaxy-mall exec deploy/galaxy-mall-stock -- \
  sh -c 'printenv | grep -E "^(REDIS_HOST|REDIS_PORT|GALAXY_STOCK_BACKEND)="'
kubectl -n galaxy-mall exec deploy/galaxy-mall-order -- \
  sh -c 'printenv | grep -E "^(ROCKETMQ_NAME_SERVER|GALAXY_ORDER_MESSAGING_BACKEND|GALAXY_ORDER_STORE_BACKEND)="'
```

集群联调通过标准：

1. `mysql`、`redis`、`rocketmq-namesrv` 都存在 Ready endpoint。
2. `galaxy-mall` 的 `stock` 和 `order` Pod 全部为 `Ready`，且重启次数不增长。
3. Pod 内环境变量的目标地址与第 3 节一致。
4. 创建订单后，订单数据可持久化且 RocketMQ 消费组能消费对应 topic；此项需要受控测试数据，不应在未授权的生产订单上执行。

## 5. 风险与限制

- 不能把 `redis`、`mysql` 改成短 Service 名：业务 Pod 位于 `galaxy-mall`，中间件位于 `middleware`，必须通过 `*.middleware.svc.cluster.local` 跨 Namespace 解析。
- MySQL、Redis、RocketMQ Broker 的可用性是业务关键路径；任一组件不可用会分别影响订单持久化、库存处理或异步订单流转。
- 本次未执行真实 K3s API 校验，因为本地 kubeconfig 的 API discovery 不可用。该限制不影响仓库静态检查结论，但不能替代第 4 节集群联调。
