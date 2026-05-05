#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

IMAGE_TAG="${1:-${APP_IMAGE_TAG}}"
OUTPUT_FILE="${2:-${RENDER_DIR}/galaxy-mall.yaml}"

ensure_dir "${RENDER_DIR}"

sed \
  -e "s/galaxy-mall-uat/${APP_NAMESPACE}/g" \
  -e "s/galaxy-mall-uat-config/${APP_NAMESPACE}-config/g" \
  -e "s/galaxy-mall-uat-secrets/${APP_NAMESPACE}-secrets/g" \
  -e "s|registry.example.com/galaxy-mall|${APP_IMAGE_REGISTRY}/${APP_IMAGE_REPOSITORY}|g" \
  -e "s/:uat/:${IMAGE_TAG}/g" \
  -e "s|redis\\.uat\\.svc\\.cluster\\.local|redis.${MIDDLEWARE_NAMESPACE}.svc.cluster.local|g" \
  -e "s|rocketmq-namesrv\\.uat\\.svc\\.cluster\\.local:9876|rocketmq-namesrv.${MIDDLEWARE_NAMESPACE}.svc.cluster.local:9876|g" \
  -e "s|mysql-order-0\\.uat\\.svc\\.cluster\\.local:3306/galaxy_order_0|mysql.${MIDDLEWARE_NAMESPACE}.svc.cluster.local:3306/galaxy_order_0|g" \
  -e "s|mysql-order-1\\.uat\\.svc\\.cluster\\.local:3306/galaxy_order_1|mysql.${MIDDLEWARE_NAMESPACE}.svc.cluster.local:3306/galaxy_order_1|g" \
  -e "s/SPRING_PROFILES_ACTIVE: \"uat\"/SPRING_PROFILES_ACTIVE: \"prod\"/g" \
  -e "s/GALAXY_ORDER_ROCKETMQ_TOPIC: \"galaxy-order-submitted-uat\"/GALAXY_ORDER_ROCKETMQ_TOPIC: \"galaxy-order-submitted\"/g" \
  -e "s/GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP: \"galaxy-order-consumer-uat\"/GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP: \"galaxy-order-consumer\"/g" \
  -e "s/ROCKETMQ_PRODUCER_GROUP: \"galaxy-order-producer-uat\"/ROCKETMQ_PRODUCER_GROUP: \"galaxy-order-producer\"/g" \
  -e "s/REDIS_PASSWORD: \"\"/REDIS_PASSWORD: \"${REDIS_PASSWORD}\"/g" \
  -e "s/ORDER_SHARD_0_PASSWORD: \"change-me\"/ORDER_SHARD_0_PASSWORD: \"${MYSQL_APP_PASSWORD}\"/g" \
  -e "s/ORDER_SHARD_1_PASSWORD: \"change-me\"/ORDER_SHARD_1_PASSWORD: \"${MYSQL_APP_PASSWORD}\"/g" \
  "${REPO_ROOT}/deploy/k8s/galaxy-mall-uat.yaml" > "${OUTPUT_FILE}"

cat <<EOF >> "${OUTPUT_FILE}"
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: galaxy-mall-gateway
  namespace: ${APP_NAMESPACE}
  annotations:
    kubernetes.io/ingress.class: traefik
spec:
  ingressClassName: traefik
  rules:
    - host: ${MALL_HOST}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: galaxy-mall-gateway
                port:
                  number: 8080
EOF

log "rendered business manifest to ${OUTPUT_FILE}"
