#!/usr/bin/env bash

set -euo pipefail

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

log "preparing host packages and firewall"

missing_packages=()
for cmd in curl git tar; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    missing_packages+=("${cmd}")
  fi
done

if [[ ${#missing_packages[@]} -gt 0 ]]; then
  if sudo_run yum install -y "${missing_packages[@]}" bash-completion policycoreutils-python >/dev/null; then
    log "installed missing packages via yum"
  else
    log "yum install failed, continuing with existing host tools"
  fi
else
  log "required base commands already present, skipping yum install"
fi

if command -v setenforce >/dev/null 2>&1; then
  sudo_run setenforce 0 || true
  sudo_run sed -i 's/^SELINUX=enforcing$/SELINUX=permissive/' /etc/selinux/config || true
fi

if systemctl is-active --quiet firewalld; then
  sudo_run firewall-cmd --permanent --add-service=http || true
  sudo_run firewall-cmd --permanent --add-service=https || true
  sudo_run firewall-cmd --permanent --add-service=ssh || true
  sudo_run firewall-cmd --permanent --add-port=30000-32767/tcp || true
  sudo_run firewall-cmd --reload || true
fi

cat <<'EOF' | sudo_run tee /etc/sysctl.d/99-galaxy-mall-ci-cd.conf >/dev/null
vm.max_map_count = 262144
fs.inotify.max_user_watches = 524288
fs.inotify.max_user_instances = 1024
net.core.somaxconn = 65535
EOF

sudo_run sysctl --system >/dev/null || true

log "host preparation complete"
