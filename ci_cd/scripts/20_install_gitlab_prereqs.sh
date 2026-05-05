#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_dir "${RENDER_DIR}"

render_template "${CI_CD_ROOT}/templates/k8s/gitlab-postgresql.yaml.tpl" "${RENDER_DIR}/gitlab-postgresql.yaml"
render_template "${CI_CD_ROOT}/templates/k8s/gitlab-redis.yaml.tpl" "${RENDER_DIR}/gitlab-redis.yaml"

log "deploying external PostgreSQL and Redis for GitLab"
kubectl_cmd apply -f "${RENDER_DIR}/gitlab-postgresql.yaml"
kubectl_cmd apply -f "${RENDER_DIR}/gitlab-redis.yaml"

wait_for_rollout "${GITLAB_NAMESPACE}" deployment/gitlab-postgresql
wait_for_rollout "${GITLAB_NAMESPACE}" deployment/gitlab-redis

log "gitlab prerequisites ready"
