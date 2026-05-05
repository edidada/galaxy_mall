#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_cmd sshpass
require_cmd rsync

log "syncing repository to ${REMOTE_SSH_TARGET}:${REMOTE_REPO_DIR}"
sshpass -p "${REMOTE_SSH_PASSWORD}" ssh -o StrictHostKeyChecking=no "${REMOTE_SSH_TARGET}" "mkdir -p '${REMOTE_REPO_DIR}'"
sshpass -p "${REMOTE_SSH_PASSWORD}" rsync -av --delete \
  --exclude .git \
  --exclude .idea \
  --exclude target \
  --exclude node_modules \
  --exclude dist \
  --exclude '.DS_Store' \
  --exclude '*.tar' \
  --exclude '*.tgz' \
  --exclude deploy/docker/output \
  -e "ssh -o StrictHostKeyChecking=no" \
  "${REPO_ROOT}/" "${REMOTE_SSH_TARGET}:${REMOTE_REPO_DIR}/"

log "remote sync complete"
