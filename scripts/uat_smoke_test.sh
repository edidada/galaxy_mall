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
queue_token=""
for attempt in 1 2 3 4 5 6 7 8; do
  queued_body="$(post_json "$BASE_URL/api/gateway/orders" "{\"userId\":\"queue-u1\",\"productId\":\"sku-iphone\",\"quantity\":1,\"clientIp\":\"$CLIENT_IP_PREFIX.9\"}")"
  if [[ "$queued_body" == *'"status":"QUEUED"'* ]]; then
    queue_token="$(extract_json_string "$queued_body" "queueToken")"
    break
  fi
done

[[ -n "$queue_token" ]] || fail "queue flow did not return queueToken"
sleep 2

log "replay queued request"
admitted_body="$(post_json "$BASE_URL/api/gateway/orders" "{\"userId\":\"queue-u1\",\"productId\":\"sku-iphone\",\"quantity\":1,\"clientIp\":\"$CLIENT_IP_PREFIX.9\",\"queueToken\":\"$queue_token\"}")"
admitted_order_id="$(extract_json_string "$admitted_body" "orderId")"
[[ -n "$admitted_order_id" ]] || fail "queued replay missing orderId"
await_status "$admitted_order_id" "WAITING_PAYMENT" >/dev/null

log "all checks passed"
