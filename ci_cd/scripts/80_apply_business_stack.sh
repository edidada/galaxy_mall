#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

IMAGE_TAG="${1:-${APP_IMAGE_TAG}}"

"${SCRIPT_DIR}/70_render_business_manifest.sh" "${IMAGE_TAG}"
kubectl_cmd apply -f "${RENDER_DIR}/galaxy-mall.yaml"
wait_for_pods "${APP_NAMESPACE}"

log "business stack applied"
