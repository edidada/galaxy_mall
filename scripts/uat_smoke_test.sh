#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
USER_ID="${USER_ID:-u100}"
CLIENT_IP_PREFIX="${CLIENT_IP_PREFIX:-10.20.30}"

log() {
  printf '[uat-smoke] %s\n' "$1"
}

fail() {
  printf '[uat-smoke][FAIL] %s\n' "$1" >&2
  exit 1
}

extract_json_string() {
  local body="$1"
  local key="$2"
  printf '%s' "$body" | grep -o "\"$key\":\"[^\"]*\"" | head -n1 | sed 's/.*:"//;s/"$//'
}

assert_contains() {
  local body="$1"
  local expected="$2"
  [[ "$body" == *"$expected"* ]] || fail "response missing: $expected body=$body"
}

request() {
  curl -fsS "$@"
}

post_json() {
  local url="$1"
  local body="$2"
  request -H 'Content-Type: application/json' -X POST "$url" -d "$body"
}

await_status() {
  local order_id="$1"
  local expected="$2"
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    local body
    body="$(request "$BASE_URL/api/gateway/orders/$order_id")"
    if [[ "$body" == *"\"status\":\"$expected\""* ]]; then
      printf '%s' "$body"
      return 0
    fi
    sleep 1
  done
  fail "order $order_id did not reach $expected"
}

log "check health"
health_body="$(request "$BASE_URL/actuator/health/readiness")"
assert_contains "$health_body" '"status":"UP"'

log "check prometheus"
prometheus_body="$(request "$BASE_URL/actuator/prometheus")"
assert_contains "$prometheus_body" 'jvm_threads_live_threads'

log "query product"
product_body="$(request "$BASE_URL/api/gateway/products/sku-iphone?userId=$USER_ID&clientIp=$CLIENT_IP_PREFIX.1")"
assert_contains "$product_body" '"productId":"sku-iphone"'

log "submit order"
order_body="$(post_json "$BASE_URL/api/gateway/orders" "{\"userId\":\"$USER_ID\",\"productId\":\"sku-iphone\",\"quantity\":1,\"clientIp\":\"$CLIENT_IP_PREFIX.2\"}")"
assert_contains "$order_body" '"success":true'
order_id="$(extract_json_string "$order_body" "orderId")"
[[ -n "$order_id" ]] || fail "missing orderId"

log "await WAITING_PAYMENT"
await_status "$order_id" "WAITING_PAYMENT" >/dev/null

log "pay order"
payment_body="$(request -X POST "$BASE_URL/api/gateway/payments/$order_id")"
assert_contains "$payment_body" '"status":"SUCCESS"'

log "trigger queue flow"
# 等待室按 userId 每秒窗口限流（UAT 环境阈值 8/s），单用户必须并发才能触发排队；
# 排队用户必须是 user 服务里已存在的账号（播种账号只有 u100/u101/u200/u300），
# 否则网关在 user-not-found 校验就直接拒绝，永远到不了等待室。
queue_user="${QUEUE_USER:-u101}"
queue_token=""
queue_work="$(mktemp)"
for attempt in 1 2 3; do
  : > "$queue_work"
  seq 1 32 | xargs -P32 -I{} curl -fsS \
    -H 'Content-Type: application/json' -X POST \
    "$BASE_URL/api/gateway/orders" \
    -d "{\"userId\":\"$queue_user\",\"productId\":\"sku-iphone\",\"quantity\":1,\"clientIp\":\"$CLIENT_IP_PREFIX.9\"}" \
    >> "$queue_work" || true
  queue_token="$(extract_json_string "$(cat "$queue_work")" "queueToken" || true)"
  [[ -n "$queue_token" ]] && break
  sleep 1
done
rm -f "$queue_work"

[[ -n "$queue_token" ]] || fail "queue flow did not return queueToken"
sleep 2

log "replay queued request"
admitted_body="$(post_json "$BASE_URL/api/gateway/orders" "{\"userId\":\"$queue_user\",\"productId\":\"sku-iphone\",\"quantity\":1,\"clientIp\":\"$CLIENT_IP_PREFIX.9\",\"queueToken\":\"$queue_token\"}")"
admitted_order_id="$(extract_json_string "$admitted_body" "orderId")"
[[ -n "$admitted_order_id" ]] || fail "queued replay missing orderId"
await_status "$admitted_order_id" "WAITING_PAYMENT" >/dev/null

log "all checks passed"
