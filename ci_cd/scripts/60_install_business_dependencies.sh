#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_dir "${RENDER_DIR}"

render_template "${CI_CD_ROOT}/templates/k8s/mysql.yaml.tpl" "${RENDER_DIR}/mysql.yaml"
render_template "${CI_CD_ROOT}/templates/k8s/redis.yaml.tpl" "${RENDER_DIR}/redis.yaml"
render_template "${CI_CD_ROOT}/templates/k8s/rocketmq.yaml.tpl" "${RENDER_DIR}/rocketmq.yaml"

log "deploying mysql / redis / rocketmq"
kubectl_cmd apply -f "${RENDER_DIR}/mysql.yaml"
kubectl_cmd apply -f "${RENDER_DIR}/redis.yaml"
kubectl_cmd apply -f "${RENDER_DIR}/rocketmq.yaml"

wait_for_rollout "${MIDDLEWARE_NAMESPACE}" deployment/mysql
wait_for_rollout "${MIDDLEWARE_NAMESPACE}" deployment/redis
wait_for_rollout "${MIDDLEWARE_NAMESPACE}" deployment/rocketmq-namesrv
wait_for_rollout "${MIDDLEWARE_NAMESPACE}" deployment/rocketmq-dashboard
wait_for_rollout "${MIDDLEWARE_NAMESPACE}" statefulset/rocketmq-broker

log "business dependencies ready"
