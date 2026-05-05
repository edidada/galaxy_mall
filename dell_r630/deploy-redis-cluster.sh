#!/bin/bash
set -euo pipefail

KUBECTL="${KUBECTL:-sudo /usr/local/bin/k3s kubectl}"
NS="${NS:-default}"

echo "=== Deploying Redis Cluster (Simplified - 3 master replicas) ==="

# 1. 创建 Redis 配置文件
cat << 'EOF' > /tmp/redis-cluster.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: redis-cluster-config
data:
  redis.conf: |
    port 6379
    cluster-enabled yes
    cluster-config-file nodes.conf
    cluster-node-timeout 5000
    appendonly yes
    save 900 1
    save 300 10
    save 60 10000
    maxmemory 2gb
    maxmemory-policy allkeys-lru
EOF

# 2. 创建 StatefulSet
cat << 'EOF' > /tmp/redis-cluster-sts.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis-cluster
spec:
  serviceName: redis-cluster
  replicas: 3
  selector:
    matchLabels:
      app: redis-cluster
  template:
    metadata:
      labels:
        app: redis-cluster
    spec:
      containers:
      - name: redis
        image: redis:7-alpine
        ports:
        - containerPort: 6379
          name: redis
        - containerPort: 16379
          name: redis-cluster
        volumeMounts:
        - name: redis-cluster-config
          mountPath: /usr/local/etc/redis/
        - name: redis-cluster-data
          mountPath: /data
        command: ["redis-server"]
        args: ["/usr/local/etc/redis/redis.conf"]
        resources:
          requests:
            cpu: 1
            memory: 2Gi
          limits:
            cpu: 2
            memory: 4Gi
      volumes:
      - name: redis-cluster-config
        configMap:
          name: redis-cluster-config
          items:
          - key: redis.conf
            path: redis.conf
  volumeClaimTemplates:
  - metadata:
      name: redis-cluster-data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 10Gi
---
apiVersion: v1
kind: Service
metadata:
  name: redis-cluster
spec:
  type: ClusterIP
  clusterIP: None
  selector:
    app: redis-cluster
  ports:
  - port: 6379
    targetPort: 6379
    name: redis
  - port: 16379
    targetPort: 16379
    name: redis-cluster
EOF

# 部署
echo "Deploying Redis Cluster resources..."
$KUBECTL -n "$NS" apply -f /tmp/redis-cluster.yaml || true
$KUBECTL -n "$NS" apply -f /tmp/redis-cluster-sts.yaml

# 等待 Pod 启动
echo "Waiting for Redis Cluster pods to start..."
sleep 30

# 获取 IP 地址
echo "Getting pod IPs..."
POD_IPS=""
for i in $(seq 0 2); do
    IP=$($KUBECTL -n "$NS" get pod redis-cluster-$i -o jsonpath='{.status.podIP}' || true)
    if [ -n "$IP" ]; then
        POD_IPS="$POD_IPS $IP:6379"
    fi
    sleep 5
done

echo "Redis Cluster pods: $POD_IPS"

echo "Redis Cluster deployed (simplified mode)"
echo "Note: For single-node k3s, we'll keep using single Redis for now"
echo "Full Redis Cluster requires 6+ nodes and multi-node k3s cluster"
EOF
