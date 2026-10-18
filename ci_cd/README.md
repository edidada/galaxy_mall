# Galaxy Mall CI/CD Bootstrap

这套目录用于把 `CentOS 7 + k3s` 主机快速拉成一套可迁移的研发交付平台，目标是：

- `k3s` 上部署 `GitLab CE`
- `k3s` 上部署 `Jenkins`
- Jenkins 使用 `Kubernetes Agent + Kaniko + kubectl` 跑流水线
- GitLab 保存代码、Webhook 触发 Jenkins、GitLab Container Registry 保存镜像
- Jenkins 把本仓库打包、推镜像、发布到当前 k3s

## 目录

- `env.sh`: 所有可迁移参数
- `scripts/00_prepare_host.sh`: 宿主机依赖和内核/防火墙准备
- `scripts/10_install_k3s.sh`: 安装或校验 k3s
- `scripts/20_install_gitlab_prereqs.sh`: GitLab 外部 PostgreSQL / Redis
- `scripts/30_install_gitlab.sh`: GitLab CE Helm 部署
- `scripts/40_install_jenkins.sh`: Jenkins Helm + JCasC + Job DSL
- `scripts/50_bootstrap_gitlab_project.sh`: 初始化 GitLab 组、项目、Token、Webhook
- `scripts/60_install_business_dependencies.sh`: Galaxy Mall 依赖中间件
- `scripts/70_render_business_manifest.sh`: 渲染业务部署清单
- `scripts/80_apply_business_stack.sh`: 发布业务中间件和应用
- `scripts/90_bootstrap_all.sh`: 一键串行安装
- `scripts/95_smoke_test.sh`: 安装后验收
- `scripts/sync_to_remote.sh`: 将当前 `ci_cd` 同步到远端机器
- `pipeline/Jenkinsfile`: Jenkins 使用的流水线脚本

## 快速用法

在目标主机本机执行：

```bash
cd /path/to/galaxy_mall
chmod +x ci_cd/scripts/*.sh
./ci_cd/scripts/90_bootstrap_all.sh
```

如果你先在本地生成，再推到远端：

```bash
cd /path/to/galaxy_mall
chmod +x ci_cd/scripts/*.sh
./ci_cd/scripts/sync_to_remote.sh
```

然后登录远端执行：

```bash
cd /home/wdidada/galaxy_mall
./ci_cd/scripts/90_bootstrap_all.sh
```

注意：

- `sync_to_remote.sh` 同步的是整个仓库，不只是 `ci_cd`
- 这样 Jenkins 初始化项目时才能把当前代码仓直接推到本地 GitLab
- `70_render_business_manifest.sh` 也依赖仓库内现有 `deploy/k8s` 资产

## 默认访问地址

脚本默认用 `nip.io` 生成可迁移域名：

- GitLab: `http://gitlab.<NODE_IP>.nip.io`
- Registry: `http://registry.<NODE_IP>.nip.io`
- Jenkins: `http://jenkins.<NODE_IP>.nip.io`
- Gateway: `http://mall.<NODE_IP>.nip.io`

如果你有正式内网 DNS，只改 `env.sh` 即可。

## 版本与取舍

- `k3s` 版本默认固定为 `v1.28.8+k3s1`，与当前机器一致，避免环境漂移
- GitLab 用 Helm Chart，固定到 `9.8.3`
- Jenkins 用官方 Helm Chart，固定到 `5.8.110`
- GitLab 官方文档明确建议生产部署使用外部 PostgreSQL / Redis；这里按这个原则把二者拆成独立 k8s 工作负载
- 对象存储为控制复杂度仍使用 GitLab Chart 自带 MinIO；单机可跑，后续如需上更完整架构，可把 MinIO 替换为外部对象存储

## 当前假设

- 目标机具备公网或至少能访问 Helm 仓库与容器镜像仓库
- 目标机资源至少接近当前机器：`48C / 62G`
- 这是单节点 k3s，配置偏“单机可复现的大厂式流程”，不是多 AZ 高可用版

## 验收结果应该包含

- `gitlab`、`jenkins`、`middleware`、`galaxy-mall` 命名空间服务全部 Ready
- Jenkins 中存在 `galaxy-mall-delivery` 流水线任务
- GitLab 项目已创建并绑定 Jenkins Webhook
- `git push` 到默认分支后，Jenkins 自动构建并部署

---

## 2026-10-18 追加：业务调度基线（requests/limits + 节点池 + 反亲和 + startupProbe）

上面"全部 Ready"这类验收只说明服务起着，不说明它**占了多少、在哪台机器上**。
现网 7 个业务 Deployment 原本一个 `resources` 都没写，`describe node` 里
`ephemeral-storage` requests 全是 0，同时 master（control-plane）上还跑着
gateway/order/stock 三个业务 Pod。这次补齐的东西都在
[`deploy/k8s/apply-scheduling-and-resources.sh`](../deploy/k8s/apply-scheduling-and-resources.sh)：

| 加了什么 | 值 | 为什么是这个值 |
|---|---|---|
| Java 服务 requests/limits | `200m/512Mi/eph 512Mi` ← `1000m/1Gi/eph 2Gi` | `kubectl top pods` 实测常驻 330–460Mi；limit 1Gi 下 JVM 默认堆约 256Mi，实测 0 重启、下单链路通 |
| front（nginx） | `25m/32Mi` ← `200m/128Mi` | 实测 4Mi |
| `nodeSelector: galaxy.io/pool=app` | worker1/worker4 | worker2 根盘 82%、worker3 cpu requests 83%，只有这两个节点有余量；顺带让业务永远离开 control-plane |
| `topologySpreadConstraints` | maxSkew 1、`part-of=galaxy-mall`、ScheduleAnyway | 整组均分 |
| 两条软反亲和 | 隔 doit 重户（weight 100）、同服务两副本不同节点（weight 90） | 只做整组均分会让 order 两副本挤在同一节点，那个节点一挂就全灭 |
| `startupProbe` | period 5s × 36（≤180s） | 加 cpu limit 后 5~6 个 JVM 并发启动，gateway 用 40s+，原来 liveness 只给 30s → 容器被反复杀（实测事件 `failed liveness probe, will be restarted`） |

**执行方式必须是 `kubectl patch --type=strategic`，不能是 `kubectl apply`**：
`ci_cd/rendered/galaxy-mall.yaml` 渲染出来的 image 是模板 tag，而现网 image 是
GitHub Actions 推的 `git-<sha>`；apply 会把线上版本打回旧 tag —— 等于借"修调度"之名回滚一次。
因此 [`scripts/80_apply_business_stack.sh`](scripts/80_apply_business_stack.sh) 改成
`apply` 之后再跑一次 `apply-scheduling-and-resources.sh patch` 把约束钉回去（幂等）。

现网已按此执行，并额外做了两件事（详见 r630-proxmox8 仓库 `cicd/ops-runbook.md` 第 9 节）：
`middleware` 的 `rocketmq-broker`/`redis` 先补 `control-plane` 容忍（它们的 local-path 卷绑在 master），
然后给 `k3s-master` 打 `node-role.kubernetes.io/control-plane:NoSchedule`。
worker 节点**只打标签不打污点** —— svclb-traefik 不容忍自定义污点，打了会把入口搞挂。
