# 从 Mac 访问 k3s 上的 HTTP 接口（速查）

> 实测日期：2026-10-17。集群：Proxmox `192.168.31.171` 上 4 台 VM 组成的 k3s（v1.35.4+k3s1）。
> 状态：Traefik LoadBalancer 已覆盖 4 个节点，Ingress 与 NodePort 在 `.77/.211/.251/.88` 全部可用。

## 0. 一句话

**最简单**：浏览器开 `http://192.168.31.77:30080/`（前端，且同源转发 `/api/gateway/**`）。
**调接口**：写一条 hosts 后访问 `http://mall.192.168.31.77.nip.io/api/gateway/...`，或直接带 `Host` 头打 `.77:80`。

## 1. 入口总览

| 入口 | 地址 | 说明 | 四节点可用 |
|---|---|---|---|
| 前端（NodePort） | `http://<节点IP>:30080/` | `front` 服务，**同源转发 `/api/gateway/**`**，最省事 | ✅ |
| 网关（Ingress） | `http://mall.192.168.31.77.nip.io/` → `galaxy-mall-gateway:8080` | 需 Host 头或 hosts 解析 | ✅ |
| RocketMQ 控制台 | `http://<节点IP>:30808/` | `middleware` 命名空间 | ✅ |
| Grafana | `http://<节点IP>:30300/` | **尚未部署**（计划中，见部署计划第 2 章） | — |
| Prometheus | `http://<节点IP>:30090/` | **尚未部署** | — |
| Proxmox Web | `https://192.168.31.171:8006` | 宿主管理台 | — |
| Netdata | `http://192.168.31.171:19999` | 宿主监控（单 IP 放行限制，见 proxmox-ops.md） | — |

节点 IP（DHCP，可能变动，以 `kubectl get nodes -o wide` 为准）：

```text
192.168.31.77    k3s-master
192.168.31.211   k3s-worker1
192.168.31.251   k3s-worker2
192.168.31.88    k3s-worker3
```

## 2. 三种访问方式

### 方式 1：最省事 —— front NodePort（推荐先试）

```bash
curl http://192.168.31.77:30080/
curl "http://192.168.31.77:30080/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

无需 Host 头，浏览器直接打开即可。前端构建产物里的 `API_BASE` 在 prod 为空 → 同源请求 → 由 front 容器内 nginx 转发到 gateway。

### 方式 2：走 Ingress 域名（接口调用推荐）

先在 Mac 上加 hosts（**比 nip.io 稳**）：

```bash
echo "192.168.31.77 mall.192.168.31.77.nip.io" | sudo tee -a /etc/hosts
# 清 DNS 缓存
sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder
```

然后：

```bash
curl "http://mall.192.168.31.77.nip.io/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

不想改 hosts 就每次带 Host 头（4 个节点 IP 任选）：

```bash
curl -H "Host: mall.192.168.31.77.nip.io" \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

> 注意：**不带 Host 头直接打 `http://192.168.31.77:80` 会 404**（Traefik 按 host 匹配）。
> nip.io 走公网 DNS，本机实测被解析到 `198.18.x`（代理 fake-ip）才通；一旦关代理或 DNS 变化就失效 → 用 hosts 更稳。

### 方式 3：port-forward（最稳，不依赖 LB/DNS）

```bash
# 1) 拿 kubeconfig（Mac 上需先装 kubectl: brew install kubectl）
ssh wdidada@192.168.31.77 'sudo cat /etc/rancher/k3s/k3s.yaml' > ~/.kube/config-k3s
# 把里面的 server: https://127.0.0.1:6443 改成 https://192.168.31.77:6443
sed -i '' 's|127.0.0.1|192.168.31.77|' ~/.kube/config-k3s

# 2) 转发
kubectl --kubeconfig ~/.kube/config-k3s -n galaxy-mall port-forward svc/galaxy-mall-gateway 8080:8080
curl "http://localhost:8080/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

# 中间件同样可转发
kubectl --kubeconfig ~/.kube/config-k3s -n middleware port-forward svc/nacos 8848:8848      # http://localhost:8848/nacos
kubectl --kubeconfig ~/.kube/config-k3s -n middleware port-forward svc/mysql 3306:3306
kubectl --kubeconfig ~/.kube/config-k3s -n middleware port-forward svc/redis 6379:6379
```

## 3. 接口清单（可直接复制）

网关编排接口（`/api/gateway`）：

```bash
# 商品查询
curl "http://mall.192.168.31.77.nip.io/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

# 下单（body 字段：userId, productId, quantity, clientIp, queueToken）
curl -X POST "http://mall.192.168.31.77.nip.io/api/gateway/orders" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u100","productId":"sku-iphone","quantity":1,"clientIp":"10.0.0.1"}'

# 查订单
curl "http://mall.192.168.31.77.nip.io/api/gateway/orders/{orderId}"

# 支付
curl -X POST "http://mall.192.168.31.77.nip.io/api/gateway/payments/{orderId}"

# 健康检查
curl "http://mall.192.168.31.77.nip.io/actuator/health"
```

直连下游的 edge 路由（网关 rewrite 后转发，前缀 `/edge/...`）：

| 前缀 | 目标服务 |
|---|---|
| `/edge/users/**` | user:8081 |
| `/edge/products/**` | product:8082 |
| `/edge/seckill/**` | seckill:8086（**尚未部署**） |
| `/edge/stocks/**` | stock:8083 |
| `/edge/orders/**` | order:8084 |
| `/edge/payments/**` | payment:8085 |

> `/edge/seckill/**` 当前返回 **500**（seckill 未部署，`SERVICES_SECKILL_URL` 默认指向 `localhost:8086`），属预期；部署计划见 `docs/k3s-seckill-message-monitor-deploy-plan.md`。

## 4. 已失效的旧地址（别再用）

| 旧地址 | 现状 |
|---|---|
| `http://192.168.31.171:30897/...` | **000 不通**。`.171` 是 Proxmox 宿主，不承载 k3s 端口；30897 这个旧 NodePort 早已不存在 |
| `http://192.168.31.171:30300`（Grafana） | 不通，且集群内尚未部署 Grafana |

> 2026-10-17 已修正（全部）：`api.md`、`readme.md`、`100w-tps-test-plan.md`、`front/src/api.js`（dev `API_BASE`）、
> `curl_tps_get.sh`、`curl_tps_post.sh`（默认 URL + `no_proxy`）、`deploy/k8s/rocketmq-k3s.md`（「30897 端口怎么访问」整节重写）、
> `deploy/k8s/how-to-view-java-in-grafana.md`（`.171:30300/30090`）、`dell_r630/galaxy-mall-seckill-perf-test-plan.md`、
> `ci_cd/env.sh`（`REMOTE_SSH_TARGET`）。
> 仓库中剩余的 `192.168.31.171` 仅出现在两处合法场景：`docs/proxmox-ops.md`（它本来就是 Proxmox 宿主地址）和上面这些「旧地址已失效」的说明文字。

## 5. 排障速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `curl` 返回 `000` | 节点 IP 变了（DHCP）/ 节点关机 / 端口错 | `kubectl get nodes -o wide` 复查；或直接换成另一个节点 IP |
| 打 `:80` 返回 404 | 没带 `Host` 头 | 加 `-H "Host: mall.192.168.31.77.nip.io"` |
| 域名解析到 `198.18.x` | 被本机代理 fake-ip 接管 | 写 `/etc/hosts`；或确认代理开启 |
| 只有部分节点 `:80` 通 | 该节点 `svclb-traefik` 未就绪 | `kubectl -n kube-system get pod -l svccontroller.k3s.cattle.io/svcname=traefik -o wide`；详见部署计划 0.5 |
| `/edge/seckill/**` 500 | seckill 未部署 | 预期，见部署计划第 1 章 |
| NodePort 不通但 Ingress 通（或反之） | 两者机制不同 | NodePort 走 kube-proxy（全节点）；Ingress 走 svclb（也应为全节点） |

## 6. 关联文档

| 文档 | 内容 |
|---|---|
| `docs/k3s-seckill-message-monitor-deploy-plan.md` | 集群现状、Traefik LB 覆盖 4 节点的修复记录（0.4/0.5）、seckill 部署与验证命令 |
| `docs/cicd-gitlab-jenkins-landing.md` | GitLab/Jenkins 落地；4.1 镜像加速、4.2 nip.io 解析、4.4 svclb 修复 |
| `docs/proxmox-ops.md` | Proxmox 宿主、VM 清单、Netdata 与 SSH 隧道 |
| `deploy/k8s/how-to-view-k3s-java-services.md` | kubectl 侧查看服务/Pod/日志 |
| `api.md` | 接口示例（**地址已过期**，见本文第 4 节） |
