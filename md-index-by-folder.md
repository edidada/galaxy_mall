# Markdown 文档索引

范围：`/Users/ibqo/Develop/git/github/java/galaxy_mall`

说明：

- 按文件夹整理仓库内自有 `*.md`
- 不包含 `front/node_modules/**` 下三方依赖自带 README
- 优先把和部署、k3s、Rancher Desktop、压测相关的资料归到容易查找的位置

---

## 根目录

### 部署与运行

- [production_deployment.md](./production_deployment.md)
- [k8s-centos9-k3s-deploy-notes-2026-05-17.md](./k8s-centos9-k3s-deploy-notes-2026-05-17.md)
- [uat_test.md](./uat_test.md)
- [api.md](./api.md)

### 压测与性能

- [100w-tps-test-plan.md](./100w-tps-test-plan.md)
- [100w-tps-test-results.md](./100w-tps-test-results.md)
- [100w-tps-test-results-updated.md](./100w-tps-test-results-updated.md)
- [short-term-optimization-results.md](./short-term-optimization-results.md)
- [single_node_tps_report.md](./single_node_tps_report.md)
- [test_plan.md](./test_plan.md)

### 设计与评审

- [design.md](./design.md)
- [ds.md](./ds.md)
- [review_suggestions.md](./review_suggestions.md)
- [skills.md](./skills.md)
- [CLAUDE.md](./CLAUDE.md)
- [readme.md](./readme.md)

---

## `alibaba/`

- [alibaba/k3s-uat-deployment-plan.md](./alibaba/k3s-uat-deployment-plan.md)

用途：

- 阿里云 / UAT / k3s 部署规划

---

## `ci_cd/`

- [ci_cd/README.md](./ci_cd/README.md)

用途：

- CI/CD 总入口
- 包含 k3s、GitLab、Jenkins、业务发布流水线说明

---

## `deploy/k8s/`

### k3s / 业务部署

- [deploy/k8s/rocketmq-k3s.md](./deploy/k8s/rocketmq-k3s.md)
- [deploy/k8s/prometheus-grafana-k3s.md](./deploy/k8s/prometheus-grafana-k3s.md)

### 查看与排障

- [deploy/k8s/how-to-view-k3s-java-services.md](./deploy/k8s/how-to-view-k3s-java-services.md)
- [deploy/k8s/how-to-view-java-in-grafana.md](./deploy/k8s/how-to-view-java-in-grafana.md)

用途：

- 这里是最接近“如何把项目部署到 k3s”这一条路径的文档目录

---

## `dell_r630/`

- [dell_r630/galaxy-mall-seckill-perf-test-plan.md](./dell_r630/galaxy-mall-seckill-perf-test-plan.md)

用途：

- 特定服务器环境下的秒杀压测方案

---

## `front/`

- [front/README.md](./front/README.md)

用途：

- 前端工程说明
- 配合 `front/build-front.sh`、`front/deploy-k3s.sh` 使用

---

## 和 “这台 macOS 上用 Rancher Desktop / k3s 部署” 最相关的文档

如果你现在关注的是：

- 这台 Mac 上如何用 Rancher Desktop 起 Kubernetes
- 如何把这个 Maven 多模块项目打包成镜像
- 如何部署到 k3s

建议优先看这几个文件：

1. [ci_cd/README.md](./ci_cd/README.md)
2. [production_deployment.md](./production_deployment.md)
3. [deploy/k8s/rocketmq-k3s.md](./deploy/k8s/rocketmq-k3s.md)
4. [deploy/k8s/how-to-view-k3s-java-services.md](./deploy/k8s/how-to-view-k3s-java-services.md)
5. [k8s-centos9-k3s-deploy-notes-2026-05-17.md](./k8s-centos9-k3s-deploy-notes-2026-05-17.md)

---

## 可继续细分的方向

如果你后面要继续整理，我建议下一步按“主题”再拆一层，而不是只按目录：

- `部署类`
- `压测类`
- `架构设计类`
- `排障类`
- `前端类`

这样比单纯看文件夹更容易找资料。

