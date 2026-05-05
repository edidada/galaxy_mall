
# 在 Grafana 查看 Galaxy Mall Java 服务信息

## 0. 前置确认

### 确认 Prometheus 是否抓取到指标
1. 访问 Prometheus（可以临时 port-forward）：
   ```bash
   kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
   ```
2. 打开 `http://localhost:9090` 或 `http://192.168.31.171:30090`
3. 在 "Graph" 或 "Table" 视图下，搜索 `jvm_memory_used_bytes` 查看是否有指标数据

### 确认指标正常
可以在 Prometheus 中搜索以下指标确认：
- `jvm_memory_used_bytes`
- `jvm_threads_live_threads`
- `system_cpu_usage`
- `process_cpu_usage`

---

## 1. 在 Grafana 查看 Java 服务信息的步骤

### 1.1 登录 Grafana
- 地址：`http://192.168.31.171:30300`
- 用户名：`admin`
- 密码：`5Edidada`

### 1.2 进入 Dashboards
1. 点击左侧菜单的 **Dashboards**
2. 你会看到默认包含的 dashboard 列表，或者可以进入 **General** 文件夹

### 1.3 找到并使用 JVM (Micrometer) Dashboard
在 Dashboard 列表中找到：
- **JVM (Micrometer)** → 这个是专门看 Java JVM 指标的 dashboard！

点击进入该 dashboard，然后：
- 在 dashboard 顶部的下拉菜单中选择你要查看的应用（如 `gateway`、`user`、`product` 等）
- 你会看到 JVM 堆内存、非堆内存、线程、GC、CPU 等各种指标的图表

---

## 2. 其他常用 Dashboard

### 2.1 Kubernetes 资源相关
- **Kubernetes / Compute Resources / Node (Pods)** → 看所有 Pod 的 CPU 和内存使用
- **Kubernetes / Compute Resources / Namespace (Pods)** → 看某个命名空间下的 Pod 资源使用
- **Kubernetes / Compute Resources / Workload** → 看某个 Deployment/StatefulSet 等的资源使用

### 2.2 网络相关
- **Kubernetes / Networking / Cluster** → 看集群网络流量
- **Kubernetes / Networking / Namespace (Pods)** → 看命名空间下的网络

---

## 3. 查看 Galaxy Mall 特定服务的指标

### 3.1 如果想直接在 Grafana 查看自定义查询
1. 点击左侧菜单的 **Explore**
2. 选择 Prometheus 作为数据源
3. 输入 PromQL 查询，例如：
   ```promql
   # 查看 gateway 服务的堆内存使用
   jvm_memory_used_bytes{application="gateway",area="heap"}
   
   # 查看所有 JVM 服务的活跃线程数
   jvm_threads_live_threads
   
   # 查看 CPU 使用
   process_cpu_usage
   ```

---

## 4. 配置提示（当前已配置）

当前配置已满足：
- ✅ 服务包含 `micrometer-registry-prometheus` 依赖
- ✅ 服务暴露 `/actuator/prometheus` 端点
- ✅ Service 有 `monitoring=galaxy-mall` 标签
- ✅ Service 的端口命名为 `http`
- ✅ ServiceMonitor 配置正确，选择器匹配 `monitoring=galaxy-mall`，从 `/actuator/prometheus` 抓取

---

## 5. 常见 Galaxy Mall 指标

| 指标 | 说明 |
| --- | --- |
| `jvm_memory_used_bytes` | JVM 已使用内存 |
| `jvm_threads_live_threads` | JVM 活跃线程数 |
| `jvm_gc_pause_seconds` | GC 暂停时间 |
| `system_cpu_usage` | 系统 CPU 使用 |
| `process_cpu_usage` | 进程 CPU 使用 |
| `spring_cloud_gateway_requests_seconds` | Gateway 网关请求时间（如果是 gateway 服务） |

