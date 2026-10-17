# 分布式追踪落地手册（路线 A：日志级全链路 → 后续叠加 B：可视化）

> 编写日期：2026-10-17
> 目标：点击 `http://192.168.31.77:30080/` 页面上的按钮后，能用**一个 traceId** 把请求在
> `front → gateway → product/user/stock/order/payment` 的日志全部串起来。
> 路线 A 不引入任何新组件（无 Jaeger/Zipkin），半天内可验证。

---

## 0. 现状：为什么现在追不了（实测证据）

| 环节 | 现状 | 证据 |
|---|---|---|
| 前端 | `fetch` 不带任何 trace 头 | `front/src/api.js` 全部请求无自定义头 |
| 网关响应头 | 源码里 `TraceGlobalFilter` 会写 `X-Trace-Id`，但**实测响应头里没有** | `curl -D -` 只有 `Content-Type/Length/Date`，无 `x-trace-id` |
| 下游服务 | 没有任何服务从请求头读 `X-Trace-Id` | 全仓只有 gateway 有 Filter；带 `X-Trace-Id: demo-trace-001` 打进去，三个服务日志命中数均为 **0** |
| `TraceContext` | 仅 `ThreadLocal`，无人从 HTTP 头填充 | `galaxy_mall-common/.../TraceContext.java` |
| `TraceLogger` | 日志写进**内存 List**，`snapshot()` 未暴露端点 | `galaxy_mall-monitor/.../TraceLogger.java` → `kubectl logs` 永远看不到 |
| 日志格式 | 无 logback 配置、无 MDC、`logging.pattern` 为空 | 全仓搜索 `logback*`/`%X{`/`MDC` 命中 0（Java 侧） |
| APM 依赖 | 无 Micrometer Tracing / OTel / Sleuth / Jaeger | 依赖搜索确认 |

调用方式（决定改造点）：

- **gateway → 下游**：`RestClient`（`GatewayApplication.restClient(...)`，Spring 6 同步客户端）
- **服务 → 服务**：Feign（`common/client/` 的 `@FeignClient`：Product/User/Order/Payment/StockClient）
- **gateway `/edge/**` 路由**：Spring Cloud Gateway 转发，默认保留请求头（含 `X-Trace-Id`）

---

## 1. 路线 A 目标与验收

**目标**：一次点击 → 页面上显示 Trace-Id → 用它在各服务日志里 grep 到全链路。

**验收标准**

- [ ] 点「查询商品」按钮，页面显示 `Trace-Id: <uuid>`
- [ ] `kubectl logs -n galaxy-mall -l app=galaxy-mall-gateway --since=2m | grep <traceId>` 命中 > 0
- [ ] `kubectl logs -n galaxy-mall -l app=galaxy-mall-product  --since=2m | grep <traceId>` 命中 > 0
- [ ] 点「立即购买」→ gateway / order / stock 日志都能 grep 到同一个 traceId
- [ ] 响应头里能拿到 `X-Trace-Id`（浏览器 DevTools 可验证）

---

## 2. 改造清单

### 2.1 阶段 1（最小闭环，优先做完验证）

只动：`common` + `gateway` + `product` + `front`。
验证「查询商品」这条最短链路：`front → gateway → product`。

### 2.2 阶段 2（推广）

把同样的 `@Bean` 与 `logback-spring.xml` 复制到 `user / stock / order / payment / seckill`。

### 2.3 阶段 3（可选，异步链路）

RocketMQ 异步段（order 消费）天然断链，需把 traceId 放进消息体，消费端再写入 MDC。

---

## 3. 代码改造（精确到文件）

### 3.1 `galaxy_mall-common`：新增 trace 支持类

**① `galaxy_mall-common/pom.xml` 加依赖**（Servlet Filter 与 MDC 需要）

```xml
<dependency>
  <groupId>jakarta.servlet</groupId>
  <artifactId>jakarta.servlet-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-api</artifactId>
</dependency>
```

> 版本由父 pom / Spring Boot BOM 管理，不要手写版本号。

**② `common/trace/TraceSupport.java`**

```java
package cn.wdidada.galaxymall.common.trace;

public final class TraceSupport {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private TraceSupport() {}
}
```

**③ `common/trace/TraceServletFilter.java`**（Servlet 服务用，WebFlux 环境下不注册）

```java
package cn.wdidada.galaxymall.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public final class TraceServletFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TraceSupport.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TraceSupport.MDC_KEY, traceId);
        response.setHeader(TraceSupport.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceSupport.MDC_KEY);
        }
    }
}
```

**④ `common/trace/FeignTraceInterceptor.java`**（Feign 调下游时透传）

```java
package cn.wdidada.galaxymall.common.trace;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

public final class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TraceSupport.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            template.header(TraceSupport.HEADER, traceId);
        }
    }
}
```

> Spring Cloud OpenFeign 会自动收集容器里的 `RequestInterceptor` Bean，注册为 `@Bean` 即可生效，无需改 `@FeignClient`。

### 3.2 各 Servlet 服务：注册 Bean + 日志格式

**⑤ 每个服务的 `*Application.java` 加两个 Bean**（以 product 为例）

```java
@Bean
TraceServletFilter traceServletFilter() {
    return new TraceServletFilter();
}

@Bean
FeignTraceInterceptor feignTraceInterceptor() {
    return new FeignTraceInterceptor();
}
```

阶段 1 只加 `ProductServiceApplication`；阶段 2 再加 `user / stock / order / payment / seckill`。

**⑥ 每个服务 `src/main/resources/logback-spring.xml`**（新建）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <property name="CONSOLE_PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-}] %logger{36} - %msg%n"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${CONSOLE_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
    <logger name="cn.wdidada.galaxymall" level="INFO"/>
</configuration>
```

> 容器里 stdout 是唯一日志出口，`CONSOLE` appender 即可，`kubectl logs` 能直接看到 `[<traceId>]`。

### 3.3 gateway（WebFlux，特殊处理）

gateway 是 Spring Cloud Gateway（Reactor），**不能用 Servlet Filter**，且 MDC 在 Reactor 线程切换下不可靠。做法：

**⑦ 修 `TraceGlobalFilter`：让响应头真的返回**

现状实测响应头缺失，改成在提交前写入：

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String traceId = exchange.getRequest().getHeaders().getFirst(TraceSupport.HEADER);
    if (traceId == null || traceId.isBlank()) {
        traceId = UUID.randomUUID().toString();
    }
    final String finalTraceId = traceId;
    ServerWebExchange traced = exchange.mutate()
            .request(builder -> builder.header(TraceSupport.HEADER, finalTraceId))
            .build();
    traced.getResponse().beforeCommit(() -> {
        traced.getResponse().getHeaders().set(TraceSupport.HEADER, finalTraceId);
        return Mono.empty();
    });
    return chain.filter(traced);
}
```

**⑧ `RestClient` 调下游时透传（显式传参，最可靠）**

`GatewayController` 用 `@RequestHeader` 取（filter 已注入请求头），传给 service：

```java
@GetMapping("/products/{productId}")
public ApiResponse<ProductView> queryProduct(
        @PathVariable String productId,
        @RequestParam String userId,
        @RequestParam String clientIp,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId
) {
    return gatewayWorkflowService.queryProduct(userId, productId, clientIp, traceId);
}
```

`GatewayWorkflowService` 对应方法加 `String traceId` 参数，调用处加头：

```java
return productServiceRestClient.get()
        .uri("/api/products/{productId}", productId)
        .header("X-Trace-Id", traceId == null ? "" : traceId)
        .retrieve()
        ...
```

（`placeOrder` / `getOrder` / `pay` 同样处理。）

**⑨ gateway 自身日志**：WebFlux 下 MDC 不可靠，改用普通日志打印 traceId：

```java
log.info("[{}] gateway -> product {}", traceId, productId);
```

> 取舍：gateway 侧不强求 MDC，只要它**把 traceId 正确传给下游**即可；下游（Servlet + MDC）日志里会有完整 traceId。

### 3.4 前端：生成 → 透传 → 展示

**⑩ `front/src/api.js`**：统一请求函数，每次请求生成 traceId 并带上，同时暴露最后一次的 traceId

```js
export let lastTraceId = '';

function newTraceId() {
    return (crypto?.randomUUID?.() ?? 't-' + Date.now() + '-' + Math.random().toString(16).slice(2));
}

async function request(url, options = {}) {
    const traceId = newTraceId();
    lastTraceId = traceId;
    const res = await fetch(url, {
        ...options,
        headers: { ...(options.headers || {}), 'X-Trace-Id': traceId }
    });
    return res;
}
```

然后把所有 `fetch(...)` 换成 `request(...)`（GET 类：`request(url)`；POST 类：`request(url, { method:'POST', headers:{...}, body })`）。

> front 是**同源**代理（:30080），加自定义头不会触发 CORS 预检，无需改 nginx。

**⑪ `front/src/App.vue`**：点按钮后把 traceId 显示出来

- `data()` 增加 `lastTraceId: ''`
- `fetchProduct()` / `quickBuy()` 等方法里：`this.lastTraceId = api.lastTraceId;`
- 模板里在结果区展示：

```html
<div v-if="lastTraceId" class="result">
  <h4>Trace-Id（去日志里 grep 这个）</h4>
  <pre>{{ lastTraceId }}</pre>
</div>
```

---

## 4. 构建与发布

### 4.1 构建（当前环境约束）

- Mac 有 `mvn 3.6.1` + `jdk 17`，可本地打包；
- Mac 有 Docker 29（Rancher Desktop），但**daemon 当前未运行**（`Cannot connect to the Docker daemon`）→ 需先启动；
- 基础镜像 `eclipse-temurin:17-jre` 在 docker.io，节点与 Mac 直连均不通 → **必须给 Docker 配 registry mirror**（`https://docker.1panel.live`；若 DNS 只回 IPv6，需在本机 `/etc/hosts` 绑 IPv4，见 CI/CD 手册 4.1 坑①）；
- 备选构建机：VM107（Ubuntu 26.04，12C/24G）。

```bash
# 1) 打包
mvn -q -DskipTests package

# 2) 构建镜像（7 个：gateway/user/product/stock/order/payment + front）
IMAGE_TAG=trace1 ./deploy/docker/build-k3s-images.sh
# 注：该脚本里 seckill 端口写的是 8080（应为 8086），按《k3s 部署计划》1.2 先修
```

### 4.2 导入镜像（4 台节点都要，否则调度到缺镜像的节点会 ErrImagePull）

```bash
for ip in 192.168.31.77 192.168.31.211 192.168.31.251 192.168.31.88; do
  ssh wdidada@$ip "mkdir -p /tmp/img"
  scp deploy/docker/output/*-trace1.tar wdidada@$ip:/tmp/img/
  ssh wdidada@$ip "for f in /tmp/img/*.tar; do echo '<sudo密码>' | sudo -S /usr/local/bin/k3s ctr images import \$f; done"
done
```

> 校验：`ssh wdidada@$ip "sudo k3s ctr images ls | grep trace1"`（4 台都要能看到）。

### 4.3 更新 Deployment

```bash
# 只更新需要的服务；front 与业务服务分开
kubectl -n galaxy-mall set image deploy/galaxy-mall-gateway gateway=registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/gateway:trace1
kubectl -n galaxy-mall set image deploy/galaxy-mall-product product=registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/product:trace1
kubectl -n galaxy-mall set image deploy/front front=registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/front:trace1
kubectl -n galaxy-mall rollout status deploy/galaxy-mall-gateway deploy/galaxy-mall-product deploy/front
```

> 镜像 tag 从 `k3s` 换成 `trace1`；`imagePullPolicy: IfNotPresent` 保持不变（registry 服务已不存在，不能改成 Always）。

### 4.4 回滚

```bash
kubectl -n galaxy-mall set image deploy/galaxy-mall-gateway gateway=registry.192.168.31.77.nip.io/galaxy-mall/galaxy-mall/gateway:k3s
kubectl -n galaxy-mall rollout undo deploy/<name>
```

---

## 5. 验证步骤

**① curl 先验证（不依赖前端）**

```bash
TID=$(uuidgen | tr 'A-Z' 'a-z')
echo "traceId=$TID"
curl -s -D - -o /dev/null --noproxy '*' -m 6 -H "Host: mall.192.168.31.77.nip.io" \
  -H "X-Trace-Id: $TID" \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1" | grep -i x-trace-id
```

期望：响应头出现 `x-trace-id: $TID`（阶段 1 前这一步是没有的）。

**② 查日志**

```bash
kubectl -n galaxy-mall logs -l app=galaxy-mall-gateway --since=2m | grep "$TID"
kubectl -n galaxy-mall logs -l app=galaxy-mall-product  --since=2m | grep "$TID"
```

期望：两边都命中，且日志行里带 `[$TID]`。

**③ 浏览器验证**

1. 打开 `http://192.168.31.77:30080/`
2. 点「查询商品」→ 页面显示 Trace-Id
3. DevTools → Network → 该请求 → Response Headers 里有 `x-trace-id`
4. 复制 Trace-Id 到上面 grep 命令里复查

---

## 6. 路线 B（A 跑通后再叠加）：可视化

> ⚠️ 路线 B 已展开成独立文档：**`docs/distributed-tracing-visualization.md`**（含逐模块的依赖/配置/代码、Jaeger 部署清单、异步链路续 span、端到端验收）。
> 下面只保留要点索引，细节以独立文档为准。

1. 依赖：`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`（Spring Boot 3.2.5 管理版本，不写版本号）
2. 配置（每服务 `application.yml`）：
   ```yaml
   management:
     tracing:
       sampling.probability: 1.0
     otlp.tracing.endpoint: http://jaeger.monitoring.svc.cluster.local:4318/v1/traces
   ```
   Micrometer Tracing 自动把 `traceId/spanId` 写入 MDC → 日志 pattern 加 `%X{spanId}` 即可与 A 平滑衔接。
3. 部署 **Jaeger all-in-one**（自带 UI :16686，无需额外 Grafana；OTLP 4317/4318 直连，无需 Collector）：
   - 镜像用 **`docker.io/jaegertracing/all-in-one`**（命中 `registries.yaml` 镜像）；**不要用 `quay.io`**（未映射）或 gcr.io
   - NodePort 暴露 UI（30686），内存存储演示足够；PVC 建议 ≤5Gi（节点磁盘：master 24G / worker1 23G / worker2,3 各 29G）
4. 异步链路（RocketMQ）是重点：生产端 `RocketMqOrderMessagePublisher` 把当前 span 写成 W3C `traceparent` 放进消息属性；消费端 `OrderServiceApplication.rocketMqOrderSubmittedConsumer` 恢复父 span 续接（`docs/distributed-tracing-visualization.md` §3.6 有完整代码）
5. gateway 是 WebFlux：裸 `RestClient.builder()` 不会自动带 trace，必须显式加 `ObservationRestClientFilter`；MDC 需 `ContextPropagation`（§3.4）
6. 前端可升级为 W3C `traceparent`（与 B 端标准对齐），A 阶段的 `X-Trace-Id` 保留作业务关联键

---

## 7. 风险与注意事项

| 风险 | 对策 |
|---|---|
| Mac Docker daemon 未运行、基础镜像拉不到 | 先启动 Rancher Desktop 并配 mirror；不行就改用 VM107 构建 |
| 镜像只导入部分节点 | 4 台全导；否则调度到缺镜像节点会 `ErrImagePull` |
| imagePullPolicy 改成 Always | 绝对不要——registry 服务已不存在，必然拉失败 |
| gateway 是 WebFlux，MDC 不可靠 | 不强求 gateway 日志带 MDC，只保证向下游透传；日志用普通 `log.info` 带 traceId |
| Feign 拦截器未生效 | 确认 `FeignTraceInterceptor` 是 `@Bean`（OpenFeign 自动收集）；只在有 `@EnableFeignClients` 的服务生效 |
| 异步 MQ 链路断 | 阶段 3 处理：消息体带 traceId，消费端写入 MDC |
| 改 7 个服务工作量大 | 先做阶段 1 最小闭环（gateway + product + front），验证通过再批量推广 |
| 日志量增大 | 采样/按需开 DEBUG；`%X{traceId:-}` 缺省显示 `-`，不影响可读 |
