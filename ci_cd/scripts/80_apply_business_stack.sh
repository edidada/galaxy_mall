#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

IMAGE_TAG="${1:-${APP_IMAGE_TAG}}"

"${SCRIPT_DIR}/70_render_business_manifest.sh" "${IMAGE_TAG}"
kubectl_cmd apply -f "${RENDER_DIR}/galaxy-mall.yaml"

# ---- 2026-10-18 追加：apply 之后把调度约束钉回去 ----
# requests/limits（含 ephemeral-storage）、节点池 nodeSelector、拓扑均分、
# 对 doit 重户的反亲和、startupProbe 都由 deploy/k8s/apply-scheduling-and-resources.sh
# 用 kubectl patch --type=strategic 下发，故意不写进上面那份渲染清单：
#   现网 Deployment 的 image 是 GitHub Actions 推的 git-<sha>，而渲染清单里的 image
#   是模板 tag（uat/testtag 系），apply 会把线上版本打回旧 tag —— 等于用"修调度"
#   的名义发一次回滚。strategic patch 按容器 name 合并，只动我写的字段。
# 放在 apply 之后、等 Pod ready 之前：CI 每次部署都会重建这些约束，
# 手工 kubectl edit 造成的漂移会被覆盖回来。
export K="${SCHEDULING_KUBECTL:-sudo /usr/local/bin/k3s kubectl}"
export NS="${APP_NAMESPACE}"
bash "${REPO_ROOT}/deploy/k8s/apply-scheduling-and-resources.sh" patch

wait_for_pods "${APP_NAMESPACE}"

log "business stack applied"
