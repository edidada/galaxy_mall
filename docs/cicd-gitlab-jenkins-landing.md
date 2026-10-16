# GitLab / Jenkins CI/CD 落地手册（ci_cd 那套在 k3s 上真正跑起来）

> 编写日期：2026-10-17
> 目标：把 `ci_cd/` 里那套 `GitLab CE + Jenkins + Kubernetes Agent + Kaniko + kubectl` 真正落到现有 4 节点 k3s 集群上。
> 原则：**只写计划，不实操**。文中"实测"= 已采集的实机事实；`[待确认]` = 执行时必须现验。

---

## 0. 结论先行

**能落地，但不能直接 `./90_bootstrap_all.sh`**。当前有 4 个硬阻塞：

| # | 阻塞 | 现状 | 后果 |
|---|---|---|---|
| 1 | ~~Docker Hub 不可达~~（2026-10-17 已通过加速器 + hosts 绑 IPv4 解决） | `registry-1.docker.io` 直连超时，但经 `docker.1panel.live` 可拉 | 已解决：4 节点 `registries.yaml` + `/etc/hosts` IPv4；**残留风险**：加速器域名 IP 变动、带宽仅 ~250 KiB/s |
| 2 | gcr.io 不可达 | `gcr.io` 返回 000 | Kaniko executor 拉不到，流水线构建阶段直接失败 |
| 3 | PVC 申请量 130Gi，单节点可用磁盘仅 24G | postgresql 50Gi + redis 20Gi + minio 30Gi + Jenkins 30Gi | Pod 起来后写满磁盘 / PVC Pending |
| 4 | helm 未安装 + `raw.githubusercontent.com` 不可达 | `get-helm-3` 脚本拉不到 | `30_install_gitlab.sh` / `40_install_jenkins.sh` 直接报错 |

**建议路径**：先做「阶段 0 改造」→「阶段 1 节点准备」→ 再按 `20 → 30 → 40 → 50 → 60/70/80 → 95` 分步执行（**不要**一上来跑 `90_bootstrap_all.sh`，它串行且失败点难定位）。

**工期预期**：受带宽限制（实测加速器 ~235 KiB/s），GitLab 镜像总量数 GB，预计 **数小时**，建议先做「镜像预热」（见 4.5）。

---

## 1. 现状盘点

### 1.1 仓库里的 ci_cd 资产

| 文件 | 作用 | 备注 |
|---|---|---|
| `ci_cd/env.sh` | 全部可迁移参数 | `K3S_VERSION=v1.28.8+k3s1`、`GITLAB_CHART_VERSION=9.8.3`、`JENKINS_CHART_VERSION=5.8.110`；`REMOTE_SSH_TARGET` 已于 2026-10-17 改为 `wdidada@192.168.31.77`（见 3.1） |
| `00_prepare_host.sh` | 宿主机依赖/内核/防火墙 | 需 root |
| `10_install_k3s.sh` | 装 k3s + helm + 建 ns | 集群已存在，**跳过** |
| `20_install_gitlab_prereqs.sh` | 外部 PostgreSQL / Redis | 用 `templates/k8s/gitlab-*.yaml.tpl` |
| `30_install_gitlab.sh` | GitLab CE Helm | 用 `templates/gitlab-values.yaml.tpl` |
| `40_install_jenkins.sh` | Jenkins Helm + JCasC + Job DSL | 用 `templates/jenkins-values.yaml.tpl` |
| `50_bootstrap_gitlab_project.sh` | 建组/项目/Token/Webhook + push | 依赖 toolbox Pod |
| `60_install_business_dependencies.sh` | mysql/redis/rocketmq | 集群已有 middleware，**跳过** |
| `70_render_business_manifest.sh` | 渲染业务清单 | **缺 seckill** |
| `80_apply_business_stack.sh` | 发布业务栈 | 依赖 `lib.sh` 的 kubectl |
| `90_bootstrap_all.sh` | 串行一键 | 含 00/10/60，需裁剪 |
| `95_smoke_test.sh` | 验收 | |
| `pipeline/Jenkinsfile` | 流水线：maven → kaniko → kubectl | agent 镜像 maven/kaniko/bitnami-kubectl |

### 1.2 目标集群（实测）

| 项 | 值 |
|---|---|
| k3s | `v1.35.4+k3s1`（**与 env.sh 的 v1.28.8 不一致，但 k3s 已装，不影响**） |
| 节点 | master `.77`(4C/8.9G)、worker1 `.211`(4C/7.9G)、worker2 `.251`(4C/10G)、worker3 `.88`(4C/10G) |
| master 磁盘 | 38G 总量 / **24G 可用** |
| master 内存 | 8G 总量 / 4G 可用 |
| StorageClass | `local-path`（PVC 落在 Pod 所在节点的本地盘） |
| Ingress | Traefik；LB 外部 IP **4 个节点全生效**（`.77/.211/.251/.88`，2026-10-17 已修复） |
| helm | **未安装** |
| gitlab / jenkins / middleware 命名空间 | **均不存在**（即 ci_cd 从未执行过） |
| 镜像仓库 | `registry.192.168.31.77.nip.io` 服务已不存在（旧镜像靠 `IfNotPresent` 存活） |

### 1.3 网络与镜像可达性（实测，从 master 发起）

| 目标 | 结果 | 结论 |
|---|---|---|
| `charts.gitlab.io` | 200 | Helm repo 可用 |
| `charts.jenkins.io` | 200 | Helm repo 可用 |
| `mirrors.huaweicloud.com/helm/` | 200 | **helm 二进制从这里下** |
| `github.com` | 200 | 仓库可用 |
| `raw.githubusercontent.com` | 000 | `get-helm-3` 不可用 |
| `registry-1.docker.io` / `auth.docker.io` | 000 | **Docker Hub 直连不可用** |
| `gcr.io` | 000 | **不可用** |
| `quay.io` / `ghcr.io` / `registry.gitlab.com` | 401 | 可达（401 = 需鉴权，服务在） |
| `docker.m.daocloud.io` | 401（根路径）/ 拉取失败 | 未验证成功 |
| `docker.1panel.live` | busybox manifest 200 | **可用加速器** |
| 同上：`jenkins/jenkins:2.541.1-jdk21` | 200 | ✅ |
| 同上：`library/maven:3.9.9-eclipse-temurin-17` | 200 | ✅ |
| 同上：`jenkins/inbound-agent:3345.v03dee9b_f88fc-6` | 200 | ✅ |
| 同上：`bitnami/kubectl:1.28 / 1.28.4 / 1.30 / 1.31 / latest` | 400 / 404 | ❌ Bitnami 旧标签已下线 |
| `gcr.m.daocloud.io` | 401（可达） | gcr 镜像候选，`[待确认]` |
| Mac 代理（7890/1087/8888 等） | 000 | 节点无法借道 Mac 代理 |

**决定性验证**：已用 containerd 的 `--hosts-dir` 机制，通过 `docker.1panel.live` 成功拉取 `docker.io/library/busybox:latest`（9.3s，2.1Mi，**~235 KiB/s**）。→ **加速器方案可行，但带宽很低**。

---

## 2. 硬阻塞与对策

| 阻塞 | 对策 |
|---|---|
| Docker Hub 不可达 | 4 台节点配置 `registries.yaml`：`docker.io → https://docker.1panel.live` |
| gcr.io 不可达 | 同上：`gcr.io → https://gcr.m.daocloud.io`（`[待确认]`）；不生效就改 Jenkinsfile 用 buildah（quay.io 可达） |
| PVC 130Gi | 全部减配（见 3.3 / 3.4 / 3.5），或先在 Proxmox 给 VM 扩盘 |
| helm 缺失 | 从 `mirrors.huaweicloud.com/helm/` 下载二进制 |
| 带宽 ~235 KiB/s | 提前预热镜像（在能联网的机器 pull → save → `ctr import`） |
| `svclb-traefik` 拉不到镜像 | 配好加速器后 `kubectl -n kube-system delete pod -l app=svclb-traefik` 重建 |

---

## 3. 阶段 0：必须改的代码与配置（先改，再执行）

> 全部改动在仓库内完成，然后 `ci_cd/scripts/sync_to_remote.sh` 同步到 master。

### 3.1 ✅ 已完成（2026-10-17）：`ci_cd/env.sh`

```diff
- export REMOTE_SSH_TARGET="${REMOTE_SSH_TARGET:-wdidada@192.168.31.171}"
+ export REMOTE_SSH_TARGET="${REMOTE_SSH_TARGET:-wdidada@192.168.31.77}"
```

已实际改到仓库里（`.171` 是 Proxmox 宿主，k3s master 是 `.77`）。

（`.171` 是 Proxmox 宿主，k3s master 是 `.77`；`K3S_NODE_IP` 由 `hostname -I` 自动探测，在 master 上会得到 `.77`，无需改。）

### 3.2 `ci_cd/scripts/lib.sh` —— **Jenkins agent 里没有 `k3s` 命令**

`env.sh` 定义了 `KUBECTL_BIN`，但 `lib.sh` 的 `kubectl_cmd()` 硬编码了 `/usr/local/bin/k3s kubectl`。Jenkins 的 kubectl 容器里没有 k3s，`80_apply_business_stack.sh` 必然失败。

```diff
 kubectl_cmd() {
-  sudo_run /usr/local/bin/k3s kubectl "$@"
+  if [[ -n "${KUBECTL_BIN:-}" && -x "${KUBECTL_BIN}" ]]; then
+    "${KUBECTL_BIN}" "$@"
+  else
+    sudo_run /usr/local/bin/k3s kubectl "$@"
+  fi
 }
```

同时在 Jenkins 的 kubectl 容器里用 ServiceAccount 生成 kubeconfig（流水线内），见 3.6。

### 3.3 `ci_cd/templates/k8s/gitlab-postgresql.yaml.tpl` —— PVC 50Gi 太大

```diff
-          storage: 50Gi
+          storage: 8Gi
```

### 3.4 `ci_cd/templates/k8s/gitlab-redis.yaml.tpl`

```diff
-          storage: 20Gi
+          storage: 2Gi
```

### 3.5 `ci_cd/templates/gitlab-values.yaml.tpl` —— 裁剪 + 限资源

现状：`minio.persistence.size: 30Gi`、无 resources、gitaly/registry 走 chart 默认（默认同样偏大）。

```diff
 minio:
   persistence:
-    size: 30Gi
+    size: 8Gi
+
+gitlab:
+  webservice:
+    minReplicas: 1
+    resources:
+      requests: { cpu: 500m, memory: 2Gi }
+      limits:   { cpu: "2",   memory: 3Gi }
+  sidekiq:
+    minReplicas: 1
+    resources:
+      requests: { cpu: 300m, memory: 1Gi }
+      limits:   { cpu: "1",  memory: 2Gi }
+  gitaly:
+    persistence:
+      size: 10Gi
+  gitlab-shell:
+    minReplicas: 1
+    service:
+      type: NodePort
+      nodePort: 32022
+  toolbox:
+    backups:
+      cron:
+        enabled: false
+
+gitlab-pages:
+  enabled: false
+
+registry:
+  enabled: true
```

> `gitlab.kas.enabled: false`、`prometheus/gitlab-runner/nginx-ingress/postgresql/redis` 已关闭，保持。
> `[待确认]`：GitLab chart 9.8.3 官方支持的 k8s 版本区间不含 1.35。若 `helm install` 报 API/模板错误，**升 chart 版本**（`helm search repo gitlab/gitlab --versions` 选最新 9.x）而不是降 k3s。

### 3.6 `ci_cd/templates/jenkins-values.yaml.tpl`

```diff
 persistence:
   enabled: true
-  size: 30Gi
+  size: 8Gi
+controller:
+  resources:
+    requests: { cpu: 500m, memory: 1Gi }
+    limits:   { cpu: "2",  memory: 2Gi }
```

（`controller.image` = `jenkins/jenkins:2.541.1-jdk21`、`agent.image` = `jenkins/inbound-agent:3345...` 实测均可通过加速器拉取，保持不变。）

> `[待确认]`：Jenkins 首次启动会下载 `installPlugins` 里的插件，源为 `updates.jenkins.io`。该域名**未实测**。若不可达，在 values 里加：
> ```yaml
> controller:
>   initContainerEnv: []
>   # 方案：改用清华/华为 Jenkins 插件镜像，或用离线插件包挂 PVC
> ```

### 3.7 `ci_cd/pipeline/Jenkinsfile` —— 两处必须改

**① kubectl 镜像不可用**

```diff
-    - name: kubectl
-      image: bitnami/kubectl:1.28
+    - name: kubectl
+      image: bitnamilegacy/kubectl:1.28.4     # [待确认] 执行前先 ctr pull 验证
```

备选（按优先级）：
1. `bitnamilegacy/kubectl:1.28.4`
2. 用 `maven` 容器 + 在流水线里安装 kubectl 二进制（走国内镜像站下载）
3. 直接用 `quay.io` 上的 kubectl 镜像（quay.io 实测可达）

**② agent 内需要 kubeconfig（没有 k3s、没有 kubeconfig 文件）**

在 `stage('Deploy')` 的 `container('kubectl')` 里先生成：

```groovy
sh '''
  set -euo pipefail
  SA=/var/run/secrets/kubernetes.io/serviceaccount
  export KUBECONFIG=/tmp/kubeconfig
  kubectl config set-cluster k3s \
    --server=https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT} \
    --certificate-authority=${SA}/ca.crt
  kubectl config set-credentials sa --token=$(cat ${SA}/token)
  kubectl config set-context k3s --cluster=k3s --user=sa --namespace=galaxy-mall
  kubectl config use-context k3s

  export KUBECTL_BIN=$(command -v kubectl)
  chmod +x ci_cd/scripts/*.sh
  . ./ci_cd/env.sh
  APP_IMAGE_TAG="${IMAGE_TAG}" ./ci_cd/scripts/80_apply_business_stack.sh "${IMAGE_TAG}"
'''
```

前提：Jenkins 的 ServiceAccount 有权限 —— `40_install_jenkins.sh` 已创建 `jenkins-cluster-admin`（cluster-admin），且 Jenkinsfile 里 `serviceAccountName: jenkins` 与 release 名一致，OK。

**③ Kaniko（gcr.io）**

优先靠 `registries.yaml` 的 `gcr.io` mirror 生效；若无效，二选一：

```diff
-      image: gcr.io/kaniko-project/executor:v1.23.2-debug
+      image: gcr.m.daocloud.io/kaniko-project/executor:v1.23.2-debug   # 方案A
+      # 或 方案B：quay.io/containers/buildah + buildah bud/push
```

### 3.8 `ci_cd/scripts/70_render_business_manifest.sh` —— 补 seckill

该文件基于 `deploy/k8s/galaxy-mall-uat.yaml` 渲染，只含 6 个服务。seckill 走独立清单（`deploy/k8s/seckill.yaml`，见《k3s 部署计划》1.4），在 `80_apply_business_stack.sh` 末尾追加：

```diff
 kubectl_cmd apply -f "${RENDER_DIR}/galaxy-mall.yaml"
+kubectl_cmd apply -f "${REPO_ROOT}/deploy/k8s/seckill.yaml"
 wait_for_pods "${APP_NAMESPACE}"
```

### 3.9 裁剪 `90_bootstrap_all.sh`

k3s 与 middleware 已存在，直接一键会重装/覆盖。改为分步执行（第 4~9 章），或把 00/10/60 从串行脚本里去掉。

---

## 4. 阶段 1：节点与集群准备（4 台节点都要做）

### 4.1 ✅ 已完成（2026-10-17）：配置 containerd 镜像加速（核心）

> 状态：4 台节点 `/etc/rancher/k3s/registries.yaml` 已写入并重启生效，kubelet 拉取 docker.io 镜像已走加速器（实测重建 svclb Pod 成功拉取 `rancher/klipper-lb:v0.4.15`）。
> **额外必做项（踩坑）**：仅配 `registries.yaml` 不够，还需在 `/etc/hosts` 绑定加速器 IPv4，原因见下文「坑①」。

`/etc/rancher/k3s/registries.yaml`：

```yaml
mirrors:
  docker.io:
    endpoint:
      - "https://docker.1panel.live"
  gcr.io:
    endpoint:
      - "https://gcr.m.daocloud.io"
  ghcr.io:
    endpoint:
      - "https://ghcr.m.daocloud.io"
  quay.io:
    endpoint:
      - "https://quay.m.daocloud.io"
  "registry.192.168.31.77.nip.io":
    endpoint:
      - "http://registry.192.168.31.77.nip.io"
configs:
  "registry.192.168.31.77.nip.io":
    tls:
      insecure_skip_verify: true
```

> 最后一段是**必须的**：GitLab Registry 走 HTTP（模板里 `global.hosts.https: false`），containerd 默认拒绝明文私有仓库，会在推送/拉取时报 `http: server gave HTTP response to HTTPS client`。

```bash
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S tee /etc/rancher/k3s/registries.yaml" < registries.yaml
done
# master
ssh wdidada@192.168.31.77 "echo '<sudo密码>' | sudo -S systemctl restart k3s"
# workers
for ip in 192.168.31.211 192.168.31.251 192.168.31.88; do
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S systemctl restart k3s-agent"
done
```

**坑① 只写 `registries.yaml` 不够：加速器域名只有 AAAA 记录**

`docker.1panel.live` 解析出的全是 IPv6，节点 IPv6 出网不通；containerd（Go）优先 IPv6 → mirror 超时 → fallback 到 `registry-1.docker.io` → 也超时。

```bash
curl -4 -m 10 https://docker.1panel.live/v2/library/busybox/manifests/latest   # 200
curl -6 -m 10 https://docker.1panel.live/v2/library/busybox/manifests/latest   # 000
```

解决（4 台节点都要）：

```bash
echo "104.26.2.138 104.26.3.138 172.67.69.152 docker.1panel.live" | sudo tee -a /etc/hosts
```

**坑② `k3s ctr` 不能用来验证加速是否生效**

`ctr` 是客户端解析 registry hosts，读的是默认 `/etc/containerd/certs.d`，**不读 k3s 的 `config_path`**。所以：

```bash
k3s ctr images pull docker.io/library/busybox:latest                       # 一直超时（假阴性）
k3s ctr images pull --hosts-dir /var/lib/rancher/k3s/agent/etc/containerd/certs.d docker.io/library/busybox:latest   # 成功
```

→ **验证必须让 kubelet 真去拉**：删一个 Pod 触发重建，看是否 `Running`。

### 4.2 域名解析：别赌 nip.io

`50_bootstrap_gitlab_project.sh`、`95_smoke_test.sh` 都在节点上用 `http://gitlab.<IP>.nip.io` 访问；集群内 Pod 解析也依赖上游 DNS。nip.io 走公网 DNS，**未实测节点侧解析**。稳妥做法：4 台节点 `/etc/hosts` 加：

```text
192.168.31.77 gitlab.192.168.31.77.nip.io registry.192.168.31.77.nip.io jenkins.192.168.31.77.nip.io mall.192.168.31.77.nip.io
```

Mac 侧同样加（访问用）；CoreDNS 默认把未知域转发到节点 `/etc/resolv.conf`，`[待确认]` 是否继承 hosts —— 若不继承，需要在 CoreDNS 配 `hosts` 插件或改用集群内的 `sslip.io`/自建 dnsmasq。

### 4.3 ✅ 已完成（2026-10-17）：安装 helm

实际执行（版本选 **v3.22.1**，华为云镜像目录里的最新稳定版；集群是 k8s v1.35，用较新的 helm 匹配度更好）：

```bash
curl -fsSL -o /tmp/helm.tgz https://mirrors.huaweicloud.com/helm/v3.22.1/helm-v3.22.1-linux-amd64.tar.gz
tar -xzf /tmp/helm.tgz -C /tmp
echo '<sudo密码>' | sudo -S install -m755 /tmp/linux-amd64/helm /usr/local/bin/helm
helm version        # v3.22.1

helm repo add gitlab  https://charts.gitlab.io/
helm repo add jenkins https://charts.jenkins.io
helm repo update     # 两个 repo 均 Successfully got an update
```

**坑④ helm 报 `Kubernetes cluster unreachable: Get "http://localhost:8080/version"`**

k3s 会把 `/usr/local/bin/kubectl` 做成本地 `k3s` 的符号链接，它默认读 `/etc/rancher/k3s/k3s.yaml`，所以 `kubectl` 一直是好的；但 `~/.kube/config` **并不存在**（`~/.kube/` 下只有 `cache/`），标准 helm 客户端找不到配置就退化到 `localhost:8080`。

```bash
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $USER:$USER ~/.kube/config && chmod 600 ~/.kube/config
helm list -A     # 能看到 traefik / traefik-crd 两个 release 即正常
```

> Jenkins 的 kubectl 容器里同样没有 kubeconfig，这也是 3.7 ② 必须生成 kubeconfig 的原因。

**Chart 版本确认（2026-10-17）**：

| Chart | 锁定版本 | 仓库中存在 | 备注 |
|---|---|---|---|
| `gitlab/gitlab` | 9.8.3 | ✅（app v18.8.3） | 仓库最新为 10.5.0（v19.5.0）；若 9.8.3 在 k8s 1.35 上报错就升到 9.10.x |
| `jenkins/jenkins` | 5.8.110 | ✅（app 2.528.2） | values 里已显式指定 `jenkins/jenkins:2.541.1-jdk21`，与 chart 默认 appVersion 不同，属正常覆盖；仓库最新 5.8.142 |

### 4.4 ✅ 已完成（2026-10-17）：Traefik LB 已覆盖全部 4 个节点

> 当前状态：`svc traefik` EXTERNAL-IP = `192.168.31.211,192.168.31.251,192.168.31.77,192.168.31.88`；四节点 `:80` 验收全 200。
> 以下内容保留为复盘 + 扩容新 worker 时的操作手册。

**为什么必须修**：GitLab / Jenkins / mall 三个 Ingress 都要通过 Traefik 的 LoadBalancer 访问。k3s 用 **klipper-lb（`svclb-traefik` DaemonSet）** 在每个节点开 `:80/:443`；现在 `.251`、`.88` 上的 Pod 是 `ImagePullBackOff`（klipper-lb 在 docker.io，这两台新 worker 拉不到）→ EXTERNAL-IP 只有 `.77`、`.211` 两个，**新加入的节点等于白加了入口能力**。

> 注意区分：**NodePort 不受影响**（kube-proxy 每节点开端口，已实测 30080/30808 四节点全通）；只有 Ingress/LoadBalancer 依赖 svclb。

**修复步骤**（前置：完成 4.1 的 `registries.yaml` 并重启 k3s/k3s-agent）：

> **坑③ svclb 的 label 带 hash**：真实 label 是 `app: svclb-traefik-9a5a38ea`（随 Service 变化），`-l app=svclb-traefik` 匹配不到。稳定写法用 `svccontroller.k3s.cattle.io/svcname`。

```bash
# 1) 现状与卡住的镜像
kubectl -n kube-system get pod -l svccontroller.k3s.cattle.io/svcname=traefik -o wide

# 2) 重建（DaemonSet 立即按新拉取配置重建；只删失败的即可）
kubectl -n kube-system delete pod -l svccontroller.k3s.cattle.io/svcname=traefik

# 3) 等就绪
kubectl -n kube-system wait --for=condition=Ready pod -l svccontroller.k3s.cattle.io/svcname=traefik --timeout=10m

# 4) 验证：4 个 Pod Running + EXTERNAL-IP 4 个
kubectl -n kube-system get pod -l svccontroller.k3s.cattle.io/svcname=traefik -o wide
kubectl -n kube-system get svc traefik
```

期望：

```text
NAME      TYPE           CLUSTER-IP     EXTERNAL-IP                                                 PORT(S)
traefik   LoadBalancer   10.43.x.x      192.168.31.77,192.168.31.88,192.168.31.211,192.168.31.251   80:31667/TCP,443:30261/TCP
```

四节点入口验收：

```bash
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  printf "%s -> " "$ip"
  curl -s -o /dev/null -w "%{http_code}\n" -m 5 \
    -H "Host: mall.192.168.31.77.nip.io" \
    "http://$ip/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
done
# 期望 4 行全 200（修复前 .251/.88 为 000）
```

**兜底：加速器不可用就离线导入 klipper-lb**

```bash
kubectl -n kube-system get pod -l svccontroller.k3s.cattle.io/svcname=traefik \
  -o jsonpath='{.items[0].status.containerStatuses[0].image}'   # 实测为 docker.io/rancher/klipper-lb:v0.4.15
docker pull rancher/klipper-lb:v0.4.15 && docker save -o /tmp/klipper-lb.tar rancher/klipper-lb:v0.4.15
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  scp /tmp/klipper-lb.tar wdidada@$ip:/tmp/
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S /usr/local/bin/k3s ctr images import /tmp/klipper-lb.tar"
done
kubectl -n kube-system delete pod -l svccontroller.k3s.cattle.io/svcname=traefik
```

**实际执行结果（2026-10-17）**

```text
svclb-traefik-9a5a38ea-brzsd  2/2 Running  k3s-worker1
svclb-traefik-9a5a38ea-ft7g7  2/2 Running  k3s-master
svclb-traefik-9a5a38ea-khmgs  2/2 Running  k3s-worker3   ← 修复后
svclb-traefik-9a5a38ea-t6k87  2/2 Running  k3s-worker2   ← 修复后

traefik  LoadBalancer  10.43.143.84  192.168.31.211,192.168.31.251,192.168.31.77,192.168.31.88  80:31667/TCP,443:30261/TCP

# 四节点验收（Mac 上）
192.168.31.77  :80 -> 200
192.168.31.211 :80 -> 200
192.168.31.251 :80 -> 200
192.168.31.88  :80 -> 200
```

> `svclb-traefik` 是 DaemonSet → **每次扩容 worker 后都要复查本节**：`registries.yaml` 需在新节点就位（建议写进 cloud-init 模板），`/etc/hosts` 的加速器 IPv4 绑定同样要同步。
> Traefik Service **无** `nodeSelector` / `externalTrafficPolicy` 限制（已确认）。
> 重启 `k3s` / `k3s-agent` 不会重建已有容器——本次修复过程中业务 Pod（galaxy-mall、middleware）全程 Running，无中断。

### 4.5 镜像预热（强烈建议，绕过 235 KiB/s）

在能高速联网的机器（Mac / VM107 编译机）上：

```bash
docker pull jenkins/jenkins:2.541.1-jdk21
docker pull jenkins/inbound-agent:3345.v03dee9b_f88fc-6
docker pull maven:3.9.9-eclipse-temurin-17
docker pull postgres:16
docker pull redis:7.2-alpine
docker save -o /tmp/prewarm.tar <上述镜像列表>

for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  scp /tmp/prewarm.tar wdidada@$ip:/tmp/
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S /usr/local/bin/k3s ctr images import /tmp/prewarm.tar"
done
```

> GitLab 的镜像（`registry.gitlab.com/gitlab-org/...`）实测可达，但总量大；建议先只预热 Jenkins 部分，GitLab 让节点自己拉，或同样离线导入（`[待确认]` 具体镜像清单：先 `helm template` 后 `grep image:` 提取）。

### 4.6 磁盘与内存检查（执行前必做）

```bash
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  ssh wdidada@$ip "df -h / | tail -1; free -g | head -2"
done
```

已知 master 只有 24G 可用 → **PVC 必须按 3.3/3.4/3.5 减配**；若仍紧张，先在 Proxmox 执行 `qm resize <VMID> scsi0 +50G` 再扩分区。

---

## 5. 阶段 2：GitLab

```bash
# master 上
cd /home/wdidada/galaxy_mall
./ci_cd/scripts/20_install_gitlab_prereqs.sh    # postgres:16 + redis:7.2-alpine
kubectl -n gitlab get pvc,pods
./ci_cd/scripts/30_install_gitlab.sh            # --timeout 40m，长耗时
kubectl -n gitlab get ingress
```

预期：

- `gitlab-postgresql`、`gitlab-redis` Running
- `gitlab-webservice-default`、`gitlab-sidekiq-all-in-1-v2`、`gitlab-gitaly-0`、`gitlab-gitlab-shell`、`gitlab-toolbox`、`gitlab-registry`、`gitlab-minio` 陆续 Ready
- Ingress host `gitlab.192.168.31.77.nip.io`、registry `registry.192.168.31.77.nip.io`，ADDRESS 应为 **4 个节点 IP**（完成 4.4 后）；未修复时只有 `.77/.211`

失败排查：

```bash
kubectl -n gitlab get pod | grep -v Running
kubectl -n gitlab describe pod <pod>          # 看 Events：ImagePullBackOff / FailedScheduling / PVC 问题
kubectl -n gitlab get pvc                     # Pending → 磁盘或 SC 问题
```

---

## 6. 阶段 3：Jenkins

```bash
./ci_cd/scripts/40_install_jenkins.sh
kubectl -n jenkins get pod,svc,ingress
kubectl logs -n jenkins -l app.kubernetes.io/name=jenkins -f    # 看插件下载是否卡住
```

预期：StatefulSet `jenkins` Ready，Ingress `jenkins.192.168.31.77.nip.io`，JCasC 自动创建 `galaxy-mall-delivery` 任务，并注入 `gitlab-http-credentials` / `gitlab-registry-credentials`。

风险点：`installPlugins` 需联网下载（`[待确认]` `updates.jenkins.io` 可达性）；插件下载失败时 Jenkins 会反复重启 → 按 3.6 换插件源或离线包。

---

## 7. 阶段 4：项目 / Token / Webhook

```bash
./ci_cd/scripts/50_bootstrap_gitlab_project.sh
```

它做的事：等 `/-/readiness` → 用 toolbox 创建固定 PAT（`glpat-bootstrap-2026-ChangeMe`）→ 建组 `galaxy-mall` → 建项目 → 加 `local-gitlab` remote 并 push → 注册 Webhook 到 `http://jenkins.*.nip.io/generic-webhook-trigger/invoke?token=...`。

风险点：

1. `GITLAB_ROOT_TOKEN` 固定值可能被 GitLab 的 token 复杂度/检测策略拒绝 → 若失败，改为 API 随机生成后回写 `env.sh`。
2. 脚本要求 `python` 命令存在（用于解析 JSON）；CentOS Stream 9 默认只有 `python3` → 需 `sudo ln -s /usr/bin/python3 /usr/bin/python` 或改脚本用 `python3`。
3. 脚本用 `curl http://${GITLAB_HOST}/-/readiness` → 依赖 4.2 的解析。

---

## 8. 阶段 5：业务依赖与业务栈

```bash
# 60 跳过（middleware 已存在 mysql/redis/rocketmq）
./ci_cd/scripts/70_render_business_manifest.sh k3s
./ci_cd/scripts/80_apply_business_stack.sh k3s        # 含 seckill（3.8 改动后）
```

注意：渲染出的镜像地址为 `${APP_IMAGE_REGISTRY}/${APP_IMAGE_REPOSITORY}/<svc>:k3s` = `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/<svc>:k3s`。**这套镜像当前在仓库里并不存在**（GitLab Registry 是新建的），所以第一次执行会 `ErrImagePull`——这是预期行为：正确顺序是 **先 push 代码触发 Jenkins 构建出镜像**，再由流水线自己 `80_apply_business_stack`。手工执行 80 只在镜像已存在时才有意义。

---

## 9. 阶段 6：验收（`95_smoke_test.sh` + 手工）

```bash
./ci_cd/scripts/95_smoke_test.sh
```

手工清单：

- [ ] `kubectl get pods -n gitlab` 全部 Running（无 ImagePullBackOff）
- [ ] `kubectl get pods -n jenkins` Running，Jenkins 登录 `http://jenkins.192.168.31.77.nip.io`（admin / ChangeMe_2026!）
- [ ] `kubectl get pods -n galaxy-mall` 含 `galaxy-mall-seckill`
- [ ] `curl http://gitlab.192.168.31.77.nip.io/-/readiness` 返回 healthy
- [ ] Jenkins 里 `galaxy-mall-delivery` 存在且能手动构建成功（含 maven 构建 + kaniko/buildah 推镜像 + kubectl 部署）
- [ ] `git push` 到 GitLab 默认分支能触发 Jenkins（Webhook 通）
- [ ] `curl -H "Host: mall.192.168.31.77.nip.io" http://192.168.31.77/edge/seckill/requests -X POST ...` 返回成功

---

## 10. 带宽与耗时预算

| 环节 | 估算 | 说明 |
|---|---|---|
| helm 二进制 | <1 min | 华为云镜像，快（18MB，实测秒下） |
| Jenkins 镜像（~500MB） | 30~40 min | 235 KiB/s；预热后 <5 min |
| GitLab 组件镜像（数 GB） | **数小时** | 强烈建议离线导入或分批拉 |
| GitLab 初始化（migrations + seed） | 10~20 min | `--timeout 40m` 可能不够，失败就 `--timeout 90m` 重跑 |
| Jenkins 插件下载 | 5~15 min | 依赖 `updates.jenkins.io` 可达性 |

> 带宽是本方案最大的不确定性。若 `docker.1panel.live` 后续限速/失效，换成其它可用加速器即可（`registries.yaml` 里可并列多个 endpoint，containerd 会依次尝试）。

## 11. 资源预算（减配后）

| 组件 | CPU req | 内存 req | PVC |
|---|---|---|---|
| gitlab-postgresql | — | ~200Mi | 8Gi |
| gitlab-redis | — | ~100Mi | 2Gi |
| gitlab-gitaly | — | ~500Mi | 10Gi |
| gitlab-webservice ×1 | 500m | 2Gi | — |
| gitlab-sidekiq ×1 | 300m | 1Gi | — |
| gitlab-shell / registry / minio / toolbox | ~500m | ~1Gi | minio 8Gi |
| jenkins | 500m | 1Gi | 8Gi |
| **合计** | ~2C | **~6Gi** | **~36Gi** |

集群 16C / ~37Gi，理论够；但**单节点最大只有 10Gi 内存**，webservice 需 2~3Gi，必须调度到空闲节点；磁盘 36Gi > master 的 24G 可用 → **扩盘或把 Pod 钉到空盘节点**（`local-path` 落在 Pod 所在节点）。

## 12. 回滚

```bash
helm -n gitlab  uninstall gitlab
helm -n jenkins uninstall jenkins
kubectl delete ns gitlab jenkins
kubectl delete clusterrolebinding jenkins-cluster-admin
# 清理残留 PVC（local-path 目录 /var/lib/rancher/k3s/storage/）
kubectl -n gitlab get pvc   # 已随 ns 删除则无需手工清理
```

业务栈不受影响（在 `galaxy-mall` 命名空间，且 80 未执行成功时不会改动）。

## 13. 执行时必须现验的清单（`[待确认]`）

1. ~~`docker.1panel.live` 加速器在每台节点都能拉~~（已验证：4 节点 kubelet 拉取成功；注意 `ctr` 需用 `--hosts-dir` 才能复现）
2. `gcr.m.daocloud.io` 能否拉 kaniko；不能则切 buildah（quay.io）
3. `bitnamilegacy/kubectl:1.28.4` 是否存在且可拉
4. `updates.jenkins.io` 是否可达（Jenkins 插件）
5. 各节点磁盘余量（已知 master 24G，其余未查）与内存余量
6. 节点侧能否解析 `*.nip.io`（不能则走 /etc/hosts + CoreDNS hosts 插件）
7. GitLab chart 9.8.3 与 k3s v1.35 的兼容性（不行就升 chart）
8. `python` 命令是否存在（50 脚本依赖）
9. GitLab 固定 PAT 是否被策略拒绝
10. Prometheus/Grafana（`registry.k8s.io` 是否可达）—— 属《k3s 部署计划》第 2 章
11. ~~klipper-lb 的实际镜像 tag~~（已确认：`docker.io/rancher/klipper-lb:v0.4.15`）
12. ~~Traefik Service 是否有 `nodeSelector` / `externalTrafficPolicy` 限制~~（已确认：无）
13. 各节点磁盘余量：master 24G、worker1 23G、worker2/work3 各 29G（2026-10-17 实测）→ PVC 仍需按 3.3/3.4/3.5 减配

## 14. 推荐执行顺序（命令汇总）

```bash
# ① 仓库改造（Mac 上）
#    按第 3 章改 env.sh / lib.sh / 3 个 tpl / Jenkinsfile / 80 脚本 / 90 脚本
./ci_cd/scripts/sync_to_remote.sh

# ② 节点准备（4 台）—— 已完成：registries.yaml、hosts 绑 IPv4、4.4 svclb 修复、4.3 helm 安装
#    待办：镜像预热（4.5）+ nip.io 解析（4.2）

# ③ 分步执行（master 上，每步确认后再下一步）
./ci_cd/scripts/20_install_gitlab_prereqs.sh
./ci_cd/scripts/30_install_gitlab.sh
./ci_cd/scripts/40_install_jenkins.sh
./ci_cd/scripts/50_bootstrap_gitlab_project.sh

# ④ 触发流水线：push 代码 → Jenkins 构建镜像 → 80 部署（含 seckill）
#    （不要手工跑 60/70/80，让流水线跑，避免镜像不存在导致 ErrImagePull）

# ⑤ 验收
./ci_cd/scripts/95_smoke_test.sh
```

---

## 附：踩坑速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `ErrImagePull` / `ImagePullBackOff`（docker.io 镜像） | Docker Hub 不可达 | 配 `registries.yaml` + 重启 k3s |
| `http: server gave HTTP response to HTTPS client` | 私有仓库是 HTTP | `configs."<host>".tls.insecure_skip_verify` + endpoint 写 `http://` |
| PVC 一直 Pending | 磁盘不足 / SC 绑定 | 减配 PVC 或扩盘 |
| `helm: command not found` | 未装 | 华为云镜像装（4.3） |
| `Kubernetes cluster unreachable: Get "http://localhost:8080/version"` | `~/.kube/config` 不存在（只有 k3s 的 kubectl 能读 `/etc/rancher/k3s/k3s.yaml`） | `sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config` + `chown`/`chmod 600`（4.3 坑④） |
| Jenkins 反复重启 | 插件下载失败 | 换插件源/离线包 |
| `sudo: k3s：找不到命令` | sudo 的 secure_path | 用 `/usr/local/bin/k3s` 全路径 |
| agent 里 kubectl 报 no configuration | 无 kubeconfig | 见 3.7 ② 用 SA 生成 kubeconfig |
| Ingress 只有部分节点通 | 该节点 `svclb-traefik` 拉不到 klipper-lb | 配加速器 + `/etc/hosts` 绑 IPv4（坑①）→ `kubectl -n kube-system delete pod -l svccontroller.k3s.cattle.io/svcname=traefik` → EXTERNAL-IP 补齐 |
