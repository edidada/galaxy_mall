# 分布式追踪 · 路线 B：可视化（Micrometer Tracing + OpenTelemetry + Jaeger）

> 编写日期：2026-10-17
> 配套文档：`docs/distributed-tracing-plan.md`（路线 A：日志级全链路，本文档是路线 B 的展开版）
> 路线 B 在**路线 A 已打通 `X-Trace-Id + MDC + logback`** 的基础上，叠加标准 APM，让一次点击在 **Jaeger UI** 里以火焰图形式可视化，并支持浏览器→后端端到端关联。

---

## 0. 现状与约束（来自实机/源码，已核实）

| 项 | 事实 |
|---|---|
| 框架 | Spring Boot **3.2.5** / Java 17 / Spring Cloud **2023.0.1** |
| 可观测性基座 | 所有服务 `application.yml` 已 `management.endpoint.web.exposure.include: health,info,prometheus` → **micrometer 已在 classpath** |
| 调用拓扑 | `gateway`(WebFlux + `RestClient`) → `user/product/stock/order/payment`(Feign)；`order → RocketMQ → order` 异步消费 |
| 消息生产端 | `galaxy_mall-order/.../RocketMqOrderMessagePublisher.publish()`：`new Message(topic, body)` + `producer.send(msg)` |
| 消息消费端 | `galaxy_mall-order/.../OrderServiceApplication.rocketMqOrderSubmittedConsumer()`：手动 `DefaultMQPushConsumer` + `MessageListenerConcurrently`，body 即 orderId |
| gateway RestClient | `GatewayApplication.restClient()` 用**裸 `RestClient.builder()`**，Spring Boot 的 `ObservationRestClientFilter` **不会**自动套上去 → 必须显式加 |
| 镜像拉取 | 节点直连 Docker Hub / gcr.io 不通；`docker.io` 走 `docker.1panel.live` 镜像 + `/etc/hosts` IPv4（见 CI/CD 手册 4.1/4.4）；**`quay.io` 未映射** → 优先用 `docker.io` 镜像 |
| 集群内 Grafana | 尚未部署（Prometheus/Grafana 计划见《k3s 部署计划》第 2 章） |

---

## 1. 目标与验收

- [ ] 点「查询商品」→ Jaeger UI 用 traceId 搜到 `gateway → product` 火焰图
- [ ] 点「立即购买」→ 搜到 `gateway → order → (RocketMQ) → order-async-consume` 串联（异步段不断链）
- [ ] 应用日志里 `[traceId spanId]` 与 Jaeger 里的 traceId 一致（日志与链路互查）
- [ ] 前端 DevTools 能看到本次请求的 `traceparent` / `X-Trace-Id`

---

## 2. 组件选型

| 方案 | 说明 | 结论 |
|---|---|---|
| **Jaeger all-in-one** | 自带 Query UI（:16686）+ OTLP 接收（:4317/4318），**单镜像**，无需额外 Grafana | ✅ **首选**（当前没 Grafana，最省事） |
| Grafana Tempo | 只做存储，必须配 Grafana 才能看（当前未部署） | ❌ 暂不选 |
| 自建 OTel Collector | 多一层转发，演示阶段没必要 | ❌ 暂不选（直连 Jaeger OTLP） |

> 不引入 Sleuth（已废弃，Boot 3 用 Micrometer Tracing）。

---

## 3. 应用侧改造（逐模块）

### 3.1 依赖（每个业务服务 `pom.xml` + `galaxy_mall-gateway/pom.xml`）

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

> 版本由 `spring-boot-dependencies` 管理（Boot 3.2.5 自带 `micrometer-tracing` 1.3.x、`opentelemetry` 1.36+），**不要写版本号**。

### 3.2 每服务 `application.yml`（合并进已有的 `management` 块）

```yaml
management:
  tracing:
    sampling:
      probability: 1.0          # 演示/排查用全采样；压测时改 0.01
  otlp:
    tracing:
      endpoint: http://jaeger.monitoring.svc.cluster.local:4318/v1/traces
```

### 3.3 `logback-spring.xml` 加 `spanId`（复用路线 A 的 pattern）

```xml
<property name="CONSOLE_PATTERN"
          value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-}|%X{spanId:-}] %logger{36} - %msg%n"/>
```

> Micrometer Tracing 自动把 `traceId` / `spanId` 写入 MDC（Servlet 服务直接生效；WebFlux 见 3.4）。

### 3.4 gateway（WebFlux）两处要点

**① RestClient 必须显式加观测过滤器**（裸 builder 不自动套）：

```java
@Bean
RestClient userServiceRestClient(
        @Value("${services.user.url}") String baseUrl,
        GatewayTrafficProperties properties,
        ObservationRegistry observationRegistry) {     // 注入
    return restClient(baseUrl, properties, observationRegistry);
}

private RestClient restClient(String baseUrl, GatewayTrafficProperties properties,
                              ObservationRegistry observationRegistry) {
    SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
    rf.setConnectTimeout(properties.getHttp().getConnectTimeoutMs());
    rf.setReadTimeout(properties.getHttp().getReadTimeoutMs());
    return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(rf)
            .filter(new ObservationRestClientFilter(observationRegistry))   // 关键：自动注入 traceparent
            .build();
}
```

**② WebFlux 的 MDC**（reactor 线程切换会丢 `traceId`/`spanId`）：

```java
import reactor.core.publisher.Hooks;
import io.micrometer.context.ContextPropagation;   // Boot 3.2 自带

@PostConstruct
void enableContextPropagation() {
    Hooks.enableAutomaticContextPropagation();
    ContextPropagation.initialize();   // 注册 MDC 的 ThreadLocalAccessor，使 reactor 链路里 MDC 不丢
}
```

> `[待确认]` Boot 3.2 是否在 `reactor` 存在时自动启用 `ContextPropagation`；若已自动，则只保留日志 pattern 即可，不必手写 `Hooks`。

### 3.5 Feign 自动传播（大概率自动，给兜底）

Spring Cloud OpenFeign 在存在 `Tracer` bean 时通常**自动**给 Feign 请求加 W3C `traceparent`。验证方式：部署后看下游服务日志里的 `traceparent` 是否被带上。

若未自动，保留路线 A 的 `FeignTraceInterceptor`，并补一行 W3C 头作为兜底：

```java
// 在 FeignTraceInterceptor.apply() 里，除 X-Trace-Id 外再加：
String tp = buildTraceParent();   // 00-<traceId>-<spanId>-01
if (tp != null) template.header("traceparent", tp);
```

> 主线以自动传播为准；`X-Trace-Id`（路线 A）继续作为业务关联键保留。

### 3.6 RocketMQ 异步链路（最关键，否则断成两条 trace）

#### 3.6.1 生产端 `RocketMqOrderMessagePublisher.publish()`

注入 `io.micrometer.tracing.Tracer`，发消息前把当前 span 写成 W3C `traceparent`：

```java
@Override
public void publish(OrderSubmittedEvent event) {
    try {
        Message message = new Message(topic, event.orderId().getBytes(StandardCharsets.UTF_8));
        TraceContext ctx = tracer.currentTraceContext().context();
        if (ctx != null) {
            message.putUserProperty("traceparent",
                String.format("00-%s-%s-01", ctx.traceId(), ctx.spanId()));
        }
        SendResult sendResult = producer.send(message);
        if (sendResult == null) throw new IllegalStateException("rocketmq-send-result-null");
    } catch (Exception e) { ... }
}
```

#### 3.6.2 消费端 `OrderServiceApplication.rocketMqOrderSubmittedConsumer`

在 `consumeMessage` 里从消息属性恢复父 span，再起子 span 包裹业务处理：

```java
consumer.registerMessageListener(new MessageListenerConcurrently() {
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            String orderId = new String(message.getBody(), StandardCharsets.UTF_8);
            Span span = continueFromTraceparent(message.getUserProperty("traceparent"))
                       .name("rocketmq-consume").start();
            try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                rocketMqOrderAsyncConsumer.handle(new OrderSubmittedEvent(orderId));
            } finally { span.end(); }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
});
```

`continueFromTraceparent` 实现（解析 W3C，重建父 `TraceContext`）：

```java
private Span continueFromTraceparent(String tp) {
    if (tp == null || !tp.startsWith("00-")) return tracer.nextSpan().name("rocketmq-consume");
    String[] p = tp.split("-");                       // 00, traceId(32), spanId(16), flags
    long tHi = Long.parseUnsignedLong(p[1].substring(0, 16), 16);
    long tLo = Long.parseUnsignedLong(p[1].substring(16), 16);
    long sId = Long.parseUnsignedLong(p[2], 16);
    TraceContext parent = TraceContext.newBuilder().traceId(tHi, tLo).spanId(sId).build();
    return tracer.nextSpan(TraceContextOrSamplingFlags.create(parent));
}
```

> 这样 `gateway → order` 的 trace，会在 Kafka/RocketMQ 投递后**续接**到 `order-async-consume`，UI 里是一条完整链路而不是两条。

### 3.7 前端（可选，端到端到浏览器）

`front/src/api.js`：在路线 A 生成 `X-Trace-Id` 的基础上，再生成 W3C `traceparent` 一起发出（同源代理，无 CORS 问题）：

```js
function traceParent() {
    const tid = crypto.getRandomValues(new Uint8Array(16));
    const sid = crypto.getRandomValues(new Uint8Array(8));
    const hex = a => Array.from(a, b => b.toString(16).padStart(2, '0')).join('');
    return `00-${hex(tid)}-${hex(sid)}-01`;
}
// fetch 时：headers['traceparent'] = traceParent(); headers['X-Trace-Id'] = newTraceId();
```

浏览器→gateway 这一跳的 trace 就与后端同一条 traceId，Jaeger 里直接搜浏览器生成的 traceId 即可。

---

## 4. 部署 Jaeger（k3s，namespace `monitoring`）

### 4.1 镜像预热（4 节点，绕开 250 KiB/s）

```bash
# 选一台有 docker 的机器（Mac 需先启动 Docker Desktop + 配 mirror，或 VM107 Ubuntu）
docker pull docker.io/jaegertracing/all-in-one:1.66
docker save -o /tmp/jaeger.tar docker.io/jaegertracing/all-in-one:1.66

for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  scp /tmp/jaeger.tar wdidada@$ip:/tmp/
  ssh wdidada@$ip "echo '<sudo密码>' | sudo -S /usr/local/bin/k3s ctr images import /tmp/jaeger.tar"
done
```

> `[待确认]` `1.66` tag 是否存在；拿不准用 `:latest`（但 latest 不利于回滚，建议锁版本）。
> 走 `docker.io/...` 才会命中 `registries.yaml` 的镜像（不要写 `jaegertracing/...` 或 `quay.io/...`）。

### 4.2 清单（Deployment + Service）

```yaml
apiVersion: v1
kind: Namespace
metadata: { name: monitoring }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: jaeger, namespace: monitoring }
spec:
  replicas: 1
  selector: { matchLabels: { app: jaeger } }
  template:
    metadata: { labels: { app: jaeger } }
    spec:
      containers:
        - name: jaeger
          image: docker.io/jaegertracing/all-in-one:1.66
          args: ["--memory.max-traces=50000"]
          env:
            - { name: SPAN_STORAGE_TYPE, value: memory }
            - { name: COLLECTOR_OTLP_ENABLED, value: "true" }
          ports:
            - { containerPort: 16686, name: ui }
            - { containerPort: 4317,  name: otlp-grpc }
            - { containerPort: 4318,  name: otlp-http }
          resources:
            requests: { memory: 512Mi, cpu: 200m }
            limits:   { memory: 1Gi,   cpu: "1" }
---
apiVersion: v1
kind: Service
metadata: { name: jaeger, namespace: monitoring }
spec:
  selector: { app: jaeger }
  ports:
    - { name: ui,       port: 16686, targetPort: 16686 }
    - { name: otlp-grpc, port: 4317,  targetPort: 4317 }
    - { name: otlp-http, port: 4318,  targetPort: 4318 }
  type: ClusterIP
---
apiVersion: v1
kind: Service
metadata: { name: jaeger-ui-np, namespace: monitoring }
spec:
  selector: { app: jaeger }
  type: NodePort
  ports:
    - { name: ui, port: 16686, targetPort: 16686, nodePort: 30686 }
```

> 内存用 `memory` 存储（演示足够）；要持久化加 ≤5Gi PVC（节点磁盘：master 24G / worker1 23G / worker2,3 各 29G，紧张，按需）。
> `ClusterIP jaeger:4318` 即应用侧 `management.otlp.tracing.endpoint` 指向的地址。

### 4.3 验证

```bash
kubectl -n monitoring get pod,svc
curl -s -o /dev/null -w "%{http_code}\n" --noproxy '*' -m 5 http://192.168.31.77:30686/
# 期望 200
```

---

## 5. 端到端验证

```bash
# 1) 先确认链路通（带 traceparent 打进网关）
TP="00-$(python3 -c 'import os;print(os.urandom(16).hex())')-$(python3 -c 'import os;print(os.urandom(8).hex())')-01"
curl -s -o /dev/null -w "%{http_code}\n" --noproxy '*' -m 6 \
  -H "Host: mall.192.168.31.77.nip.io" -H "traceparent: $TP" \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

# 2) 浏览器打开 Jaeger UI
open http://192.168.31.77:30686/
# → Search → Service = galaxy-mall-gateway → 找刚才的 trace → 看火焰图

# 3) 异步链路：点「立即购买」后，在 Jaeger 里看是否出现
#    gateway → order → rocketmq-consume 的连续 span（不断成两条）

# 4) 日志互查：取 Jaeger 里的 traceId，到 Pod 日志 grep
kubectl -n galaxy-mall logs -l app=galaxy-mall-gateway --since=2m | grep <traceId>
kubectl -n galaxy-mall logs -l app=galaxy-mall-order    --since=2m | grep <traceId>
# 期望日志里 [traceId|spanId] 与 Jaeger 一致
```

---

## 6. 风险与 `[待确认]`

| 风险 / 项 | 说明 / 对策 |
|---|---|
| WebFlux MDC 是否自动 | `[待确认]` Boot 3.2 是否自动 `ContextPropagation`；否则手写 `Hooks.enableAutomaticContextPropagation()`（3.4②） |
| 裸 RestClient 不带 trace | gateway 必须显式加 `ObservationRestClientFilter`（3.4①），否则 gateway→下游的 span 不连 |
| Feign 是否自动传播 | 大概率自动；若否，用 3.5 兜底加 `traceparent` |
| Jaeger 镜像 tag | `[待确认]` `1.66` 是否存在，否则 `:latest` |
| `quay.io` 未映射 | 用 `docker.io/jaegertracing/all-in-one`，**不要**写 `quay.io/...` 或短名（会直连失败） |
| 全采样压测开销 | `probability: 1.0` 仅排查用；100w TPS 压测改 `0.01`，否则 trace 序列化放大延迟 |
| 重建镜像 | 每个业务服务 + gateway 都要加依赖重新构建、4 节点 `ctr import`、`rollout`（同路线 A 流程） |
| Jaeger 内存存储 | 重启即丢；演示可接受，长期需接 ES/Cassandra 或 Tempo+对象存储 |

---

## 7. 与路线 A 的关系

- 路线 A 已把 `X-Trace-Id`（自定义头）+ MDC + `logback-spring.xml` 打通——**路线 B 依赖 A 的日志基座**。
- B 启用后，MDC 自动多出 `spanId`，日志 pattern 加 `%X{spanId}` 即可与 A 复用，无需重写日志采集。
- A 的「grep traceId 看日志」与 B 的「Jaeger 火焰图」互为补充：日志看细节文本，Jaeger 看拓扑与耗时。
- 前端 `X-Trace-Id`（A）继续保留作业务关联键；B 额外加 `traceparent` 作标准链路键。
