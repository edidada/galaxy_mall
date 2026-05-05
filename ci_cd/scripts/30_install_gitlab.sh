#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_dir "${RENDER_DIR}"

kubectl_cmd -n "${GITLAB_NAMESPACE}" create secret generic gitlab-initial-root-password \
  --from-literal=password="${GITLAB_ROOT_PASSWORD}" \
  --dry-run=client -o yaml | kubectl_cmd apply -f -

render_template "${CI_CD_ROOT}/templates/gitlab-values.yaml.tpl" "${RENDER_DIR}/gitlab-values.yaml"

log "installing gitlab chart"
helm_cmd upgrade --install "${GITLAB_RELEASE}" gitlab/gitlab \
  --namespace "${GITLAB_NAMESPACE}" \
  --version "${GITLAB_CHART_VERSION}" \
  -f "${RENDER_DIR}/gitlab-values.yaml" \
  --timeout 40m \
  --wait

wait_for_pods "${GITLAB_NAMESPACE}"
kubectl_cmd -n "${GITLAB_NAMESPACE}" get ingress

log "gitlab ready at http://${GITLAB_HOST}"
