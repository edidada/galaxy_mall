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
