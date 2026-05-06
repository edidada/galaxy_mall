#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

IMAGE_NAMESPACE="${IMAGE_NAMESPACE:-galaxy-mall}"
IMAGE_TAG="${IMAGE_TAG:-local}"
APP_VERSION="${APP_VERSION:-1.0-SNAPSHOT}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/deploy/docker/output}"
SAVE_TAR="${SAVE_TAR:-true}"
SKIP_PACKAGE="${SKIP_PACKAGE:-false}"

services=(
  "gateway:galaxy_mall-gateway:8080"
  "user:galaxy_mall-user:8081"
  "product:galaxy_mall-product:8082"
  "galaxy-mall-seckill:galaxy_mall-seckill:8080"
  "stock:galaxy_mall-stock:8083"
  "order:galaxy_mall-order:8084"
  "payment:galaxy_mall-payment:8085"
)

if [[ "${SKIP_PACKAGE}" != "true" ]]; then
  mvn -q -DskipTests package
fi

mkdir -p "${OUTPUT_DIR}"

for service_spec in "${services[@]}"; do
  IFS=":" read -r image_name module_name app_port <<< "${service_spec}"

  jar_path="${module_name}/target/${module_name}-${APP_VERSION}-exec.jar"
  image_ref="${IMAGE_NAMESPACE}/${image_name}:${IMAGE_TAG}"

  if [[ ! -f "${jar_path}" ]]; then
    echo "missing jar: ${jar_path}" >&2
    echo "set APP_VERSION correctly or run mvn -q -DskipTests package first" >&2
    exit 1
  fi

  echo "building ${image_ref} from ${jar_path}"
  docker build \
    -f Dockerfile \
    --build-arg JAR_FILE="${jar_path}" \
    --build-arg APP_PORT="${app_port}" \
    -t "${image_ref}" \
    .

  if [[ "${SAVE_TAR}" == "true" ]]; then
    tar_path="${OUTPUT_DIR}/${image_name}-${IMAGE_TAG}.tar"
    echo "saving ${image_ref} to ${tar_path}"
    docker save -o "${tar_path}" "${image_ref}"
  fi
done

cat <<EOF
images ready.

import into k3s:
  sudo k3s ctr images import ${OUTPUT_DIR}/gateway-${IMAGE_TAG}.tar
  sudo k3s ctr images import ${OUTPUT_DIR}/user-${IMAGE_TAG}.tar
  sudo k3s ctr images import ${OUTPUT_DIR}/product-${IMAGE_TAG}.tar
  sudo k3s ctr images import ${OUTPUT_DIR}/galaxy-mall-seckill-${IMAGE_TAG}.tar
  sudo k3s ctr images import ${OUTPUT_DIR}/stock-${IMAGE_TAG}.tar
  sudo k3s ctr images import ${OUTPUT_DIR}/order-${IMAGE_TAG}.tar
  sudo k3s ctr images import ${OUTPUT_DIR}/payment-${IMAGE_TAG}.tar
EOF
