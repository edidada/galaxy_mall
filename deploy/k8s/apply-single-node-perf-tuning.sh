#!/bin/bash
set -euo pipefail

KUBECTL="${KUBECTL:-sudo /usr/local/bin/k3s kubectl}"
NS="${NS:-default}"

echo "[1/4] update runtime env"
$KUBECTL -n "$NS" set env deployment/gateway \
  SPRING_PROFILES_ACTIVE=prod \
  SERVICES_USER_URL=http://user:8080 \
  SERVICES_PRODUCT_URL=http://product:8080 \
  SERVICES_STOCK_URL=http://stock:8080 \
  SERVICES_ORDER_URL=http://order:8080 \
  SERVICES_PAYMENT_URL=http://payment:8080 \
  GALAXY_GATEWAY_QUERY_TOKEN_CAPACITY=200000 \
  GALAXY_GATEWAY_QUERY_REFILL_PER_SECOND=100000 \
  GALAXY_GATEWAY_ORDER_TOKEN_CAPACITY=100000 \
  GALAXY_GATEWAY_ORDER_REFILL_PER_SECOND=50000 \
  GALAXY_GATEWAY_ORDER_LEAKY_BUCKET_CAPACITY=30000 \
  GALAXY_GATEWAY_ORDER_LEAKY_BUCKET_PER_SECOND=20000 \
  GALAXY_GATEWAY_WAITING_ROOM_CAPACITY=1000000 \
  GALAXY_GATEWAY_WAITING_ROOM_RELEASE_PER_SECOND=100000

$KUBECTL -n "$NS" set env deployment/user \
  SPRING_PROFILES_ACTIVE=prod \
  SERVER_PORT=8080

$KUBECTL -n "$NS" set env deployment/product \
  SPRING_PROFILES_ACTIVE=prod \
  SERVER_PORT=8080

$KUBECTL -n "$NS" set env deployment/stock \
  SPRING_PROFILES_ACTIVE=prod \
  SERVER_PORT=8080 \
  GALAXY_STOCK_BACKEND=redis \
  REDIS_HOST=redis \
  REDIS_PORT=6379

$KUBECTL -n "$NS" set env deployment/order \
  SPRING_PROFILES_ACTIVE=prod \
  SERVER_PORT=8080 \
  SERVICES_PRODUCT_URL=http://product:8080 \
  SERVICES_STOCK_URL=http://stock:8080 \
  GALAXY_ORDER_MESSAGING_BACKEND=rocketmq \
  ROCKETMQ_NAME_SERVER=rocketmq-namesrv.middleware.svc.cluster.local:9876 \
  ROCKETMQ_PRODUCER_GROUP=galaxy-order-producer \
  GALAXY_ORDER_ROCKETMQ_TOPIC=galaxy-order-submitted \
  GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP=galaxy-order-consumer \
  GALAXY_ORDER_STORE_BACKEND=jdbc-sharded \
  ORDER_SHARD_0_URL='jdbc:mysql://mysql:3306/galaxy_order_0?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8' \
  ORDER_SHARD_1_URL='jdbc:mysql://mysql:3306/galaxy_order_1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8' \
  ORDER_SHARD_0_USERNAME=root \
  ORDER_SHARD_1_USERNAME=root

$KUBECTL -n "$NS" set env deployment/payment \
  SPRING_PROFILES_ACTIVE=prod \
  SERVER_PORT=8080 \
  SERVICES_ORDER_URL=http://order:8080

echo "[2/4] scale replicas"
$KUBECTL -n "$NS" scale deployment/gateway --replicas=8
$KUBECTL -n "$NS" scale deployment/product --replicas=4
$KUBECTL -n "$NS" scale deployment/stock --replicas=4
$KUBECTL -n "$NS" scale deployment/order --replicas=4
$KUBECTL -n "$NS" scale deployment/payment --replicas=2
$KUBECTL -n "$NS" scale deployment/user --replicas=2

echo "[3/4] restart deployments"
$KUBECTL -n "$NS" rollout restart deployment/gateway
$KUBECTL -n "$NS" rollout restart deployment/user
$KUBECTL -n "$NS" rollout restart deployment/product
$KUBECTL -n "$NS" rollout restart deployment/stock
$KUBECTL -n "$NS" rollout restart deployment/order
$KUBECTL -n "$NS" rollout restart deployment/payment

echo "[4/4] rollout status"
$KUBECTL -n "$NS" rollout status deployment/gateway
$KUBECTL -n "$NS" rollout status deployment/user
$KUBECTL -n "$NS" rollout status deployment/product
$KUBECTL -n "$NS" rollout status deployment/stock
$KUBECTL -n "$NS" rollout status deployment/order
$KUBECTL -n "$NS" rollout status deployment/payment

echo "single-node perf tuning applied in namespace: $NS"
