
# 查看 k3s 部署的 Java 服务信息

## 1. 查看当前运行的所有资源

```bash
kubectl get all -n default
```

## 2. 查看 Pod 详细信息

```bash
# 查看特定 Pod 的详情
kubectl describe pod &lt;pod-name&gt; -n default

# 查看所有 Java 服务的 Pod 信息
kubectl get pods -l app in (gateway,user,product,stock,order,payment,seckill) -o wide
```

## 3. 查看 Pod 日志

```bash
# 查看 Pod 日志
kubectl logs &lt;pod-name&gt; -n default

# 实时查看 Pod 日志
kubectl logs -f &lt;pod-name&gt; -n default

# 查看之前重启的 Pod 日志
kubectl logs &lt;pod-name&gt; --previous -n default
```

## 4. 查看 Deployment 配置

```bash
# 查看 Deployment 详情
kubectl describe deployment &lt;deployment-name&gt; -n default

# 查看 Deployment 的 YAML 配置
kubectl get deployment &lt;deployment-name&gt; -o yaml -n default
```

## 5. 查看 Service 配置

```bash
# 查看 Service 详情
kubectl describe service &lt;service-name&gt; -n default

# 查看 Service 的 YAML 配置
kubectl get service &lt;service-name&gt; -o yaml -n default
```

## 6. 查看 ConfigMap/Secret 配置

```bash
# 查看 ConfigMap
kubectl get configmap -n default
kubectl describe configmap &lt;configmap-name&gt; -n default
kubectl get configmap &lt;configmap-name&gt; -o yaml -n default

# 查看 Secret（注意：数据是 base64 编码的）
kubectl get secret -n default
kubectl describe secret &lt;secret-name&gt; -n default
kubectl get secret &lt;secret-name&gt; -o yaml -n default
```

## 7. 查看节点信息

```bash
kubectl get nodes -o wide
kubectl describe node &lt;node-name&gt;
```

## 8. 查看资源使用情况（Metrics Server）

```bash
# 需要先安装 Metrics Server
kubectl top pods -n default
kubectl top nodes
```

## 9. 进入 Pod 容器调试

```bash
# 进入 Pod 的容器
kubectl exec -it &lt;pod-name&gt; -- /bin/bash
# 或者
kubectl exec -it &lt;pod-name&gt; -- /bin/sh

# 查看 Java 进程
jps -l

# 查看 JVM 堆信息
jmap -heap &lt;pid&gt;

# 查看 GC 情况
jstat -gc &lt;pid&gt; 1000 10
```

## 10. 常用的 Galaxy Mall 服务

| 服务名 | 镜像 | 端口 |
| ------ | ----- | ---- |
| gateway | galaxy-mall-gateway | 8080 |
| user | galaxy-mall-user | 8080 |
| product | galaxy-mall-product | 8080 |
| stock | galaxy-mall-stock | 8080 |
| order | galaxy-mall-order | 8080 |
| payment | galaxy-mall-payment | 8080 |
| seckill | galaxy-mall-seckill | 8080 |

## 11. 常用命令示例

```bash
# 查看 gateway 日志
kubectl logs -f deployment/gateway -n default

# 查看 stock 为什么 CrashLoopBackOff
kubectl logs -f deployment/stock -n default

# 查看 order 的环境变量
kubectl exec deployment/order -- printenv | grep -E "(SERVICES|SPRING|REDIS|ROCKETMQ)"

# 查看 product 的 YAML 配置
kubectl get deployment/product -o yaml -n default
```

