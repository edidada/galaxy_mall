# Prometheus Grafana在k3s中的部署方案

## 方案概述
本方案使用k3s内置的Helm控制器，通过Helm部署 `kube-prometheus-stack`（包含Prometheus、Grafana、Alertmanager、node-exporter等组件）。

## 前置条件
- k3s已正常运行
- 网络访问正常（能够拉取镜像）
- 有`kubectl`访问权限

## 1.配置Helm（可选）
k3s自带Helm，但你也可以安装本地Helm客户端方便使用：
```bash
# 安装 Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# 验证安装
helm version
```

## 2. 添加Helm repo
```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

## 3. 部署 kube-prometheus-stack
创建一个 `values.yaml` 配置文件，简化配置并适配 k3s：

```yaml
# values.yaml
prometheus:
  prometheusSpec:
    serviceMonitorSelectorNilUsesHelmValues: false
    serviceMonitorSelector: {}
    serviceMonitorNamespaceSelector: {}

grafana:
  adminPassword: "5Edidada"
  service:
    type: NodePort
    nodePort: 30300

alertmanager:
  enabled: true
  service:
    type: NodePort
    nodePort: 30301

prometheus-node-exporter:
  enabled: true
```

开始部署：
```bash
# 创建命名空间
kubectl create namespace monitoring

# 部署
helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring -f values.yaml
```

## 4. 验证部署
```bash
# 查看所有 Pod 状态
kubectl get pods -n monitoring

# 查看 Service
kubectl get svc -n monitoring
```

## 5. 访问服务
### Grafana
- 访问地址：`http://<k3s-node-ip>:30300`
- 用户名：`admin`
- 密码：你在 `values.yaml` 中配置的密码（默认 `5Edidada`）

### Prometheus
```bash
# 临时 port-forward
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
```
然后访问：`http://localhost:9090`

## 6. 为Galaxy Mall服务配置ServiceMonitor
让Prometheus能够自动发现并抓取Galaxy Mall服务的指标：

创建 `galaxy-mall-service-monitor.yaml`：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: galaxy-mall-service-monitor
  namespace: monitoring
  labels:
    release: prometheus
spec:
  namespaceSelector:
    matchNames:
      - default
  selector:
    matchLabels:
      app: galaxy-mall
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

应用：
```bash
kubectl apply -f galaxy-mall-service-monitor.yaml
```

## 7. Grafana 常用 Dashboard
`kube-prometheus-stack` 默认包含很多 Dashboard，你可以：
1. 点击左侧菜单的 `Dashboards` → `General`
2. 找到常用的：
   - Kubernetes / Compute Resources / Node (Pods)
   - Kubernetes / Networking / Cluster
   - JVM (Micrometer)

## 8. 清理（可选）
如果需要删除 Prometheus + Grafana：
```bash
helm uninstall prometheus -n monitoring
kubectl delete namespace monitoring
```
