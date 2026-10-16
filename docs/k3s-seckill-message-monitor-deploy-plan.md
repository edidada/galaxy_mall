# k3s 部署计划：seckill / message / monitor

> 编写日期：2026-10-17
> 适用集群：Proxmox `192.168.31.171` 上的 4 台 VM 组成的 k3s 集群（v1.35.4+k3s1）
> 原则：**只写计划，不实操**。文中所有"现状"均为已采集的实机/源码事实；标注 `[待确认]` 的项需在执行时现验。

---

## 0. 现状快照

### 0.1 集群

| 项 | 值 |
|---|---|
| Proxmox 宿主 | `192.168.31.171`（只做虚拟化，不跑 k3s 端口） |
| k3s-master | VM100 `192.168.31.77`，control-plane，4C / 8.9G |
| k3s-worker1 | VM101 `192.168.31.211`，4C / 7.9G |
| k3s-worker2 | VM103 `192.168.31.251`，4C / 10G（新加入） |
| k3s-worker3 | VM104 `192.168.31.88`，4C / 10G（新加入） |
| k3s 版本 | `v1.35.4+k3s1`，containerd `2.2.3-k3s1`，OS CentOS Stream 9 |
| StorageClass | `local-path`（默认，WaitForFirstConsumer） |
| Ingress | Traefik，LoadBalancer 外部 IP **4 个节点全生效**（`192.168.31.77/211/251/88`，2026-10-17 已修复，见 0.4） |

### 0.2 已部署工作负载

- `galaxy-mall` 命名空间：`front`(NodePort 30080)、`galaxy-mall-gateway`(8080, 2 副本)、`user`(8081)、`product`(8082)、`stock`(8083, 2 副本)、`order`(8084, 2 副本)、`payment`(8085)
- `middleware` 命名空间：`mysql`、`nacos`、`redis`、`rocketmq-namesrv`、`rocketmq-broker`(StatefulSet)、`rocketmq-dashboard`(NodePort 30808)
- ConfigMap `galaxy-mall-config` 已含：`SERVICES_USER_URL` / `SERVICES_PRODUCT_URL` / `SERVICES_STOCK_URL` / `SERVICES_ORDER_URL` / `SERVICES_PAYMENT_URL`、`REDIS_HOST`、`ROCKETMQ_NAME_SERVER`、`ORDER_SHARD_*`、`SPRING_PROFILES_ACTIVE=prod`
- **未部署**：`seckill`、`message`、`monitor`、Prometheus/Grafana、GitLab/Jenkins

### 0.3 三个模块的定性结论（关键）

| 模块 | 是不是可独立部署的服务 | 依据 | 结论 |
|---|---|---|---|
| `galaxy_mall-seckill` | ✅ 是 | 有 `SeckillServiceApplication` 启动类、`SeckillController`、`application.yml` 声明 `server.port: 8086` | **新增部署**，端口 8086 |
| `galaxy_mall-message` | ❌ 不是 | 只有 `InMemoryMessageBus` / `MallMessage` / `MessageConsumer`，无 `main`、无 web 依赖、无 Dockerfile 构建项 | **不部署**，作为库被依赖；消息能力落到已部署的 RocketMQ |
| `galaxy_mall-monitor` | ❌ 不是 | 只有 `MetricsCollector`（内存计数器）/ `TraceLogger`，无启动类 | **不部署**，可观测性落到 actuator + Prometheus/Grafana |

> 一句话：**真正要新增的是一个 seckill 服务（8086）；message 与 monitor 是"能力"而非"服务"，分别落到 RocketMQ 与 Prometheus 体系。**

### 0.4 ✅ 已完成（2026-10-17）：Traefik LoadBalancer 已覆盖全部 4 个节点

> **执行结果**：`svc traefik` 的 EXTERNAL-IP 已从 2 个变为 **4 个**（`.77/.211/.251/.88`），四节点 `:80` 验收全部 200。
> 下面是当时的问题分析与实际执行记录（含两个文档写错、实际踩到的坑），保留作为复盘与扩容时的参考。

**现象**：`kubectl -n kube-system get svc traefik` 的 EXTERNAL-IP 只有 `192.168.31.77, 192.168.31.211`。

**根因**：Traefik 的 Service 是 `LoadBalancer` 类型，k3s 通过 **klipper-lb（`svclb-traefik` DaemonSet）** 在每个节点起一个 Pod，把节点的 `:80/:443` 转进 Traefik。新加入的两个 worker（worker2 `.251`、worker3 `.88`）上的 `svclb-traefik` Pod 处于 `ImagePullBackOff` —— klipper-lb 镜像在 docker.io，而这两台节点**拉不到 Docker Hub**。

**影响面**：

- 走 Ingress（域名访问）的入口目前只有 2 个 IP（`.77`、`.211`）；
- **NodePort 完全不受影响**，4 个节点全通 —— NodePort 由 kube-proxy 在每节点开端口，不依赖 svclb Pod（已实测 30080 / 30808 四节点均通）。

**目标状态**：4 个节点 IP 都能作为 Ingress 入口。

**修复步骤**（前置：先按《`docs/cicd-gitlab-jenkins-landing.md`》4.1 给 4 台节点配好 `registries.yaml` 镜像加速，并重启 `k3s` / `k3s-agent`）：

```bash
# 1) 确认现状：看 Pod 分布与卡住的镜像
kubectl -n kube-system get pod -l app=svclb-traefik -o wide
kubectl -n kube-system get pod -l app=svclb-traefik \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.nodeName}{"\t"}{.status.containerStatuses[0].image}{"\n"}{end}'

# 2) 重建：DaemonSet 会立刻按新的镜像拉取配置重建
kubectl -n kube-system delete pod -l app=svclb-traefik

# 3) 等就绪
kubectl -n kube-system wait --for=condition=Ready pod -l app=svclb-traefik --timeout=10m

# 4) 验证
kubectl -n kube-system get pod -l app=svclb-traefik -o wide
kubectl -n kube-system get svc traefik
```

期望输出（EXTERNAL-IP 变成 4 个）：

```text
NAME      TYPE           CLUSTER-IP     EXTERNAL-IP                                                 PORT(S)
traefik   LoadBalancer   10.43.x.x      192.168.31.77,192.168.31.88,192.168.31.211,192.168.31.251   80:31667/TCP,443:30261/TCP
```

四节点连通性验收（Mac 或任一节点上执行）：

```bash
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  printf "%s -> " "$ip"
  curl -s -o /dev/null -w "%{http_code}\n" -m 5 \
    -H "Host: mall.192.168.31.77.nip.io" \
    "http://$ip/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
done
# 期望：4 行全是 200（修复前 .251/.88 是 000）
```

**兜底：加速器失效时离线导入 klipper-lb**

```bash
# 取实际镜像名与 tag（从已 Running 的 svclb Pod 读，避免猜错版本）
kubectl -n kube-system get pod -l app=svclb-traefik \
  -o jsonpath='{.items[0].status.containerStatuses[0].image}'
# 例：rancher/klipper-lb:v0.4.13

docker pull rancher/klipper-lb:<上一步得到的 tag>
docker save -o /tmp/klipper-lb.tar rancher/klipper-lb:<tag>

for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  scp /tmp/klipper-lb.tar wdidada@$ip:/tmp/
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S /usr/local/bin/k3s ctr images import /tmp/klipper-lb.tar"
done

kubectl -n kube-system delete pod -l app=svclb-traefik
```

### 0.5 实际执行记录（2026-10-17）

**Step 1 — 定位镜像**：`docker.io/rancher/klipper-lb:v0.4.15`（2 容器：`lb-tcp-80`、`lb-tcp-443`）。失败事件原文：

```text
Failed to pull image "rancher/klipper-lb:v0.4.15": failed to resolve reference ...
Head "https://registry-1.docker.io/v2/rancher/klipper-lb/manifests/v0.4.15":
dial tcp [2a03:2880:f111:83:face:b00c:0:25de]:443: i/o timeout
```

**Step 2 — 写 `registries.yaml` 到 4 台节点**（`docker.io → docker.1panel.live`、`gcr.io → gcr.m.daocloud.io`、私有 registry 走 http），重启 `k3s`(master) / `k3s-agent`(3 台 worker)。4 节点仍 Ready，业务 Pod 无影响（重启 k3s 不会重建容器）。

**Step 3 — 踩坑①：DNS 只走 IPv6**

`docker.1panel.live` 解析出的都是 AAAA 记录，而节点 IPv6 出网不通：

```bash
curl -4 ... → 200      # IPv4 通
curl -6 ... → 000      # IPv6 超时
```

containerd/Go 解析器优先 IPv6 → mirror 请求超时 → fallback 到 `registry-1.docker.io` → 同样超时。**修复**：4 台节点 `/etc/hosts` 绑定 IPv4：

```text
104.26.2.138 104.26.3.138 172.67.69.152 docker.1panel.live
```

**Step 4 — 踩坑②：`k3s ctr` 的结果不能代表 kubelet**

`ctr` 是**客户端解析** registry hosts，用的是 `/etc/containerd/certs.d`（默认），**不会**读 k3s 的 `config_path`；所以 `k3s ctr images pull docker.io/...` 一直报超时，加 `--hosts-dir /var/lib/rancher/k3s/agent/etc/containerd/certs.d` 才成功。

→ **结论：验证镜像加速必须让 kubelet 真的去拉**（删 Pod 触发重建），不能只看 `ctr`。

**Step 5 — 踩坑③：svclb 的 label 带 hash**

文档里写的 `-l app=svclb-traefik` 匹配不到，实际 label 是 `app: svclb-traefik-9a5a38ea`。稳定写法：

```bash
kubectl -n kube-system get pod -l svccontroller.k3s.cattle.io/svcname=traefik -o wide
```

**Step 6 — 重建并验收**

```bash
kubectl -n kube-system delete pod svclb-traefik-9a5a38ea-m9rs8 svclb-traefik-9a5a38ea-kj9qt
```

结果：

```text
svclb-traefik-9a5a38ea-brzsd  2/2 Running  k3s-worker1
svclb-traefik-9a5a38ea-ft7g7  2/2 Running  k3s-master
svclb-traefik-9a5a38ea-khmgs  2/2 Running  k3s-worker3   ← 新
svclb-traefik-9a5a38ea-t6k87  2/2 Running  k3s-worker2   ← 新

traefik  LoadBalancer  10.43.143.84  192.168.31.211,192.168.31.251,192.168.31.77,192.168.31.88  80:31667/TCP,443:30261/TCP
```

四节点验收（Mac 上）：

```text
192.168.31.77  :80 -> 200 | :30080 -> 200
192.168.31.211 :80 -> 200 | :30080 -> 200
192.168.31.251 :80 -> 200 | :30080 -> 200
192.168.31.88  :80 -> 200 | :30080 -> 200
```

业务与中间件 Pod 全部 Running，无中断。

**后续注意**

- `svclb-traefik` 是 **DaemonSet**，新加节点会自动调度 → **每次扩容 worker 后复查一次**（现在镜像配置与 hosts 已在 4 台节点上，新节点加入会自动用 `registries.yaml`，但 `/etc/hosts` 的 IPv4 绑定需要同步，或在 cloud-init 模板里预置）。
- `/etc/hosts` 里写死的 Cloudflare IPv4 **可能变动**，加速器失效时优先检查这一条，或改用有稳定 IPv4 的加速器（如 `docker.m.daocloud.io`，A 记录 `139.196.198.243`）。
- Traefik Service 未见 `nodeSelector` / `externalTrafficPolicy` 限制（已确认）。
- 修好后 Ingress 入口由 2 个变 4 个，Mac 侧 `/etc/hosts` 指向任一节点 IP 均可。

---

## 1. seckill 部署计划

### 1.1 端口与依赖

| 项 | 值 |
|---|---|
| 监听端口 | **8086**（`galaxy_mall-seckill/src/main/resources/application.yml`） |
| 下游依赖 | `product:8082`、`order:8084`（OpenFeign，env `SERVICES_PRODUCT_URL` / `SERVICES_ORDER_URL`） |
| 上游入口 | Gateway 已有路由 `seckill-edge`：`/edge/seckill/**` → rewrite → `/api/seckill/**`，目标由 `SERVICES_SECKILL_URL` 注入（默认 `http://localhost:8086`） |
| 对外 API | `POST /edge/seckill/requests`、`GET /edge/seckill/requests/{requestId}` |
| 请求体 | `SeckillRequest{userId, productId, quantity(=1), clientIp}`（`quantity` 被 `@Min(1) @Max(1)` 限定为 1） |
| 库依赖 | `galaxy_mall-common`、`galaxy_mall-stock`、`galaxy_mall-monitor`（打进 fat jar，无需额外服务） |
| 监控端点 | `/actuator/health/readiness`、`/actuator/health/liveness`、`/actuator/prometheus`（已 `include: health,info,prometheus`） |

### 1.2 必须先修的两个源码/脚本问题

**问题 A：镜像构建端口写错**

`deploy/docker/build-k3s-images.sh` 第 18 行把 seckill 的端口写成 8080，与 `application.yml` 的 8086 不一致；同时镜像名 `galaxy-mall-seckill` 与其它服务（`gateway`/`user`/...）命名风格不一致。

```diff
-  "galaxy-mall-seckill:galaxy_mall-seckill:8080"
+  "seckill:galaxy_mall-seckill:8086"
```

> 若暂不重建镜像，可用环境变量兜底：在 Deployment 里加 `SERVER_PORT: "8086"`（Spring Boot 会覆盖 `server.port`），`containerPort` 仍写 8086。

**问题 B：业务清单未渲染 seckill**

`ci_cd/scripts/70_render_business_manifest.sh` 基于 `deploy/k8s/galaxy-mall-uat.yaml` 渲染，而该文件只含 6 个服务。要么在该 yaml 中补 seckill 段，要么单独维护 `deploy/k8s/seckill.yaml`（推荐后者，见 1.4，可作为独立单元灰度）。

### 1.3 配置变更（ConfigMap 增量，禁止整体覆盖）

现有 `galaxy-mall-config` 是共享 CM，用 `patch` 增量添加，避免冲掉其它键：

```bash
kubectl -n galaxy-mall patch cm galaxy-mall-config --type merge -p '{
  "data": {
    "SERVICES_SECKILL_URL": "http://galaxy-mall-seckill:8086",
    "GALAXY_SECKILL_GLOBAL_RATE_LIMIT": "20000",
    "GALAXY_SECKILL_USER_RATE_LIMIT": "1",
    "GALAXY_SECKILL_IP_RATE_LIMIT": "10",
    "GALAXY_SECKILL_WORKER_COUNT": "8",
    "GALAXY_SECKILL_QUEUE_CAPACITY": "4096",
    "GALAXY_SECKILL_REFRESH_INTERVAL_MS": "100",
    "GALAXY_SECKILL_PRODUCT_CACHE_TTL_MS": "30000",
    "GALAXY_SECKILL_TERMINAL_RETENTION_MS": "300000",
    "GALAXY_SECKILL_FEIGN_CONNECT_TIMEOUT_MS": "500",
    "GALAXY_SECKILL_FEIGN_READ_TIMEOUT_MS": "1000"
  }
}'

# Gateway 需要重启才能注入新的 services.seckill.url
kubectl -n galaxy-mall rollout restart deploy/galaxy-mall-gateway
kubectl -n galaxy-mall rollout status  deploy/galaxy-mall-gateway
```

参数含义（来自 `application.yml`）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `GALAXY_SECKILL_GLOBAL_RATE_LIMIT` | 20000 | 全局限流 |
| `GALAXY_SECKILL_USER_RATE_LIMIT` | 1 | 单用户限流 |
| `GALAXY_SECKILL_IP_RATE_LIMIT` | 10 | 单 IP 限流 |
| `GALAXY_SECKILL_WORKER_COUNT` | 8 | 异步 worker 数 |
| `GALAXY_SECKILL_QUEUE_CAPACITY` | 4096 | 队列容量 |
| `GALAXY_SECKILL_REFRESH_INTERVAL_MS` | 100 | 状态刷新间隔 |
| `GALAXY_SECKILL_PRODUCT_CACHE_TTL_MS` | 30000 | 商品缓存 TTL |
| `GALAXY_SECKILL_TERMINAL_RETENTION_MS` | 300000 | 终态保留时长 |

### 1.4 部署清单 `deploy/k8s/seckill.yaml`

> 镜像名按 1.2 修正后的 `seckill`；若沿用旧镜像名，把下面两处 `seckill:` 换成 `galaxy-mall-seckill:` 即可。

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: galaxy-mall-seckill
  namespace: galaxy-mall
  labels:
    app: galaxy-mall-seckill
spec:
  replicas: 2
  revisionHistoryLimit: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: galaxy-mall-seckill
  template:
    metadata:
      labels:
        app: galaxy-mall-seckill
    spec:
      terminationGracePeriodSeconds: 45   # > lifecycle.timeout-per-shutdown-phase(30s)
      containers:
        - name: seckill
          image: registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/seckill:k3s
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8086
          envFrom:
            - configMapRef:
                name: galaxy-mall-config
          env:
            - name: SERVER_PORT
              value: "8086"          # 兜底：镜像若按 8080 构建也能拉起 8086
            - name: JAVA_OPTS
              value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=70"
          resources:
            requests:
              cpu: "500m"
              memory: "768Mi"
            limits:
              cpu: "1"
              memory: "1Gi"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8086
            initialDelaySeconds: 15
            periodSeconds: 5
            failureThreshold: 6
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8086
            initialDelaySeconds: 40
            periodSeconds: 10
            failureThreshold: 3
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values: ["galaxy-mall-seckill"]
                topologyKey: kubernetes.io/hostname
---
apiVersion: v1
kind: Service
metadata:
  name: galaxy-mall-seckill
  namespace: galaxy-mall
spec:
  selector:
    app: galaxy-mall-seckill
  ports:
    - name: http
      port: 8086
      targetPort: 8086
```

### 1.5 镜像准备（离线导入路径，不依赖外部仓库）

集群当前**拉不到 docker.io / gcr.io**，且 GitLab Registry 已不存在，所以走"本地构建 + `ctr images import`"：

```bash
# 在能构建的机器（Mac 或 VM107 编译机）上
cd /path/to/galaxy_mall
sed -i '' 's|"galaxy-mall-seckill:galaxy_mall-seckill:8080"|"seckill:galaxy_mall-seckill:8086"|' deploy/docker/build-k3s-images.sh
IMAGE_TAG=k3s ./deploy/docker/build-k3s-images.sh      # 产出 deploy/docker/output/seckill-k3s.tar

# 传到 4 台节点并导入（k3s 用 containerd，必须 4 台都导，否则调度到没镜像的节点会 ErrImagePull）
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  scp deploy/docker/output/seckill-k3s.tar wdidada@$ip:/tmp/
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S /usr/local/bin/k3s ctr images import /tmp/seckill-k3s.tar"
done
```

> 旧镜像名兼容：若沿用 `galaxy-mall-seckill:k3s`，把上面镜像名替换即可，**不要**改 Deployment 里的 `imagePullPolicy: IfNotPresent`（否则会尝试联网拉取而卡住）。

### 1.6 上线与验证

```bash
kubectl apply -f deploy/k8s/seckill.yaml
kubectl -n galaxy-mall rollout status deploy/galaxy-mall-seckill --timeout=180s
kubectl -n galaxy-mall get pods -l app=galaxy-mall-seckill -o wide

# 1) 直连服务（任选其一）
kubectl -n galaxy-mall port-forward svc/galaxy-mall-seckill 8086:8086 &
curl -s -X POST http://127.0.0.1:8086/api/seckill/requests \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u100","productId":"sku-iphone","quantity":1,"clientIp":"10.0.0.1"}'
curl -s http://127.0.0.1:8086/api/seckill/requests/{requestId}
curl -s http://127.0.0.1:8086/actuator/prometheus | head -20

# 2) 经网关（修复 0.4 后 4 个节点 IP 任选；未修复时只能用 .77/.211）
curl -s -X POST -H "Host: mall.192.168.31.77.nip.io" \
  http://192.168.31.77/edge/seckill/requests \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u100","productId":"sku-iphone","quantity":1,"clientIp":"10.0.0.1"}'
```

验收标准：

- [ ] 2 个副本 Running，分布在不同节点（反亲和生效）
- [ ] readiness 探针 `/actuator/health/readiness` 200
- [ ] `POST /api/seckill/requests` 返回 `success:true` 且带 `requestId`
- [ ] `GET /api/seckill/requests/{requestId}` 能查到终态
- [ ] 网关 `/edge/seckill/requests` 转发成功（说明 `SERVICES_SECKILL_URL` 已注入）
- [ ] `/actuator/prometheus` 有 `jvm_*` / `http_server_requests_*` 指标

### 1.7 回滚

```bash
kubectl -n galaxy-mall scale deploy/galaxy-mall-seckill --replicas=0   # 先摘流量
# 或
kubectl delete -f deploy/k8s/seckill.yaml
kubectl -n galaxy-mall patch cm galaxy-mall-config --type json \
  -p '[{"op":"remove","path":"/data/SERVICES_SECKILL_URL"}]'
kubectl -n galaxy-mall rollout restart deploy/galaxy-mall-gateway
```

> 注意：Gateway 在 `SERVICES_SECKILL_URL` 未注入时默认 `http://localhost:8086`，自身 Pod 内无监听 → `/edge/seckill/**` 会 503。回滚必须连带移除 CM 键并重启 Gateway，或保留键指向一个占位服务。

### 1.8 资源预算

| 项 | 需求 | 说明 |
|---|---|---|
| CPU | 2 × 500m request / 1 core limit | 4 节点共 16C，宽松 |
| 内存 | 2 × 1Gi limit | 各节点可用内存 4~8G，宽松 |
| 磁盘 | 0（无 PVC） | seckill 无状态 |
| 副本 | 2 | 配合 `podAntiAffinity` 软策略分散 |

---

## 2. monitor 落地计划（不部署 monitor 服务）

### 2.1 为什么不是服务

`galaxy_mall-monitor` 只提供 `MetricsCollector`（进程内 `ConcurrentHashMap` 计数器 + 延迟列表）与 `TraceLogger`。它：

- 没有 `main`、没有 Spring Boot 启动类、没有 HTTP 端点；
- 数据是**单进程内存态**，多副本无意义，也无法被 Prometheus 直接抓取；
- 被 seckill/stock 等模块作为编译依赖引入。

所以"monitor 未部署"不是缺口，而是设计如此。真正的可观测性应建立在**已存在的 actuator + micrometer** 之上：`application.yml` 里各服务均已 `exposure.include: health,info,prometheus`，且 seckill/payment 等已引入 `micrometer-registry-prometheus`。

### 2.2 目标架构

```
Java Pods (/actuator/prometheus)
        │  PodMonitor/ServiceMonitor
        ▼
   Prometheus  ──►  Grafana
   (NodePort 30090)  (NodePort 30300)
        │
   RocketMQ Dashboard (已有, 30808) / node-exporter / kube-state-metrics
```

### 2.3 落地步骤

**步骤 1：确认/补齐 micrometer 依赖**

需要接入监控的模块应在 `pom.xml` 中含：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

并在 `application.yml` 保证 `management.endpoints.web.exposure.include` 含 `prometheus`。
`[待确认]`：逐个模块核对（seckill 已确认有；user/product/order/stock/payment/gateway 需逐个查 pom 与 yml）。

**步骤 2：部署 Prometheus + Grafana**

仓库已有 `deploy/k8s/prometheus-grafana-k3s.md`（Grafana NodePort 30300 / admin `5Edidada`、Prometheus 30090）。落地要点：

- 采用 `kube-prometheus-stack`（prometheus-community）时，**镜像源必须替换**（本集群 docker.io / gcr.io 不可达）：
  - Prometheus / node-exporter / alertmanager → `quay.io`（可达）
  - kube-state-metrics → 默认 `registry.k8s.io`，`[待确认]` 是否可达，不可达时覆盖为 `quay.io/coreos/kube-state-metrics` 或经加速器拉取
  - Grafana → `docker.io/grafana/grafana`，需配置 docker.io 加速器（见 CI/CD 手册第 3 章）
- 存储：`local-path` + 单节点磁盘余量有限（master 仅 24G 可用），**Prometheus PVC 建议 ≤ 10Gi 且 retention 设 7d**：
  ```yaml
  prometheus:
    prometheusSpec:
      retention: 7d
      retentionSize: 8GiB
      storageSpec:
        volumeClaimTemplate:
          spec:
            storageClassName: local-path
            resources: { requests: { storage: 10Gi } }
  ```
- 暴露：
  ```yaml
  grafana:
    service: { type: NodePort, nodePort: 30300 }
  prometheus:
    service: { type: NodePort, nodePort: 30090 }
  ```
  NodePort 在 4 个节点全通（已实测 30080/30808 四节点均通）。

**步骤 3：抓取配置**

用 PodMonitor 按标签抓取所有 Java 服务：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: galaxy-mall-java
  namespace: galaxy-mall
spec:
  selector:
    matchExpressions:
      - key: app
        operator: Exists
  podMetricsEndpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

> 前提：各 Service 的 port 要有 `name: http`（现有 uat 模板部分 Service 未命名端口，需要补 `name: http`）。

**步骤 4：验收**

- `http://192.168.31.77:30090` 打开 Prometheus，查 `jvm_memory_used_bytes`、`http_server_requests_seconds_count`
- `http://192.168.31.77:30300` Grafana（admin / 5Edidada），导入 JVM 面板（参考 `deploy/k8s/how-to-view-java-in-grafana.md`）
- RocketMQ 业务面：`http://192.168.31.77:30808`

---

## 3. message 落地计划（不部署 message 服务）

### 3.1 为什么不是服务

`galaxy_mall-message` 只有一个 `InMemoryMessageBus`：进程内 `ConcurrentLinkedQueue` + 单线程调度器 + 死信列表。

- 无启动类、无网络端点；
- **多副本下消息不互通、进程重启即丢**——这决定了它只能用于单实例/测试场景，不能作为"服务"部署。

### 3.2 生产路径：统一到 RocketMQ（已就绪）

集群已部署 `rocketmq-namesrv:9876` 与 `rocketmq-broker`，且 `order` 服务已在用：

```
GALAXY_ORDER_MESSAGING_BACKEND=rocketmq
GALAXY_ORDER_ROCKETMQ_TOPIC=galaxy-order-submitted
GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP=galaxy-order-consumer
ROCKETMQ_PRODUCER_GROUP=galaxy-order-producer
```

落地动作（无需新建服务）：

1. **约定 topic 命名**：`<domain>-<event>-<env>`，例如
   - `galaxy-order-submitted`（已用）
   - `galaxy-seckill-submitted`（新增，seckill 落单后投递）
   - `galaxy-payment-result`（新增，预留）
2. **消费者组**：`galaxy-<domain>-consumer`，多副本 Pod 同组消费 = 天然负载均衡（这正是 `InMemoryMessageBus` 做不到的）。
3. **代码约束**：新增异步链路一律走 RocketMQ；`InMemoryMessageBus` 仅保留在单元测试与本地 `dev` profile，生产配置中禁止启用。
4. **可观测**：RocketMQ Dashboard（30808）看 topic 堆积；`[待确认]` 是否引入 rocketmq-exporter 纳入 Prometheus。

### 3.3 若坚持做成独立服务（备选，需改造）

必须做的最小改造：

1. 新增 `MessageServiceApplication`（`@SpringBootApplication`）+ `spring-boot-starter-web` 依赖；
2. 暴露运维端点：
   - `GET /actuator/message/backlog` → `InMemoryMessageBus.backlog()`
   - `GET /actuator/message/dead-letters` → `deadLetters()`
   - 注册为 Micrometer Gauge，纳入 Prometheus；
3. **强制 `replicas: 1` + `strategy.type: Recreate`**（内存态，不能多副本），并声明该服务为"单实例、有状态、非 HA"；
4. 明确它不与 RocketMQ 争职责：只做本地事件派发/补偿。

> 结论：除非有明确的进程内事件需求，否则**不建议**走这条路——它把一个已解决（RocketMQ）的问题重新变成单点。

---

## 4. 里程碑

| 阶段 | 内容 | 产出/验收 | 依赖 |
|---|---|---|---|
| **M0** | ✅ **已完成 2026-10-17**：修 `svclb-traefik`，Traefik LB 覆盖 4 个节点 | EXTERNAL-IP = `.211/.251/.77/.88`；四节点 `:80` curl 全 200（记录见 0.5） | — |
| M1 | 修 `build-k3s-images.sh`（8086 + 命名）、补 `deploy/k8s/seckill.yaml` | 清单入库 | 无 |
| M2 | 构建并导入 seckill 镜像到 4 节点 | `ctr images list` 四节点可见 | M1 |
| M3 | patch ConfigMap + 部署 seckill + 重启 gateway | 1.6 验收清单全绿 | M2 |
| M4 | 核对各模块 micrometer/actuator | 每个服务 `/actuator/prometheus` 有数据 | 无 |
| M5 | 部署 Prometheus + Grafana（NodePort 30090/30300） | Grafana 有 JVM 面板 | M4、镜像源 |
| M6 | RocketMQ topic 规范化 + 文档化 | Dashboard 可见 topic 与消费组 | 无 |

## 5. 风险清单

| 风险 | 影响 | 对策 |
|---|---|---|
| 镜像端口不一致（8080 vs 8086） | 探针失败、CrashLoop | 优先改脚本；短期用 `SERVER_PORT=8086` 兜底 |
| ConfigMap 被整体覆盖 | 其它服务配置丢失 | 只用 `patch --type merge`，先备份 |
| Gateway 未重启 | `/edge/seckill/**` 指向 localhost 导致 503 | 必须 `rollout restart gateway` |
| 镜像只导入部分节点 | 调度到缺镜像节点 → `ErrImagePull` | 4 台全导；或加 `nodeSelector` 先钉在已导入节点 |
| ~~Traefik LB 只覆盖 2 个节点~~（已于 2026-10-17 修复） | — | 已修：加速器 + `/etc/hosts` 绑 IPv4 + 重建 svclb，4 节点全通（见 0.5）。**残留风险**：`/etc/hosts` 写死的加速器 IPv4 可能变动；扩容新 worker 时需同步 hosts |
| Prometheus PVC 撑爆磁盘 | 节点磁盘写满 | PVC ≤10Gi + retention 7d；必要时 Proxmox 侧 `qm resize` |
| seckill 限流参数与压测目标不匹配 | 100w TPS 计划失真 | 按 `100w-tps-test-plan.md` 调 `GALAXY_SECKILL_*`，先在单副本下调参 |
| `InMemoryMessageBus` 被误用于多副本 | 消息丢失 | 生产强制 `GALAXY_ORDER_MESSAGING_BACKEND=rocketmq` |
