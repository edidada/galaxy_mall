# 路线 A：全链路 traceId —— 实机验证报告与踩坑记录

日期：2026-10-17
环境：家庭实验室 k3s 4 节点（master 192.168.31.77 + workers .211/.251/.88），namespace `galaxy-mall`

---

## 1. 结论：同步链路已全打通（实测通过）

### 1.1 商品查询（gateway → user + product）

```
# 请求：GET /api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1
# 头：  X-Trace-Id: trace-verify-1792195610

user     00:06:50.882 [http-nio-8081-exec-10] INFO [trace=trace-verify-1792195610] galaxy.trace - GET /api/users/u100                    status=200 27ms
product  00:06:50.933 [http-nio-8082-exec-9]  INFO [trace=trace-verify-1792195610] galaxy.trace - GET /api/products/sku-iphone           status=200 38ms
gateway  00:06:50.995 [reactor-http-epoll-2]  INFO [trace=trace-verify-1792195610] galaxy.trace - GET /api/gateway/products/sku-iphone   status=200 148ms
```

### 1.2 下单（gateway → user + product + order）

```
# 请求：POST /api/gateway/orders  {"userId":"u100","productId":"sku-iphone","quantity":1,"clientIp":"10.0.0.2"}
# 头：  X-Trace-Id: trace-order-1792198071

user     00:47:51.565  GET /api/users/u100          6ms
product  00:47:51.588  GET /api/products/sku-iphone 7ms   （随后 00:47:52.141 又命中一次 2ms）
order    00:47:51.633  Seata: Load compatible class io.seata.core.context.ContextCore   ← 第三方框架日志也带上了 traceId
order    00:47:53.309  POST /api/orders             1691ms
gateway  00:47:53.327  POST /api/gateway/orders     1921ms
```

### 1.3 支付（gateway → payment → order）

```
# 请求：POST /api/gateway/payments/ORD-1792198072174-1001
# 头：  X-Trace-Id: trace-pay-1792198141

payment  00:49:01.572  Seata: Load compatible class ...
payment  00:49:02.201  POST /api/payments/ORD-...                      657ms
order    00:49:01.919  GET  /api/orders/ORD-...                        255ms
order    00:49:02.167  POST /api/orders/ORD-.../paid                    22ms
gateway  00:49:02.390  POST /api/gateway/payments/ORD-...             1001ms
```

响应头同时回传同一个 traceId（`X-Trace-Id: trace-…`），前端拿到即可展示。

**达成的能力**：给定任意一次请求的 traceId，在各服务日志里 `grep <traceId>` 即可还原
「前端 → 网关 → user / product / order / payment」的完整调用链与每段耗时。

---

## 2. 自己验证的方法（可复现）

```bash
TID="trace-verify-$(date +%s)"

# 商品查询（3 个参数缺一不可）
curl -s -D /tmp/h.txt -H "X-Trace-Id: $TID" \
  "http://10.43.195.149:8080/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1" -o /tmp/b.txt

# 下单
curl -s -X POST -H "Content-Type: application/json" -H "X-Trace-Id: $TID" \
  -d '{"userId":"u100","productId":"sku-iphone","quantity":1,"clientIp":"10.0.0.2"}' \
  "http://10.43.195.149:8080/api/gateway/orders"

# 支付（用下单返回的 orderId）
curl -s -X POST -H "X-Trace-Id: $TID" \
  "http://10.43.195.149:8080/api/gateway/payments/<orderId>"

# 逐服务 grep
for APP in galaxy-mall-gateway galaxy-mall-user galaxy-mall-product \
           galaxy-mall-stock galaxy-mall-order galaxy-mall-payment; do
  echo "=== $APP ==="
  sudo k3s kubectl -n galaxy-mall logs -l app=$APP --tail=400 | grep "$TID"
done
```

> **注意**：`/api/gateway/products/{productId}` 有 **3 个必填参数**（`productId` + `userId` + `clientIp`），
> 缺 `clientIp` 会直接 400。真实可用数据：`userId=u100`、`productId=sku-iphone`
> （见 `scripts/uat_smoke_test.sh`）。若 userId 不存在，网关查用户失败即短路返回 `user-not-found`，
> **不会调用 product**，看不到 product 那一跳 —— 这不是追踪失败。

---

## 3. 踩到的两个坑（都很隐蔽，值得记住）

### 坑 1：响应式 WebFilter 里用 try/finally 清理 MDC = traceId 传不出网关

网关是 WebFlux。最初的写法：

```java
try {
    return chain.filter(exchange);   // 只是"组装" Mono
} finally {
    MDC.remove("traceId");            // ← 立刻执行，此时请求还没真正处理
    TraceContext.clear();
}
```

`chain.filter(exchange)` **只是组装响应式链路**，真正的处理要等订阅后才异步发生。
于是 `finally` 在请求开始瞬间就把 traceId 清掉了，导致：

1. 网关自己的日志没有 traceId；
2. 更严重：`RestClient` 拦截器真正调用下游时，`TraceContext.current()` 已是 `null`，
   **traceId 根本没有传出网关**，下游各自生成新 ID，链路断开。

**正确写法**：用 `Mono.defer` 包裹，让「写入 MDC」发生在真正执行时，清理放到 `doFinally`：

```java
return (exchange, chain) -> Mono.defer(() -> {
    TraceContext.set(traceId);
    MDC.put("traceId", traceId);
    return chain.filter(exchange).doFinally(signal -> {
        logAccess(...);          // 注意：日志必须在清理 MDC 之前输出
        MDC.remove("traceId");
        TraceContext.clear();
    });
});
```

> 顺带一提：日志如果写在 `MDC.remove()` 之后，`[trace=]` 会退化成 `[trace=NO]` —— 顺序很重要。

### 坑 2：把 Feign 拦截器放进共用的自动配置类，会让不用 Feign 的服务起不来

现象：`user` / `product` / `stock` 滚动更新后 `CrashLoopBackOff`（Restarts 27+），日志报：

```
Failed to introspect Class [cn.wdidada.galaxymall.common.tracing.TracingAutoConfiguration]
Caused by: java.lang.ClassNotFoundException: feign.RequestInterceptor
```

原因：这三个服务**不使用 Feign**，classpath 上没有 `feign-core`。
（实测 `mvn dependency:tree`：product 无 `openfeign`；order 有
`spring-cloud-starter-openfeign:4.1.1 → feign-core:13.2.1:compile`。）

而共用的自动配置类里有个返回 `feign.RequestInterceptor` 的 `@Bean`，
Spring 在判定 Bean 类型时会反射 `getDeclaredMethods()` **解析方法签名**，
签名里的 `feign.RequestInterceptor` 缺失就直接炸，服务起不来。

**正确写法**：拆成独立的自动配置类，并加**类级别** `@ConditionalOnClass`：

```java
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class TracingFeignAutoConfiguration { … }
```

关键点：Boot 对登记在 `AutoConfiguration.imports` 的类，是**用 ASM 读注解元数据**评估条件的，
条件不满足时**根本不加载这个类**，自然不会去解析其中的 `feign.*` 类型。
（把条件放在 `@Bean` 方法上没用 —— 那时类已经被加载了。）

> 同理：servlet 过滤器用类级 `@ConditionalOnWebApplication(type = SERVLET)`，
> 才能让 WebFlux 网关不去加载需要 `jakarta.servlet` 的类。
> 另外 `feign-core` / `jakarta.servlet-api` 在 common 里必须是 `provided` —— 它不传递，
> 运行时由真正需要它们的服务自己提供。

---

## 4. 让 traceId 真正"看得见"：访问日志

原项目各服务**默认不打印任何按请求的日志**。只有 MDC 而没有日志输出，traceId 无处显现，
追踪等于没做 —— 这是初版「看起来没效果」的根本原因。

新增专用 logger `galaxy.trace`，每个请求打一行（方法 / 路径 / 状态码 / 耗时）。
级别由环境变量控制（`logback-spring.xml` 里的 `<springProperty>`），**不需要重新构建镜像**：

| 场景 | 设置 |
|---|---|
| 日常排查 / 演示（默认） | `GALAXY_TRACE_LEVEL=INFO` |
| 100w TPS 压测 | `GALAXY_TRACE_LEVEL=OFF`（彻底关闭，避免日志 IO 影响压测结果） |

> 已知副作用：k8s 的健康探针每 10 秒打一次 `/actuator/health/*`，会产生大量无意义的访问日志
> （每个都带一个随机 traceId）。如需干净日志，可在过滤器里跳过 `/actuator/**` 路径。

---

## 5. 部署状态

全部 6 个 Java 服务已构建并滚动发布到含访问日志的版本：

| 服务 | 状态 |
|---|---|
| gateway | ✅ 含响应式修复 + 访问日志 |
| user / product / order / payment / stock | ✅ 已更新，实测 traceId 均正常 |
| front | ⚠️ 未构建（浏览器端注入 `X-Trace-Id` + TraceId 横幅尚未部署，需 node 环境） |

### 遗留待办

1. **异步（MQ）链路尚未串联**：下单/扣库存走消息队列时，消费者是新线程，
   traceId 不会自动延续。要覆盖这类跨线程调用，需要把 traceId 写进消息头并在消费端恢复 MDC。
2. **过滤健康探针日志**，避免访问日志被 `/actuator/**` 刷屏。
3. 构建发布 front，完成端到端「点一下 → 看到 TraceId」。

---

## 6. 发布流程提速：从 ~20 分钟降到 166 秒

原先每次全量发布很慢，根因是**集群里没有可用的镜像仓库**：

- `registry.192.168.31.77.nip.io` 实际未运行（`/v2/` 返回 404，匿名 push 失败）
- 于是只能把镜像 tar（每个 400~687MB，6 个共 2.8GB）物理搬运到每个节点

更糟的是节点之间**没有任何互信**（各节点 `authorized_keys` 里只有你这台 Mac 的公钥），
数据必须 `scp -3` 经 Mac 中转，走两跳；实测 Mac 有效吞吐仅 **~10MB/s**（约 10MB/s ≈ WiFi 瓶颈）。
这种情况下再怎么并行都没用 —— 实测「3 个 worker 并行」依然是每服务 4 分 22 秒，与串行几乎无差。

### 已做的两件事

1. **master ↔ worker 免密**：在 master 生成 `id_ed25519` 密钥对，
   公钥追加到 4 个节点的 `authorized_keys`（各 2 条），节点间可直连。
2. **最小化 sudo 免密**：`/etc/sudoers.d/wdidada-k3s`，内容为
   `wdidada ALL=(ALL) NOPASSWD:/usr/local/bin/k3s`
   —— 只放开 k3s 命令。`sudo -n true` 仍然需要密码（未开放全局权限，已验证）。

### 效果（实测）

| 方式 | 3 个服务分发耗时 |
|---|---|
| 旧：串行 + 经 Mac 中转（`scp -3`） | ≈ 13 分 30 秒 |
| 新：节点间直传 + worker 并行 | **166 秒** |

约 **5 倍提速**，且你的 Mac 不再被占用带宽。

> 注意：`visudo -c` 和 `k3s version` 都不能作为免密校验手段 —— 前者不在 NOPASSWD 范围内，
> 后者 `k3s` 根本没有 `version` 子命令（会返回 exit 3）。
> 可用 `sudo -l -U wdidada` 查看实际特权，或直接跑业务命令（如 `ctr images import`）。
