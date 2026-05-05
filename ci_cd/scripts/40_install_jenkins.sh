#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_dir "${RENDER_DIR}"
render_template "${CI_CD_ROOT}/templates/jenkins-values.yaml.tpl" "${RENDER_DIR}/jenkins-values.yaml"

log "installing jenkins chart"
helm_cmd upgrade --install "${JENKINS_RELEASE}" jenkins/jenkins \
  --namespace "${JENKINS_NAMESPACE}" \
  --version "${JENKINS_CHART_VERSION}" \
  -f "${RENDER_DIR}/jenkins-values.yaml" \
  --timeout 30m \
  --wait

kubectl_cmd create clusterrolebinding jenkins-cluster-admin \
  --clusterrole=cluster-admin \
  --serviceaccount="${JENKINS_NAMESPACE}:${JENKINS_RELEASE}" \
  --dry-run=client -o yaml | kubectl_cmd apply -f -

wait_for_rollout "${JENKINS_NAMESPACE}" statefulset/"${JENKINS_RELEASE}"

log "jenkins ready at http://${JENKINS_HOST}"
