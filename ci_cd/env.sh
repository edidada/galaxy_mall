#!/usr/bin/env bash

set -euo pipefail

CI_CD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${CI_CD_ROOT}/.." && pwd)"

detect_node_ip() {
  local ip=""

  if command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi

  if [[ -z "${ip}" ]] && command -v ip >/dev/null 2>&1; then
    ip="$(ip route get 1.1.1.1 2>/dev/null | awk '/src/ {for (i=1; i<=NF; i++) if ($i == "src") {print $(i+1); exit}}')"
  fi

  if [[ -z "${ip}" ]] && command -v ipconfig >/dev/null 2>&1; then
    ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
    if [[ -z "${ip}" ]]; then
      ip="$(ipconfig getifaddr en1 2>/dev/null || true)"
    fi
  fi

  printf '%s' "${ip}"
}

export TIME_ZONE="${TIME_ZONE:-Asia/Shanghai}"
export K3S_VERSION="${K3S_VERSION:-v1.28.8+k3s1}"
export GITLAB_CHART_VERSION="${GITLAB_CHART_VERSION:-9.8.3}"
export JENKINS_CHART_VERSION="${JENKINS_CHART_VERSION:-5.8.110}"

export K3S_NODE_IP="${K3S_NODE_IP:-$(detect_node_ip)}"
export K3S_NODE_IP="${K3S_NODE_IP:-127.0.0.1}"
export BASE_DOMAIN="${BASE_DOMAIN:-${K3S_NODE_IP}.nip.io}"

export GITLAB_NAMESPACE="${GITLAB_NAMESPACE:-gitlab}"
export JENKINS_NAMESPACE="${JENKINS_NAMESPACE:-jenkins}"
export MIDDLEWARE_NAMESPACE="${MIDDLEWARE_NAMESPACE:-middleware}"
export APP_NAMESPACE="${APP_NAMESPACE:-galaxy-mall}"

export GITLAB_RELEASE="${GITLAB_RELEASE:-gitlab}"
export JENKINS_RELEASE="${JENKINS_RELEASE:-jenkins}"

export GITLAB_HOST="${GITLAB_HOST:-gitlab.${BASE_DOMAIN}}"
export GITLAB_REGISTRY_HOST="${GITLAB_REGISTRY_HOST:-registry.${BASE_DOMAIN}}"
export JENKINS_HOST="${JENKINS_HOST:-jenkins.${BASE_DOMAIN}}"
export MALL_HOST="${MALL_HOST:-mall.${BASE_DOMAIN}}"

export GITLAB_ROOT_USERNAME="${GITLAB_ROOT_USERNAME:-root}"
export GITLAB_ROOT_PASSWORD="${GITLAB_ROOT_PASSWORD:-ChangeMe_2026!}"
export GITLAB_ROOT_EMAIL="${GITLAB_ROOT_EMAIL:-root@${BASE_DOMAIN}}"
export GITLAB_ROOT_TOKEN="${GITLAB_ROOT_TOKEN:-glpat-bootstrap-2026-ChangeMe}"

export GITLAB_GROUP_PATH="${GITLAB_GROUP_PATH:-galaxy-mall}"
export GITLAB_PROJECT_NAME="${GITLAB_PROJECT_NAME:-galaxy-mall}"
export GITLAB_DEFAULT_BRANCH="${GITLAB_DEFAULT_BRANCH:-master}"
export GITLAB_PROJECT_HTTP_URL="${GITLAB_PROJECT_HTTP_URL:-http://${GITLAB_HOST}/${GITLAB_GROUP_PATH}/${GITLAB_PROJECT_NAME}.git}"

export JENKINS_ADMIN_USER="${JENKINS_ADMIN_USER:-admin}"
export JENKINS_ADMIN_PASSWORD="${JENKINS_ADMIN_PASSWORD:-ChangeMe_2026!}"
export JENKINS_WEBHOOK_TOKEN="${JENKINS_WEBHOOK_TOKEN:-galaxy-mall-webhook-2026}"

export GITLAB_POSTGRES_USER="${GITLAB_POSTGRES_USER:-gitlab}"
export GITLAB_POSTGRES_PASSWORD="${GITLAB_POSTGRES_PASSWORD:-ChangeMe_2026!}"
export GITLAB_POSTGRES_DB="${GITLAB_POSTGRES_DB:-gitlabhq_production}"
export GITLAB_REDIS_PASSWORD="${GITLAB_REDIS_PASSWORD:-ChangeMe_2026!}"

export MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-ChangeMe_2026!}"
export MYSQL_APP_USER="${MYSQL_APP_USER:-galaxy}"
export MYSQL_APP_PASSWORD="${MYSQL_APP_PASSWORD:-ChangeMe_2026!}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-ChangeMe_2026!}"

export APP_IMAGE_REGISTRY="${APP_IMAGE_REGISTRY:-${GITLAB_REGISTRY_HOST}}"
export APP_IMAGE_REPOSITORY="${APP_IMAGE_REPOSITORY:-${GITLAB_GROUP_PATH}/${GITLAB_PROJECT_NAME}}"
export APP_IMAGE_TAG="${APP_IMAGE_TAG:-uat}"

export KUBECTL_BIN="${KUBECTL_BIN:-/usr/local/bin/k3s kubectl}"
export HELM_BIN="${HELM_BIN:-helm}"
export RENDER_DIR="${RENDER_DIR:-${CI_CD_ROOT}/rendered}"

export REMOTE_SSH_TARGET="${REMOTE_SSH_TARGET:-wdidada@192.168.31.171}"
export REMOTE_SSH_PASSWORD="${REMOTE_SSH_PASSWORD:-5Edidada}"
export REMOTE_REPO_DIR="${REMOTE_REPO_DIR:-/home/wdidada/galaxy_mall}"
export REMOTE_CI_CD_DIR="${REMOTE_CI_CD_DIR:-${REMOTE_REPO_DIR}/ci_cd}"
export SUDO_PASSWORD="${SUDO_PASSWORD:-${REMOTE_SSH_PASSWORD}}"
