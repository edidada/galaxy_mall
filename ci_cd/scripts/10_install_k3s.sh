#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

if [[ -x /usr/local/bin/k3s ]]; then
  log "k3s already installed"
else
  log "installing k3s ${K3S_VERSION}"
  curl -sfL https://get.k3s.io | sudo env INSTALL_K3S_VERSION="${K3S_VERSION}" INSTALL_K3S_EXEC="server --write-kubeconfig-mode 644 --tls-san ${K3S_NODE_IP}" sh -
fi

ensure_dir "${HOME}/.kube"
sudo_run cp /etc/rancher/k3s/k3s.yaml "${HOME}/.kube/config"
sudo_run chown "${USER}":"${USER}" "${HOME}/.kube/config"

kubectl_cmd get nodes

ensure_helm

log "adding helm repos"
helm repo add gitlab https://charts.gitlab.io/ >/dev/null 2>&1 || true
helm repo add jenkins https://charts.jenkins.io >/dev/null 2>&1 || true
helm repo update >/dev/null

for ns in "${GITLAB_NAMESPACE}" "${JENKINS_NAMESPACE}" "${MIDDLEWARE_NAMESPACE}" "${APP_NAMESPACE}"; do
  kubectl_cmd create namespace "${ns}" --dry-run=client -o yaml | kubectl_cmd apply -f -
done

log "k3s and helm ready"
