# Galaxy Mall 实体机（裸金属/虚机直装）部署 SOP

适用场景：不使用 k3s/Kubernetes，把 galaxy_mall 直接部署到实体操作系统（本文以 **Rocky Linux 9 / CentOS Stream 9** 为基线，Ubuntu 24.04 命令差异随文标注）。

参照物：k3s 集群现网部署（`ci_cd/rendered/galaxy-mall.yaml`，镜像 `git-5534813`），本文把其中的 ConfigMap/Secret/探针/Service 翻译成裸机等价物。与 k3s 的差异对照见第 8 节。

---

## 1. 进程与端口清单

单机演示可全部混部；生产/压测建议至少按"接入层 / 业务层 / 中间件层"三组拆机，同组内多台。

### 1.1 业务进程（systemd 管理，Java 17）

| 服务 | jar（模块） | 端口 | 依赖 | 备注 |
| --- | --- | ---: | --- | --- |
| gateway | galaxy_mall-gateway | 8080 | user/product/stock/order/payment | 对外唯一入口，含限流/等待室/埋点 |
| user | galaxy_mall-user | 8081 | 无（用户数据进程内存） | 播种账号 u100/u101/u200/u300 |
| product | galaxy_mall-product | 8082 | 无 | |
| stock | galaxy_mall-stock | 8083 | Redis | 库存走 Redis Lua；⚠️ "DB 账本"是进程内存，见第 7 节 |
| order | galaxy_mall-order | 8084 | product/stock、RocketMQ、MySQL×2 | 异步下单：RocketMQ + 分库 |
| payment | galaxy_mall-payment | 8085 | order/stock | |
| front | 静态文件（`front/dist`） | 80 (nginx) | gateway:8080 | `/api/`、`/edge/`、`/actuator/` 反代到 gateway |

### 1.2 中间件

| 组件 | 端口 | 用途 | 最小版本建议 |
| --- | ---: | --- | --- |
| MySQL 8 | 3306 | 库 `galaxy_order_0`、`galaxy_order_1`（每库 4 张 `t_order_0..3`，应用启动自动建表） | 8.0 |
| Redis | 6379 | 库存（`galaxy:stock:*`），**必须设 requirepass** | 7.x |
| RocketMQ NameServer | 9876 | 订单异步消息 | 5.x / 4.9.x |
| RocketMQ Broker | 10911 | 同上 | 与 NameServer 同版本 |
| Nacos | — | **不需要**。k3s Deployment 里虽然注入了 `NACOS_*` 环境变量，但源码 `application*.yml` 无 Nacos 配置，属历史残留 | — |

---

## 2. 第 0 步：操作系统与依赖准备

每台实体机执行：

```bash
# Java 17（业务唯一运行时要求；构建机另需 Maven 3.9+ 与 Node 20+）
dnf install -y java-17-openjdk java-17-openjdk-devel   # Ubuntu: apt install -y openjdk-17-jdk
java -version   # 必须 17.x

# 内核与资源
ulimit -n 65535          # 写入 /etc/security/limits.conf 持久化
sysctl -w net.core.somaxconn=4096
sysctl -w vm.overcommit_memory=1   # Redis 要求

# 防火墙：只放行业务端口；3306/6379/9876/10911 仅限内网网段
firewall-cmd --permanent --add-port=8080-8085/tcp --add-port=80/tcp
firewall-cmd --reload
```

SELinux 建议保持 enforcing 并用 `semanage port` 放行非标端口，或演示环境临时 setenforce 0（不要长期关闭）。

## 3. 第 1 步：构建

在构建机（或 CI）执行，产物是各模块 target 下的可执行 jar：

```bash
mvn -q -DskipTests package
# 产物：galaxy_mall-<svc>/target/galaxy_mall-<svc>-1.0-SNAPSHOT.jar
# front 产物：front/dist（npm run build，见 front/build-front.sh）
```

发布物建议打 tar 包上传：`galaxy-mall-<git-sha>.tar.gz`（jar ×6 + dist/ + env 模板），tag 用 git 短 sha，与 k3s 镜像 tag `git-<sha>` 对齐，便于回退。

## 4. 第 2 步：中间件部署

### 4.1 MySQL

```bash
# 建库建号（密码替换为真实值；与 env 文件保持一致）
mysql -uroot -p <<'SQL'
create database galaxy_order_0 default charset utf8mb4;
create database galaxy_order_1 default charset utf8mb4;
create user 'galaxy'@'192.168.31.%' identified by '<ORDER_DB_PASSWORD>';
grant all on galaxy_order_0.* to 'galaxy'@'192.168.31.%';
grant all on galaxy_order_1.* to 'galaxy'@'192.168.31.%';
flush privileges;
SQL
```

`t_order_0..3` 由 `JdbcShardedOrderStore` 启动时 `create table if not exists` 自动创建，无需手工导 SQL（仓库中没有 .sql 文件）。

### 4.2 Redis

```bash
# redis.conf 关键项
requirepass <REDIS_PASSWORD>
appendonly yes
# 启动后预热库存键（等价 k3s 里 stock 启动时的 preload）
redis-cli -a <REDIS_PASSWORD> set galaxy:stock:sku-iphone 10
# 其余 sku 同理：sku-mouse 1, sku-seckill 2, sku-laptop/monitor/earphone/watch/fridge/jacket/coffee 300
```

> 库存键值来源 = `StockServiceApplication` 的播种值。只预热 Redis 不够：confirm 走的是每个 stock **进程内存**账本（每副本 sku-iphone 只有 10），所以 stock 实例数 × 10 才是可成功 confirm 的订单上限。

### 4.3 RocketMQ

```bash
# NameServer
nohup sh bin/mqnamesrv >/dev/null 2>&1 &        # 建议也交给 systemd

# Broker（conf/broker.conf 关键项）
brokerClusterName=DefaultCluster
brokerName=broker-a
brokerId=0
namesrvAddr=<NS_IP>:9876
autoCreateTopicEnable=true       # 演示可用；生产建议手工建 topic 后关掉
nohup sh bin/mqbroker -c conf/broker.conf >/dev/null 2>&1 &

# 建 topic（consumer group 首次消费自动注册，topic 建议显式建）
sh bin/mqadmin updateTopic -n <NS_IP>:9876 -c DefaultCluster -t galaxy-order-submitted
```

## 5. 第 3 步：业务进程部署（systemd）

### 5.1 目录规划

```text
/opt/galaxy-mall/
├── jar/                     # galaxy-mall-gateway.jar 等 6 个 jar
├── etc/                     # 每服务一个 env 文件（等价 ConfigMap+Secret）
│   ├── galaxy-mall-gateway.env
│   └── ...
└── front/dist/              # nginx 根目录
```

### 5.2 环境变量文件（等价 k3s ConfigMap + Secret）

以 `gateway.env` 为例（业务服务把 `SERVICES_*_URL` 指向实际 IP；中间件三件套只有 stock/order 用到）：

```bash
# /opt/galaxy-mall/etc/galaxy-mall-gateway.env
SPRING_PROFILES_ACTIVE=prod
SERVICES_USER_URL=http://<USER_HOST>:8081
SERVICES_PRODUCT_URL=http://<PRODUCT_HOST>:8082
SERVICES_STOCK_URL=http://<STOCK_HOST>:8083
SERVICES_ORDER_URL=http://<ORDER_HOST>:8084
SERVICES_PAYMENT_URL=http://<PAYMENT_HOST>:8085
# 限流/等待室如需自定义再暴露，prod profile 默认值见 galaxy_mall-gateway/application-prod.yml
```

```bash
# /opt/galaxy-mall/etc/galaxy-mall-order.env
SPRING_PROFILES_ACTIVE=prod
SERVICES_PRODUCT_URL=http://<PRODUCT_HOST>:8082
SERVICES_STOCK_URL=http://<STOCK_HOST>:8083
ROCKETMQ_NAME_SERVER=<NS_IP>:9876
GALAXY_ORDER_MESSAGING_BACKEND=rocketmq
GALAXY_ORDER_STORE_BACKEND=jdbc-sharded
GALAXY_ORDER_ROCKETMQ_TOPIC=galaxy-order-submitted
GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP=galaxy-order-consumer
ROCKETMQ_PRODUCER_GROUP=galaxy-order-producer
ORDER_SHARD_0_URL=jdbc:mysql://<MYSQL_HOST>:3306/galaxy_order_0?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
ORDER_SHARD_1_URL=jdbc:mysql://<MYSQL_HOST>:3306/galaxy_order_1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
ORDER_SHARD_0_USERNAME=galaxy
ORDER_SHARD_1_USERNAME=galaxy
ORDER_SHARD_0_PASSWORD=<ORDER_DB_PASSWORD>
ORDER_SHARD_1_PASSWORD=<ORDER_DB_PASSWORD>
```

```bash
# /opt/galaxy-mall/etc/galaxy-mall-stock.env
SPRING_PROFILES_ACTIVE=prod
REDIS_HOST=<REDIS_HOST>
REDIS_PORT=6379
REDIS_PASSWORD=<REDIS_PASSWORD>
GALAXY_STOCK_BACKEND=redis
GALAXY_STOCK_LOCAL_RATE_LIMIT=50000
```

user/product/payment 三个服务无外部依赖，env 只需 `SPRING_PROFILES_ACTIVE=prod`（payment 若与 order/stock 不同机，再补 `SERVICES_ORDER_URL` / `SERVICES_STOCK_URL`）。

权限收敛：`chown -R galaxy:galaxy /opt/galaxy-mall/etc && chmod 600 etc/*.env`（Secret 明文等价物，等同 k3s Secret 的保密要求）。

### 5.3 systemd unit（模板，每服务一份）

```ini
# /etc/systemd/system/galaxy-mall-gateway.service
[Unit]
Description=galaxy mall gateway
After=network-online.target
Wants=network-online.target

[Service]
User=galaxy
Group=galaxy
EnvironmentFile=/opt/galaxy-mall/etc/galaxy-mall-gateway.env
ExecStart=/usr/bin/java -XX:+UseG1GC -Xms512m -Xmx1g -jar /opt/galaxy-mall/jar/galaxy-mall-gateway.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=40
KillSignal=SIGINT

[Install]
WantedBy=multi-user.target
```

要点（与 k3s 行为对齐）：
- `KillSignal=SIGINT` + `SuccessExitStatus=143`：应用 `server.shutdown=graceful`，SIGTERM/SIGINT 触发 30s 优雅停机，等价 k8s 的 terminationGracePeriod。
- `Restart=on-failure` + `RestartSec=5`：等价 k8s 的 `restartPolicy`，不要用 `always` 掩盖配置错误（对齐 readiness runbook 的原则）。
- 每个服务 `systemctl enable --now`，启动顺序：中间件 → user/product/stock → order/payment → gateway → nginx front。

## 6. 第 4 步：front 静态站 + nginx 反代

nginx 配置直接复用 `r630-proxmox8/cicd/infra/front-nginx-galaxy-mall.conf`，仅把两处 `proxy_pass http://galaxy-mall-gateway:8080` 改为裸机可达地址（本机部署即 `http://127.0.0.1:8080`）：

```bash
dnf install -y nginx
mkdir -p /usr/share/nginx/html
cp -r /opt/galaxy-mall/front/dist/* /usr/share/nginx/html/
cp front-nginx-galaxy-mall.conf /etc/nginx/conf.d/galaxy-mall.conf   # 改 proxy_pass 后
nginx -t && systemctl enable --now nginx
```

`try_files ... /index.html` 保证前端路由直出；`/api/collect` 埋点、`/actuator/` 健康经同一反代。

## 7. 第 5 步：验收（等价 k3s 冒烟）

```bash
# 1) 每实例健康（业务失败会包装成 HTTP 200，必须看响应体，不能只看状态码）
for p in 8080 8081 8082 8083 8084 8085; do
  curl -fsS http://127.0.0.1:$p/actuator/health/readiness; echo
done
curl -fsS http://127.0.0.1:8080/actuator/prometheus | head

# 2) 全链路冒烟：仓库脚本，BASE_URL 指向 gateway
BASE_URL=http://127.0.0.1:8080 CLIENT_IP_PREFIX=192.168.31 bash scripts/uat_smoke_test.sh
```

冒烟脚本会做：健康 → 指标 → 商品查询 → 异步下单推进 WAITING_PAYMENT → 支付 SUCCESS → 32 并发触发等待室 QUEUED + queueToken → 重放放行。判绿标准与 `docs/uat-smoke-test-report-2026-10-25.md` 一致。

注意脚本两点（本次 UAT 冒烟实测得出的前提）：
- 排队用户默认 `u101`（播种账号），可 `QUEUE_USER=` 覆盖；不存在的用户会在网关 `user-not-found` 直接被拒。
- 并发突发每轮会产生 ~8-16 条真实 SUBMITTED 订单，属演示数据；反复冒烟需保证 MySQL 分库可写、Redis 库存键存在、stock 内存账本未耗尽（见第 4.2 节警告与第 7 节遗留问题）。

## 8. 与 k3s 部署的差异对照

| k3s 上的等价物 | 裸机方案 |
| --- | --- |
| Deployment 副本数 / 滚动升级 | systemd `Restart=on-failure`；升级 = 备份旧 jar → 替换 → `systemctl restart`（多实例需 nginx upstream 摘流/挂流，参考 `zero-downtime.md` 的摘流思想） |
| readiness/liveness 探针 | 无自动探活；用 systemd 拉起 + 监控侧定时 `curl /actuator/health/readiness`（或 Prometheus blackbox），失败告警 |
| ConfigMap | `/opt/galaxy-mall/etc/*.env` |
| Secret | 同上 env 文件，`chmod 600` + 属主收敛（无可选加密则靠主机权限边界） |
| Service DNS（`*.svc.cluster.local`） | env 里写真实 `IP:PORT` |
| Ingress（Traefik，host 路由） | nginx 反代 + `server_name` |
| NodePort 30080 | nginx 直接监听 80 |
| Pod 内存 limit / requests | `-Xmx` + systemd `MemoryMax=`（防 OOM 拖垮整机） |
| 镜像 tag `git-<sha>` | 发布 tar 包按 git 短 sha 命名，回滚 = 换回旧包重启 |

## 9. 已知问题与建议（裸机同样存在）

1. stock 的库存"DB 账本"是进程内存（每副本 sku-iphone 播种 10）：多实例/重启即账本回滚，与 Redis 事实不一致，confirm 可能 `db-confirm-failed` → 订单 FAILED。生产化前应把账本落 MySQL。
2. 等待室票据、用户/库存账本均在进程内存：**裸机多实例部署时同一服务拆多进程会出现数据分裂**。除无状态读路径（product）外，user/stock/等待室相关组件单实例运行，或先完成共享存储改造。
3. RocketMQ 消费失败原因只进内存 TraceLogger，排障不可见，建议接回 logback stdout 并按第 5.3 节由 journald 收集（`journalctl -u galaxy-mall-order`）。
