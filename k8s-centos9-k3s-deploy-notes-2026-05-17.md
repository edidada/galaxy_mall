# CentOS Stream 9 双机 K3s / Java 项目部署记录

日期：2026-05-17  
环境：

- `192.168.31.77`：`wdidada@192.168.31.77`，作为 `master`
- `192.168.31.211`：`wdidada@192.168.31.211`，作为 `worker1`
- 系统：CentOS Stream 9
- SSH / sudo 密码：同一套密码
- 目标：安装 `Kubernetes 1.30+`，部署当前 `galaxy_mall` 项目

本次实际采用的是 `k3s v1.35.4+k3s1`。它是轻量发行版，但底层仍然是标准 Kubernetes，版本满足 `1.30+` 要求。

---

## 1. K3s 安装过程

### 1.1 选型

CentOS Stream 9 上直接装标准 kubeadm 也可以，但这次为了尽快完成双机可运行集群，采用了 `k3s`。

优点：

- 安装快
- 内置 containerd
- 自带 ingress / metrics
- 后续加 worker 更简单

### 1.2 安装版本

安装版本：`v1.35.4+k3s1`

原因：

- 明确高于 `1.30`
- 是当时稳定通道可用版本

### 1.3 实际安装方式

一开始尝试让远端机器直接从公网下载 k3s 组件，但下载速度很慢，不稳定。最后改成：

1. 本地获取 k3s 安装脚本和二进制
2. 复制到远端
3. 在远端执行安装

这样比直接让两台 CentOS 机器自己拉取 GitHub 资源更稳。

### 1.4 节点角色与主机名

第一次安装后发现两台机器主机名冲突，导致节点识别不清晰。后来重装并显式指定：

- `192.168.31.77` -> `k3s-master`
- `192.168.31.211` -> `k3s-worker1`

这一步很关键，否则后续加节点、排障、看 Pod 调度都会很乱。

### 1.5 镜像加速配置

由于默认从 Docker Hub 拉镜像速度不稳定，在两台机器都写了：

- `/etc/rancher/k3s/registries.yaml`

使用的镜像加速源：

- `https://docker.m.daocloud.io`
- `https://docker.1ms.run`

写完后分别重启：

- `k3s`
- `k3s-agent`

### 1.6 集群状态

确认成功后的状态：

- `k3s-master`：`Ready`
- `k3s-worker1`：`Ready`

系统组件正常：

- `coredns`
- `metrics-server`
- `traefik`
- `local-path-provisioner`

---

## 2. 项目部署方式

### 2.1 代码同步到主节点

将当前仓库同步到主节点：

- 远端目录：`/home/wdidada/galaxy_mall`

### 2.2 主节点安装构建工具

在 `192.168.31.77` 上安装了以下工具：

- Java 17
- Maven
- Node / npm
- podman / buildah
- rsync
- git
- tar

### 2.3 前端构建

前端使用容器化 Node 20 构建，避免主机 Node 版本差异：

- 基础镜像：`docker.m.daocloud.io/library/node:20-alpine`

构建完成后，生成前端运行镜像：

- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/front:k3s`

### 2.4 后端构建

后端各模块在主节点打包，然后使用根目录 `Dockerfile` 构建镜像。

实际构建的业务镜像：

- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/gateway:k3s`
- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/user:k3s`
- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/product:k3s`
- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/stock:k3s`
- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/order:k3s`
- `registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/payment:k3s`

### 2.5 镜像分发方式

没有搭独立镜像仓库，而是采用更直接的双机导入方式：

1. 在主节点 `podman save` 打出 tar 包
2. 主节点执行 `k3s ctr images import`
3. 再把 tar 包复制到 worker
4. worker 执行 `k3s ctr images import`

这样可以避免现场再折腾 registry、证书、鉴权和网络策略。

---

## 3. K8s 资源部署

### 3.1 中间件

先部署中间件命名空间：

- namespace：`middleware`

已部署：

- MySQL
- Redis
- RocketMQ
- RocketMQ Dashboard
- Nacos

### 3.2 业务命名空间

业务命名空间：

- namespace：`galaxy-mall`

已部署：

- gateway
- user
- product
- stock
- order
- payment
- front

### 3.3 对外访问方式

使用了两种入口：

1. 前端 NodePort
2. Traefik ingress

关键入口：

- 前端：`http://192.168.31.77:30080/`
- 网关域名：`mall.192.168.31.77.nip.io`

---

## 4. 部署过程中遇到的问题与处理

### 问题 1：远端机器直接下载 k3s 太慢

现象：

- 从 GitHub 或默认源直接拉取 k3s 安装资源速度慢
- 安装过程不稳定

处理：

- 改为本地准备 k3s 二进制和安装脚本，再传到远端执行

结论：

- 在内网 / 家宽环境下，这种方式更稳

### 问题 2：两台机器主机名冲突

现象：

- 节点名不直观
- 不利于后续扩容和排障

处理：

- 重装 k3s
- 显式设置 node name

结果：

- `k3s-master`
- `k3s-worker1`

### 问题 3：镜像拉取慢

现象：

- 系统组件和业务镜像拉取都容易卡

处理：

- 在两台机器配置 `registries.yaml`
- 使用国内可用镜像加速源

结果：

- 系统组件和构建过程明显稳定很多

### 问题 4：Dubbo 没有可用注册中心

现象：

- gateway / order / payment 启动时报错
- 典型报错是：
  - `No such any registry to reference`

原因：

- 项目部署清单里没有把 Dubbo 需要的注册中心配置完整注入
- 原工程运行依赖 `Nacos`

处理：

1. 单独补部署 `Nacos`
2. 给相关 deployment 注入环境变量：
   - `DUBBO_REGISTRY_ADDRESS=nacos://nacos.middleware.svc.cluster.local:8848`
   - `NACOS_SERVER_ADDR=nacos.middleware.svc.cluster.local:8848`
   - `NACOS_DISCOVERY_ENABLED=true`
   - `NACOS_DISCOVERY_REGISTER_ENABLED=true`
3. 关闭默认 Nacos 鉴权变量：
   - `NACOS_USERNAME=`
   - `NACOS_PASSWORD=`

结果：

- Dubbo 服务注册和订阅恢复正常

### 问题 5：Dubbo tri 反序列化不兼容 Java `record`

现象：

- gateway 调产品接口时，曾报：
  - `Deserialize response failed`
  - `can't get field offset on a record class`

原因：

- `galaxy_mall-common` 里的跨服务 DTO 使用了 Java `record`
- 当前 Dubbo 3.3 tri + fastjson2 组合对这些 DTO 兼容性不稳定

处理：

把跨服务公共 DTO 从 `record` 改成普通 JavaBean 类，保留：

- 无参构造
- 全参构造
- 标准 getter / setter
- 兼容旧调用点的 `xxx()` 方法

本次实际修改的重点目录：

- `galaxy_mall-common/src/main/java/cn/wdidada/galaxymall/common/api/`

结果：

- Dubbo 消费端不再因 `record` 失败

### 问题 6：网关返回 `200`，但响应是断掉的

现象：

- `curl` 请求商品接口时：
  - HTTP 状态码是 `200`
  - 但连接被提前关闭
  - 看起来像 `text/event-stream`

原因：

- `ApiResponse` 从 `record` 改成普通类后，只保留了 `success() / message() / data()`
- Jackson 不认这套访问器，导致网关写 JSON 时抛：
  - `No serializer found for class ApiResponse`
- 因为响应头已经写出，所以表现成 `200` 但 body 不完整

处理：

给 `ApiResponse` 补标准 JavaBean getter：

- `isSuccess()`
- `getMessage()`
- `getData()`

结果：

- 商品接口恢复为标准 JSON 输出

### 问题 7：前端 nginx 只代理了 `/api/`

现象：

- 前端访问时，部分网关路径没有正确转发

处理：

修改 `front/nginx.conf`，补转发：

- `/edge/`
- `/actuator/`

结果：

- 前端访问链路更完整

### 问题 8：后端新镜像滚动更新时，部分新 Pod 启动较慢

现象：

- 新建 Pod 有一段时间 `0/1`
- readiness probe 曾出现连接拒绝

判断：

- 至少 `gateway` 新版本已经成功接管流量
- 主商品接口已经能稳定返回 JSON
- 但在网络中断前，部分业务 deployment 还处于滚动收敛过程中

说明：

- 这部分不是最早的 Dubbo / Jackson 主问题
- 现场网络中断前，主链路已经恢复

---

## 5. 本次实际代码改动

### 5.1 `front/nginx.conf`

新增代理路径：

- `/edge/`
- `/actuator/`

### 5.2 `ApiResponse.java`

从 `record` 改为普通类后，又补了 Jackson 需要的标准 getter。

### 5.3 `common/api` 下公共 DTO

将跨服务使用的一批 `record` DTO 改成兼容 Dubbo / Jackson 的普通 JavaBean。

---

## 6. 当时验证通过的结果

### 6.1 集群

- 两节点 `Ready`
- 系统 Pod 正常

### 6.2 网关健康检查

验证通过：

- `GET /actuator/health/readiness`

### 6.3 主商品接口

最终恢复为正常 JSON：

`GET /api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1`

当时返回示例：

```json
{
  "success": true,
  "message": "cache-or-db-hit",
  "data": {
    "productId": "sku-iphone",
    "name": "iPhone Galaxy Edition",
    "priceCent": 899900,
    "seckillProduct": false,
    "staticPage": "<html><body><h1>iPhone Galaxy Edition</h1><p>static page</p></body></html>"
  }
}
```

---

## 7. 下次继续时建议的顺序

如果后续要继续修复“网络不通”或把集群恢复到完全稳定状态，建议按下面顺序继续：

1. 先确认 `kubectl get pods -A -o wide`
2. 重点看 `galaxy-mall` 命名空间里是否还有新旧 Pod 并存
3. 对 `0/1 Running` 的新 Pod 看日志和 readiness probe
4. 检查各 deployment 当前实际使用的 image 是否正确
5. 必要时对未收敛的 deployment 单独 `rollout restart`
6. 最后再测：
   - 前端首页
   - `/api/gateway/products/sku-iphone`
   - 下单 / 支付链路

---

## 8. 后续增加第三台 worker 的做法

如果后面再增加一台 worker，推荐沿用本次方式：

1. 新机器安装 CentOS Stream 9
2. 配置相同镜像加速
3. 使用主节点生成的 join token 安装 `k3s-agent`
4. 指定清晰 node name，例如 `k3s-worker2`
5. 把当前业务镜像 tar 包导入新节点 containerd

这样比重新搭完整 registry 更省事。

---

## 9. 本次记录涉及的关键文件

- 本文档：`k8s-centos9-k3s-deploy-notes-2026-05-17.md`
- 前端代理配置：`front/nginx.conf`
- 公共 DTO：`galaxy_mall-common/src/main/java/cn/wdidada/galaxymall/common/api/`

