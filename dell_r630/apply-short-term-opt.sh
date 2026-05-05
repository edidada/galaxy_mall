#!/bin/bash
set -euo pipefail

KUBECTL="${KUBECTL:-sudo /usr/local/bin/k3s kubectl}"
NS="${NS:-default}"

echo "[1/6] Apply kernel tuning"
if [ -f apply-kernel-tuning.sh ]; then
    chmod +x apply-kernel-tuning.sh
    sudo ./apply-kernel-tuning.sh || true
fi

echo "[2/6] Optimize JVM parameters for Gateway"
$KUBECTL -n "$NS" set env deployment/gateway \
  JAVA_OPTS="-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=16m -XX:+UseStringDeduplication -XX:+OptimizeStringConcat -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=1g -Xss512k -XX:+ParallelRefProcEnabled -XX:InitiatingHeapOccupancyPercent=45 -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom"

echo "[3/6] Optimize JVM parameters for Product"
$KUBECTL -n "$NS" set env deployment/product \
  JAVA_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=8m -XX:+UseStringDeduplication -XX:+OptimizeStringConcat -XX:+AlwaysPreTouch -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom"

echo "[4/6] Optimize JVM parameters for Stock"
$KUBECTL -n "$NS" set env deployment/stock \
  JAVA_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=8m -XX:+UseStringDeduplication -XX:+OptimizeStringConcat -XX:+AlwaysPreTouch -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom"

echo "[5/6] Scale up replicas"
$KUBECTL -n "$NS" scale deployment/gateway --replicas=16
$KUBECTL -n "$NS" scale deployment/product --replicas=8
$KUBECTL -n "$NS" scale deployment/stock --replicas=8
$KUBECTL -n "$NS" scale deployment/order --replicas=6
$KUBECTL -n "$NS" scale deployment/payment --replicas=4
$KUBECTL -n "$NS" scale deployment/user --replicas=4

echo "[6/6] Restart and wait"
$KUBECTL -n "$NS" rollout restart deployment/gateway
$KUBECTL -n "$NS" rollout restart deployment/product
$KUBECTL -n "$NS" rollout restart deployment/stock
$KUBECTL -n "$NS" rollout restart deployment/order
$KUBECTL -n "$NS" rollout restart deployment/payment
$KUBECTL -n "$NS" rollout restart deployment/user

echo "Waiting for rollout to complete..."
$KUBECTL -n "$NS" rollout status deployment/gateway --timeout=300s || true
$KUBECTL -n "$NS" rollout status deployment/product --timeout=300s || true
$KUBECTL -n "$NS" rollout status deployment/stock --timeout=300s || true
$KUBECTL -n "$NS" rollout status deployment/order --timeout=300s || true
$KUBECTL -n "$NS" rollout status deployment/payment --timeout=300s || true
$KUBECTL -n "$NS" rollout status deployment/user --timeout=300s || true

echo "Short-term optimization applied"
echo "Gateway replicas: 16, Product: 8, Stock: 8, Order: 6, Payment: 4, User: 4"
