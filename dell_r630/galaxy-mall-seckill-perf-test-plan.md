# Galaxy Mall Seckill k3s Deployment And Performance Test Plan

## Scope
- Deploy `galaxy_mall-seckill` into the current single-node k3s cluster in `default` namespace.
- Expose seckill through `gateway` as `/edge/seckill/**`.
- Verify two things separately:
  - submit-path throughput for `POST /edge/seckill/requests`
  - end-to-end business completion for asynchronous order creation and status polling

## Deployment Assumptions
- Current branch: `k3s_uat_8080`
- Current live cluster: single-node k3s on `192.168.31.171`
- Current application convention: Java services listen on `8080` inside k3s
- Current seckill implementation stores request state in memory, so `seckill` should stay at `1` replica for now

## Deployment Changes
- Add `seckill` deployment and service to `dell_r630/deploy-services.yaml`
- Add `SERVICES_SECKILL_URL=http://seckill:8080` to `gateway`
- Add gateway route:
  - `/edge/seckill/** -> /api/seckill/**`
- Add `galaxy-mall-seckill` image build to `deploy/docker/build-k3s-images.sh`

## Recommended Deploy Steps
1. Build image tar:
```bash
IMAGE_TAG=local APP_VERSION=1.0-SNAPSHOT ./deploy/docker/build-k3s-images.sh
```
2. Import image into k3s:
```bash
sudo k3s ctr images import deploy/docker/output/galaxy-mall-seckill-local.tar
```
3. Apply workload manifest:
```bash
sudo /usr/local/bin/k3s kubectl apply -f dell_r630/deploy-services.yaml
```
4. Verify route and service:
```bash
sudo /usr/local/bin/k3s kubectl get deploy,svc -n default | grep seckill
curl -i http://<gateway-node-ip>:<gateway-node-port>/edge/seckill/requests/not-exists
```

## Test Data
- Product id: `sku-seckill`
- Quantity: `1`
- User id must be unique per request batch because current service has per-user rate limit and duplicate request reuse
- Client IP must also be randomized to avoid IP limiter dominating the result

## Test Objectives
- Submit API availability: `>= 99.9%`
- Submit API p99 latency:
  - `<= 50ms` at `5k RPS`
  - `<= 100ms` at `10k RPS`
  - `<= 200ms` at `20k RPS`
- Error ratio:
  - infrastructure errors `5xx + connect timeout` should stay `< 0.5%`
  - business rejections such as `rate-limited` or `queue-full` should be counted separately

## Phase 1: Warm-Up
Warm product and route caches before pressure:

```bash
curl http://<gateway-node-ip>:<gateway-node-port>/edge/products/sku-seckill
for i in $(seq 1 1000); do
  curl -s -o /dev/null http://<gateway-node-ip>:<gateway-node-port>/edge/products/sku-seckill
done
```

## Phase 2: wrk2 Submit Throughput Test
Use `wrk2` only for the submit path. This phase measures front-door acceptance capacity, not business completion.

### Command Template
```bash
wrk -t24 -c2000 -d60s -R10000 \
  -s dell_r630/wrk2_seckill_submit.lua \
  http://<gateway-node-ip>:<gateway-node-port>/edge/seckill/requests
```

### Rate Ladder
1. `R=5000`
2. `R=10000`
3. `R=20000`
4. `R=30000`
5. Stop when p99 sharply degrades or queue-full grows continuously

### Metrics To Record
- achieved requests/sec
- p50 / p90 / p99 latency
- non-2xx ratio
- gateway CPU / memory
- seckill CPU / memory
- order CPU / memory
- product CPU / memory
- Prometheus metrics:
  - `seckill.success`
  - `seckill.failed`
  - `seckill.queue.full`
  - `seckill.rate.limited`
  - `seckill.order-refresh.exception`

## Phase 3: JMeter End-To-End Business Test
Use JMeter for asynchronous business validation because it can chain `submit -> poll status`.

### Thread Group Design
- Group A: submit requests to `POST /edge/seckill/requests`
- Group B: poll `GET /edge/seckill/requests/${requestId}` until terminal state
- CSV data:
  - `userId`
  - `clientIp`
  - `productId=sku-seckill`
  - `quantity=1`

### Success Criteria
- terminal completion ratio `>= 95%`
- `SUCCESS + FAILED + SOLD_OUT` terminal states must converge within `5s`
- no long tail accumulation of `PROCESSING` requests

## Phase 4: Direct Service Isolation Test
If gateway becomes bottleneck, test seckill service directly through service NodePort or temporary port-forward:

```bash
sudo /usr/local/bin/k3s kubectl port-forward -n default svc/seckill 38080:8080
wrk -t16 -c1000 -d60s -R10000 \
  -s dell_r630/wrk2_seckill_submit.lua \
  http://127.0.0.1:38080/api/seckill/requests
```

## Key Risks
- Current seckill request repository is in-memory, so horizontal scaling will break status lookup consistency unless state is externalized
- `stock` service is currently unstable on the live single-node cluster; if it remains `CrashLoopBackOff`, end-to-end seckill success rate will be distorted
- Single-node k3s cannot represent true distributed ceiling; results should be treated as single-node baseline only

## Result Recording Template
| Phase | Endpoint | Rate | Success Ratio | p99 | Queue Full | 5xx | Notes |
|---|---|---:|---:|---:|---:|---:|---|
| Warm-up | product | - | - | - | - | - | |
| Submit | gateway seckill | 5000 | | | | | |
| Submit | gateway seckill | 10000 | | | | | |
| Submit | gateway seckill | 20000 | | | | | |
| E2E | gateway seckill | 2000 users/s | | | | | |

## Exit Criteria
- Gateway route works
- `seckill` pod is `Running`
- Prometheus can scrape `seckill`
- Submit-path pressure reaches a stable rate level
- End-to-end polling shows terminal convergence without backlog explosion
