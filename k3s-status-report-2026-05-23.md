# K3s 运行情况巡检报告

- 巡检时间: 2026-05-23 09:57 CST (UTC+08:00)
- 巡检对象:
  - `192.168.31.77` / `k3s-master`
  - `192.168.31.211` / `k3s-worker1`
- 操作方式: 使用 `wdidada` 账号通过 SSH 登录，必要命令通过 `sudo` 执行

## 1. 总体结论

集群控制面和节点层面整体可用，`k3s` 服务正常运行，2 个节点均为 `Ready`，`kube-system` 和 `middleware` 命名空间核心组件均已正常运行。

当前主要问题不在 K3s 本身，而在业务工作负载就绪状态:

- `galaxy-mall-order`
- `galaxy-mall-payment`
- `galaxy-mall-product`
- `galaxy-mall-stock`
- `galaxy-mall-user`

以上业务 Deployment 的 Pod 多数处于 `Running` 但 `0/1 Ready`，表现为应用容器已启动，但就绪探针持续失败，服务端口未对探针请求正常响应。

## 2. 主机与 K3s 服务状态

| 主机 | IP | 角色 | OS | K3s 版本 | 服务状态 | 运行时长 |
| --- | --- | --- | --- | --- | --- | --- |
| k3s-master | 192.168.31.77 | control-plane | CentOS Stream 9 | v1.35.4+k3s1 | `k3s.service active` | 约 49 分钟 |
| k3s-worker1 | 192.168.31.211 | worker | CentOS Stream 9 | v1.35.4+k3s1 | `k3s-agent.service active` | 约 49 分钟 |

补充观察:

- 两台虚拟机内核均为 `5.14.0-704.el9.x86_64`
- 两台机器本次巡检时系统 uptime 都约 49 分钟，说明两台 VM 近期发生过重启
- `k3s-master` 上 `k3s.service` 已运行约 47 分钟
- `k3s-worker1` 上 `k3s-agent.service` 正常运行，容器运行时进程正常存在

## 3. 集群节点状态

`kubectl get nodes -o wide` 结果:

| 节点 | 状态 | 角色 | 版本 | Internal IP | Container Runtime |
| --- | --- | --- | --- | --- | --- |
| k3s-master | Ready | control-plane | v1.35.4+k3s1 | 192.168.31.77 | containerd://2.2.3-k3s1 |
| k3s-worker1 | Ready | worker | v1.35.4+k3s1 | 192.168.31.211 | containerd://2.2.3-k3s1 |

结论:

- K3s 节点注册正常
- 节点间网络当前可用
- 控制面未见异常告警

## 4. 资源使用情况

### 4.1 节点资源

`kubectl top nodes` 结果:

| 节点 | CPU | CPU% | Memory | Memory% |
| --- | --- | --- | --- | --- |
| k3s-master | 502m | 12% | 6662Mi | 78% |
| k3s-worker1 | 195m | 4% | 5842Mi | 77% |

### 4.2 系统资源

| 主机 | 磁盘使用 | 内存情况 | Swap |
| --- | --- | --- | --- |
| k3s-master | `/` 38G 中已用 9.8G，26% | 8.3Gi 总内存，1.9Gi available | 6.4Gi，未使用 |
| k3s-worker1 | `/` 38G 中已用 13G，33% | 7.4Gi 总内存，1.8Gi available | 未配置 |

结论:

- 当前磁盘空间充足
- 两台机器内存占用偏高但未到紧急程度
- worker 未配置 swap，这对 K8s 节点通常可接受

## 5. Pod 与业务可用性

### 5.1 正常工作负载

以下组件运行正常:

- `front` 1/1 Ready
- `galaxy-mall-gateway` 2/2 Ready
- `coredns`、`metrics-server`、`traefik`、`local-path-provisioner`
- `mysql`、`redis`、`nacos`、`rocketmq-*`

### 5.2 异常业务工作负载

`kubectl get deploy,statefulset -A` 显示:

| 工作负载 | Ready | Available | 结论 |
| --- | --- | --- | --- |
| galaxy-mall-order | 0/2 | 0 | 异常 |
| galaxy-mall-payment | 0/1 | 0 | 异常 |
| galaxy-mall-product | 0/1 | 0 | 异常 |
| galaxy-mall-stock | 0/2 | 0 | 异常 |
| galaxy-mall-user | 0/1 | 0 | 异常 |

对应 Pod 当前多为:

- `STATUS=Running`
- `READY=0/1`
- 最近 46 到 49 分钟内发生过重建或重启

这说明:

- 容器进程并非完全拉不起来
- 但应用没有通过 K8s readiness probe
- 对外服务很可能不可用或未完全可用

## 6. 关键异常明细

### 6.1 Order 服务

示例 Pod: `galaxy-mall-order-965b576d4-gbldd`

核心事件:

- 持续出现 `Readiness probe failed`
- 探针地址: `http://<pod-ip>:8084/actuator/health/readiness`
- 失败原因: `connect: connection refused`

判定:

- 容器已启动
- 但应用未监听 `8084`，或启动后未能提供 readiness 接口

### 6.2 Payment 服务

示例 Pod: `galaxy-mall-payment-746b9655bc-zm26n`

核心事件:

- `Readiness probe failed`
- 探针地址端口为 `8085`
- 最近约 70 分钟前出现过 `Back-off restarting failed container`
- 约 49 分钟前出现过 `FailedCreatePodSandBox`

关键报错:

`failed to load flannel 'subnet.env' file: open /run/flannel/subnet.env: no such file or directory`

判定:

- 节点重启或网络组件恢复过程中出现过一次 CNI/Flannel 初始化时序问题
- 当前 Pod 已重新进入 Running，但业务应用仍未通过 readiness

### 6.3 Product 服务

示例 Pod: `galaxy-mall-product-647764b486-qjr9k`

核心事件:

- `Readiness probe failed`
- 探针地址端口为 `8082`
- 最近约 70 分钟前出现过 `Back-off restarting failed container`
- 约 49 分钟前同样出现过 Flannel `subnet.env` 缺失

判定:

- 与 Payment 类似，先发生过节点恢复期网络/沙箱创建异常
- 当前主要剩余问题仍然是应用 readiness 不通过

## 7. 对当前状态的判断

可以将当前状态分成两层:

### 7.1 K3s 平台层

平台层基本正常:

- K3s server/agent 均为 active
- 2 个节点均为 Ready
- 核心系统 Pod 正常
- `journalctl -p warning` 未见新的 systemd/k3s warning

### 7.2 业务应用层

业务层存在明显可用性问题:

- 多个业务服务副本未 Ready
- readiness probe 指向的服务端口持续 `connection refused`
- 问题更像是应用本身未成功启动完成，而不是节点彻底宕机

## 8. 建议优先排查项

1. 先查看异常业务 Pod 的应用日志，重点查启动失败、配置缺失、依赖连接失败。
2. 核对 readiness probe 配置是否与应用实际监听端口一致。
3. 检查这些服务在重启后是否依赖 `nacos`、`mysql`、`redis`、`rocketmq` 的初始化顺序。
4. 针对 `payment`、`product`、`order` 优先执行 `kubectl logs`，因为它们已经明确出现 readiness 失败和重启迹象。
5. 复核两台 VM 最近一次重启原因，确认是否是宿主机维护、PVE 重启或客体系统异常重启。
6. 如果近期还有新 Pod 无法起 sandbox，再专项检查 flannel/CNI 启动时序和 `/run/flannel/subnet.env` 生成链路。

## 9. 最终结论

本次巡检结论:

- K3s 集群本身是存活且可调度的
- 两个节点当前都在线并 Ready
- 基础中间件和系统组件基本正常
- 主要故障集中在 `galaxy-mall` 业务服务的 readiness 未通过
- 从现象看，当前更接近“应用启动或健康检查异常”，不是“K3s 集群不可用”

