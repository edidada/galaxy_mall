#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CI_CD_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${CI_CD_ROOT}/.." && pwd)"

# shellcheck source=/dev/null
source "${CI_CD_ROOT}/env.sh"

log() {
  printf '[ci_cd] %s\n' "$*"
}

fail() {
  printf '[ci_cd] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

sudo_run() {
  if [[ "${EUID}" -eq 0 ]]; then
    "$@"
  elif sudo -n true >/dev/null 2>&1; then
    sudo "$@"
  elif [[ -n "${SUDO_PASSWORD:-}" ]]; then
    local askpass
    askpass="$(mktemp)"
    cat > "${askpass}" <<EOF
#!/usr/bin/env bash
printf '%s\n' '${SUDO_PASSWORD}'
EOF
    chmod 700 "${askpass}"
    SUDO_ASKPASS="${askpass}" sudo -A "$@"
    rm -f "${askpass}"
  else
    sudo "$@"
  fi
}

kubectl_cmd() {
  sudo_run /usr/local/bin/k3s kubectl "$@"
}

helm_cmd() {
  if [[ -x /usr/local/bin/helm ]]; then
    /usr/local/bin/helm "$@"
  else
    "${HELM_BIN}" "$@"
  fi
}

ensure_dir() {
  mkdir -p "$1"
}

wait_for_rollout() {
  local namespace="$1"
  local kind_name="$2"
  kubectl_cmd -n "${namespace}" rollout status "${kind_name}" --timeout=20m
}

wait_for_pods() {
  local namespace="$1"
  local pods
  pods="$(kubectl_cmd -n "${namespace}" get pods --field-selector=status.phase!=Succeeded,status.phase!=Failed -o name)"
  if [[ -z "${pods}" ]]; then
    return 0
  fi
  while IFS= read -r pod; do
    [[ -n "${pod}" ]] || continue
    kubectl_cmd -n "${namespace}" wait --for=condition=Ready "${pod}" --timeout=20m
  done <<< "${pods}"
}

escape_sed() {
  printf '%s' "$1" | sed -e 's/[\/&]/\\&/g'
}

render_template() {
  local src="$1"
  local dst="$2"

  ensure_dir "$(dirname "${dst}")"

  sed \
    -e "s|__TIME_ZONE__|$(escape_sed "${TIME_ZONE}")|g" \
    -e "s|__K3S_NODE_IP__|$(escape_sed "${K3S_NODE_IP}")|g" \
    -e "s|__BASE_DOMAIN__|$(escape_sed "${BASE_DOMAIN}")|g" \
    -e "s|__GITLAB_NAMESPACE__|$(escape_sed "${GITLAB_NAMESPACE}")|g" \
    -e "s|__JENKINS_NAMESPACE__|$(escape_sed "${JENKINS_NAMESPACE}")|g" \
    -e "s|__MIDDLEWARE_NAMESPACE__|$(escape_sed "${MIDDLEWARE_NAMESPACE}")|g" \
    -e "s|__APP_NAMESPACE__|$(escape_sed "${APP_NAMESPACE}")|g" \
    -e "s|__GITLAB_RELEASE__|$(escape_sed "${GITLAB_RELEASE}")|g" \
    -e "s|__JENKINS_RELEASE__|$(escape_sed "${JENKINS_RELEASE}")|g" \
    -e "s|__GITLAB_HOST__|$(escape_sed "${GITLAB_HOST}")|g" \
    -e "s|__GITLAB_REGISTRY_HOST__|$(escape_sed "${GITLAB_REGISTRY_HOST}")|g" \
    -e "s|__JENKINS_HOST__|$(escape_sed "${JENKINS_HOST}")|g" \
    -e "s|__MALL_HOST__|$(escape_sed "${MALL_HOST}")|g" \
    -e "s|__GITLAB_ROOT_USERNAME__|$(escape_sed "${GITLAB_ROOT_USERNAME}")|g" \
    -e "s|__GITLAB_ROOT_PASSWORD__|$(escape_sed "${GITLAB_ROOT_PASSWORD}")|g" \
    -e "s|__GITLAB_ROOT_EMAIL__|$(escape_sed "${GITLAB_ROOT_EMAIL}")|g" \
    -e "s|__GITLAB_ROOT_TOKEN__|$(escape_sed "${GITLAB_ROOT_TOKEN}")|g" \
    -e "s|__GITLAB_GROUP_PATH__|$(escape_sed "${GITLAB_GROUP_PATH}")|g" \
    -e "s|__GITLAB_PROJECT_NAME__|$(escape_sed "${GITLAB_PROJECT_NAME}")|g" \
    -e "s|__GITLAB_DEFAULT_BRANCH__|$(escape_sed "${GITLAB_DEFAULT_BRANCH}")|g" \
    -e "s|__GITLAB_PROJECT_HTTP_URL__|$(escape_sed "${GITLAB_PROJECT_HTTP_URL}")|g" \
    -e "s|__JENKINS_ADMIN_USER__|$(escape_sed "${JENKINS_ADMIN_USER}")|g" \
    -e "s|__JENKINS_ADMIN_PASSWORD__|$(escape_sed "${JENKINS_ADMIN_PASSWORD}")|g" \
    -e "s|__JENKINS_WEBHOOK_TOKEN__|$(escape_sed "${JENKINS_WEBHOOK_TOKEN}")|g" \
    -e "s|__GITLAB_POSTGRES_USER__|$(escape_sed "${GITLAB_POSTGRES_USER}")|g" \
    -e "s|__GITLAB_POSTGRES_PASSWORD__|$(escape_sed "${GITLAB_POSTGRES_PASSWORD}")|g" \
    -e "s|__GITLAB_POSTGRES_DB__|$(escape_sed "${GITLAB_POSTGRES_DB}")|g" \
    -e "s|__GITLAB_REDIS_PASSWORD__|$(escape_sed "${GITLAB_REDIS_PASSWORD}")|g" \
    -e "s|__MYSQL_ROOT_PASSWORD__|$(escape_sed "${MYSQL_ROOT_PASSWORD}")|g" \
    -e "s|__MYSQL_APP_USER__|$(escape_sed "${MYSQL_APP_USER}")|g" \
    -e "s|__MYSQL_APP_PASSWORD__|$(escape_sed "${MYSQL_APP_PASSWORD}")|g" \
    -e "s|__REDIS_PASSWORD__|$(escape_sed "${REDIS_PASSWORD}")|g" \
    -e "s|__APP_IMAGE_REGISTRY__|$(escape_sed "${APP_IMAGE_REGISTRY}")|g" \
    -e "s|__APP_IMAGE_REPOSITORY__|$(escape_sed "${APP_IMAGE_REPOSITORY}")|g" \
    -e "s|__APP_IMAGE_TAG__|$(escape_sed "${APP_IMAGE_TAG}")|g" \
    "${src}" > "${dst}"
}

ensure_helm() {
  if [[ -x /usr/local/bin/helm ]]; then
    return 0
  fi

  if command -v helm >/dev/null 2>&1; then
    return 0
  fi

  log "installing helm"
  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
}
