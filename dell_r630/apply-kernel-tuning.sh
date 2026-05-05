#!/bin/bash
set -euo pipefail

echo "[1/3] Apply kernel parameters for high-concurrency"
cat << 'EOF' > /etc/sysctl.d/99-galaxy-perf.conf
# Increase file descriptors
fs.file-max = 2097152
fs.nr_open = 2097152

# Network tuning
net.core.somaxconn = 65535
net.core.netdev_max_backlog = 65535
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_tw_recycle = 0
net.ipv4.tcp_fin_timeout = 15
net.ipv4.ip_local_port_range = 1024 65535
net.core.optmem_max = 16777216
net.ipv4.tcp_max_tw_buckets = 1048576
net.ipv4.tcp_mtu_probing = 1

# VM tuning
vm.swappiness = 10
vm.dirty_ratio = 15
vm.dirty_background_ratio = 5
vm.max_map_count = 262144
EOF

sysctl -p /etc/sysctl.d/99-galaxy-perf.conf

echo "[2/3] Increase ulimit limits"
cat << 'EOF' > /etc/security/limits.d/99-galaxy.conf
*       soft    nofile      1048576
*       hard    nofile      1048576
*       soft    nproc       1048576
*       hard    nproc       1048576
root    soft    nofile      1048576
root    hard    nofile      1048576
EOF

echo "[3/3] Verify settings"
sysctl fs.file-max
sysctl net.core.somaxconn
ulimit -n || true

echo "Kernel tuning applied"
