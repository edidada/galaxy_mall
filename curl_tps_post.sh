#!/bin/bash
set -euo pipefail

unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
export no_proxy="192.168.31.77,192.168.31.211,192.168.31.251,192.168.31.88,mall.192.168.31.77.nip.io,localhost,127.0.0.1"
export NO_PROXY="$no_proxy"

# 默认走 front 的 NodePort（同源转发 /api/gateway，不需要 Host 头、不需要 DNS 解析）
# ⚠️ 旧默认值 http://192.168.31.171:30897 已失效（.171 是 Proxmox 宿主，不承载 k3s 端口）
# 压测建议显式传 Ingress 地址：
#   ./curl_tps_post.sh "http://mall.192.168.31.77.nip.io/api/gateway/orders"
#   前提：echo "192.168.31.77 mall.192.168.31.77.nip.io" | sudo tee -a /etc/hosts
URL="${1:-http://192.168.31.77:30080/api/gateway/orders}"
TOTAL="${2:-2000}"
CONCURRENCY="${3:-100}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

start_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')

seq "$TOTAL" | xargs -I{} -P "$CONCURRENCY" sh -c '
  i="$1"
  uid="u$((100000 + i))"
  ip="10.0.$((i % 250)).$(((i % 200) + 1))"
  body=$(printf "{\"userId\":\"%s\",\"productId\":\"sku-iphone\",\"quantity\":1,\"clientIp\":\"%s\",\"queueToken\":null}" "$uid" "$ip")
  code=$(curl --noproxy "*" --connect-timeout 3 --max-time 10 -s -o /dev/null -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "$body" \
    "'"$URL"'" || echo "CURLERR")
  echo "$code" >> "'"$tmpdir"'/codes.txt"
' _ {}

end_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')

elapsed_ms=$((end_ms - start_ms))
if [ "$elapsed_ms" -le 0 ]; then
  elapsed_ms=1
fi

success=$(grep -c '^200$' "$tmpdir/codes.txt" || true)
curlerr=$(grep -c '^CURLERR$' "$tmpdir/codes.txt" || true)
fail=$((TOTAL - success))

python3 -c "total=$TOTAL; success=$success; fail=$fail; curlerr=$curlerr; elapsed_ms=$elapsed_ms; tps=total/(elapsed_ms/1000.0); print(f'TOTAL={total}'); print(f'SUCCESS={success}'); print(f'FAIL={fail}'); print(f'CURLERR={curlerr}'); print(f'ELAPSED_MS={elapsed_ms}'); print(f'TPS={tps:.2f}')"
