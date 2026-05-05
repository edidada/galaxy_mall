#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_cmd curl
require_cmd git
require_cmd python

json_value() {
  local key="$1"
  python - "$key" <<'PY'
import json
import sys

key = sys.argv[1]
data = json.load(sys.stdin)
value = data.get(key, "")
if value is None:
    value = ""
print(value)
PY
}

json_find_first_id_by_path() {
  local path="$1"
  python - "$path" <<'PY'
import json
import sys

target = sys.argv[1]
data = json.load(sys.stdin)
for item in data:
    if item.get("path") == target:
        print(item.get("id", ""))
        break
PY
}

log "waiting for gitlab api"
until curl -fsS "http://${GITLAB_HOST}/-/readiness" >/dev/null 2>&1; do
  sleep 15
done

toolbox_pod="$(kubectl_cmd -n "${GITLAB_NAMESPACE}" get pods -o name | grep toolbox | head -n 1 | cut -d/ -f2)"
[[ -n "${toolbox_pod}" ]] || fail "gitlab toolbox pod not found"

log "creating deterministic root personal access token"
kubectl_cmd -n "${GITLAB_NAMESPACE}" exec "${toolbox_pod}" -c toolbox -- gitlab-rails runner "
user = User.find_by_username('${GITLAB_ROOT_USERNAME}')
token = user.personal_access_tokens.find_by(name: 'bootstrap-token')
if token.nil?
  token = user.personal_access_tokens.create!(name: 'bootstrap-token', scopes: [:api, :read_user, :read_repository, :write_repository, :read_registry, :write_registry])
  token.set_token('${GITLAB_ROOT_TOKEN}')
  token.save!
end
" >/dev/null

group_id="$(
  curl -fsS --header "PRIVATE-TOKEN: ${GITLAB_ROOT_TOKEN}" \
    --data-urlencode "name=${GITLAB_GROUP_PATH}" \
    --data-urlencode "path=${GITLAB_GROUP_PATH}" \
    "http://${GITLAB_HOST}/api/v4/groups" 2>/dev/null | json_value id
)"

if [[ -z "${group_id}" ]]; then
  group_id="$(
    curl -fsS --header "PRIVATE-TOKEN: ${GITLAB_ROOT_TOKEN}" \
      "http://${GITLAB_HOST}/api/v4/groups?search=${GITLAB_GROUP_PATH}" | json_find_first_id_by_path "${GITLAB_GROUP_PATH}"
  )"
fi

[[ -n "${group_id}" ]] || fail "unable to create or locate gitlab group"

project_id="$(
  curl -fsS --header "PRIVATE-TOKEN: ${GITLAB_ROOT_TOKEN}" \
    --data-urlencode "name=${GITLAB_PROJECT_NAME}" \
    --data-urlencode "namespace_id=${group_id}" \
    --data-urlencode "visibility=private" \
    --data-urlencode "initialize_with_readme=false" \
    "http://${GITLAB_HOST}/api/v4/projects" 2>/dev/null | json_value id
)"

if [[ -z "${project_id}" ]]; then
  project_id="$(
    curl -fsS --header "PRIVATE-TOKEN: ${GITLAB_ROOT_TOKEN}" \
      "http://${GITLAB_HOST}/api/v4/projects?search=${GITLAB_PROJECT_NAME}" | json_find_first_id_by_path "${GITLAB_PROJECT_NAME}"
  )"
fi

[[ -n "${project_id}" ]] || fail "unable to create or locate gitlab project"

if git -C "${REPO_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if git -C "${REPO_ROOT}" remote get-url local-gitlab >/dev/null 2>&1; then
    git -C "${REPO_ROOT}" remote set-url local-gitlab "http://${GITLAB_ROOT_USERNAME}:${GITLAB_ROOT_TOKEN}@${GITLAB_HOST}/${GITLAB_GROUP_PATH}/${GITLAB_PROJECT_NAME}.git"
  else
    git -C "${REPO_ROOT}" remote add local-gitlab "http://${GITLAB_ROOT_USERNAME}:${GITLAB_ROOT_TOKEN}@${GITLAB_HOST}/${GITLAB_GROUP_PATH}/${GITLAB_PROJECT_NAME}.git"
  fi

  git -C "${REPO_ROOT}" push -u local-gitlab HEAD:"${GITLAB_DEFAULT_BRANCH}"
fi

curl -fsS --header "PRIVATE-TOKEN: ${GITLAB_ROOT_TOKEN}" \
  --data-urlencode "url=http://${JENKINS_HOST}/generic-webhook-trigger/invoke?token=${JENKINS_WEBHOOK_TOKEN}" \
  --data-urlencode "push_events=true" \
  --data-urlencode "enable_ssl_verification=false" \
  "http://${GITLAB_HOST}/api/v4/projects/${project_id}/hooks" >/dev/null 2>&1 || true

log "gitlab project bootstrap complete"
