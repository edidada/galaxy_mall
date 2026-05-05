#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

log "running smoke tests"

kubectl_cmd -n "${GITLAB_NAMESPACE}" get pods
kubectl_cmd -n "${JENKINS_NAMESPACE}" get pods
kubectl_cmd -n "${MIDDLEWARE_NAMESPACE}" get pods
kubectl_cmd -n "${APP_NAMESPACE}" get pods

curl -fsS "http://${GITLAB_HOST}/-/readiness" >/dev/null
curl -fsS "http://${JENKINS_HOST}/login" >/dev/null
curl -fsS "http://${MALL_HOST}/actuator/health/readiness" >/dev/null || true

log "smoke tests completed"
