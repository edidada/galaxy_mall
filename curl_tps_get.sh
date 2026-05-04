
  #!/bin/bash
  set -euo pipefail

  URL="${1:-http://192.168.31.171:30897/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1}"
  TOTAL="${2:-10000}"
  CONCURRENCY="${3:-200}"

  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' EXIT

  start_ms=$(python3 - <<'PY'
  import time
  print(int(time.time() * 1000))
  PY
  )

  seq "$TOTAL" | xargs -I{} -P "$CONCURRENCY" sh -c '
    code=$(curl -s -o /dev/null -w "%{http_code}" "'"$URL"'")
    echo "$code" >> "'"$tmpdir"'/codes.txt"
  '

  end_ms=$(python3 - <<'PY'
  import time
  print(int(time.time() * 1000))
  PY
  )

  elapsed_ms=$((end_ms - start_ms))
  success=$(grep -c '^200$' "$tmpdir/codes.txt" || true)
  fail=$((TOTAL - success))

  python3 - <<PY
  total = $TOTAL
  success = $success
  fail = $fail
  elapsed_ms = max($elapsed_ms, 1)
  tps = total / (elapsed_ms / 1000.0)
  print(f"TOTAL={total}")
  print(f"SUCCESS={success}")
  print(f"FAIL={fail}")
  print(f"ELAPSED_MS={elapsed_ms}")
  print(f"TPS={tps:.2f}")
  PY
