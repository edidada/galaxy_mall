#!/bin/bash
set -euo pipefail

unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
export no_proxy="192.168.31.171,localhost,127.0.0.1"
export NO_PROXY="$no_proxy"

URL="${1:-http://192.168.31.171:30897/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1}"
TOTAL="${2:-10000}"
CONCURRENCY="${3:-200}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

start_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')

seq "$TOTAL" | xargs -I{} -P "$CONCURRENCY" sh -c '
  code=$(curl --noproxy "*" --connect-timeout 3 --max-time 10 -s -o /dev/null -w "%{http_code}" "'"$URL"'" || echo "CURLERR")
  echo "$code" >> "'"$tmpdir"'/codes.txt"
'

end_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')

elapsed_ms=$((end_ms - start_ms))
if [ "$elapsed_ms" -le 0 ]; then
  elapsed_ms=1
fi

success=$(grep -c '^200$' "$tmpdir/codes.txt" || true)
curlerr=$(grep -c '^CURLERR$' "$tmpdir/codes.txt" || true)
fail=$((TOTAL - success))

python3 -c "total=$TOTAL; success=$success; fail=$fail; curlerr=$curlerr; elapsed_ms=$elapsed_ms; tps=total/(elapsed_ms/1000.0); print(f'TOTAL={total}'); print(f'SUCCESS={success}'); print(f'FAIL={fail}'); print(f'CURLERR={curlerr}'); print(f'ELAPSED_MS={elapsed_ms}'); print(f'TPS={tps:.2f}')"
