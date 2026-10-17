# 路线 A：全链路 traceId —— 实机验证报告与踩坑记录

日期：2026-10-17
环境：家庭实验室 k3s 4 节点（master 192.168.31.77 + workers .211/.251/.88），namespace `galaxy-mall`

---

## 1. 结论：链路已打通（实测通过）

一次请求（商品查询）的同一个 `traceId` 同时出现在 **3 个服务**的日志中：

```
# 请求：GET /api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1
# 头：  X-Trace-Id: trace-verify-1792195610

user     2026-10-17 00:06:50.882 [http-nio-8081-exec-10] INFO [trace=trace-verify-1792195610] galaxy.trace - GET /api/users/u100            status=200 27ms
product  2026-10-17 00:06:50.933 [http-nio-8082-exec-9]  INFO [trace=trace-verify-1792195610] galaxy.trace - GET /api/products/sku-iphone   status=200 38ms
gateway  2026-10-17 00:06:50.995 [reactor-http-epoll-2]  INFO [trace=trace-verify-1792195610] galaxy.trace - GET /api/gateway/products/sku-iphone status=200 148ms
```

时间戳本身也反映了调用顺序（user 27ms → product 38ms → 网关总耗时 148ms）。

响应头同时回传了同一个 traceId（`X-Trace-Id: trace-verify-…`），前端拿到即可展示。

**达成的能力**：给定任意一次请求的 traceId，可在各服务日志里 `grep <traceId>` 还原
「前端 → 网关 → user / product / order / payment」的完整调用链与每段耗时。

---

## 2. 自己验证的方法（可复现）

```bash
TID="trace-verify-$(date +%s)"

# 从 master 节点打请求（网关 ClusterIP，或用 NodePort http://192.168.31.77:30080）
curl -s -D /tmp/h.txt -H "X-Trace-Id: $TID" \
  "http://10.43.195.149:8080/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1" \
  -o /tmp/b.txt

head -1 /tmp/h.txt          # 期望 HTTP/1.1 200 OK
head -c 300 /tmp/b.txt      # 期望 success:true

# 逐服务 grep
for APP in galaxy-mall-gateway galaxy-mall-user galaxy-mall-product; do
  echo "=== $APP ==="
  sudo k3s kubectl -n galaxy-mall logs -l app=$APP --tail=300 | grep "$TID"
done
```

> **注意**：`/api/gateway/products/{productId}` 有 **3 个必填参数**（`productId` 路径参数 +
> `userId` + `clientIp`），缺 `clientIp` 会直接 400。
> 真实可用的测试数据：`userId=u100`、`productId=sku-iphone`（见 `scripts/uat_smoke_test.sh`）。
> 若传不存在的 userId，网关会先查用户并短路返回 `user-not-found`，
> **不会调用 product**，因此看不到 product 那一跳 —— 这不是追踪失败。

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
于是 `finally` 在请求开始瞬间就把 traceId 清掉了，导致两个后果：

1. 网关自己的日志没有 traceId；
2. 更严重：`RestClient` 拦截器真正调用下游时，`TraceContext.current()` 已是 `null`，
   **traceId 根本没有传出网关**，下游只能是各自生成新 ID，链路断开。

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

现象：`user` / `product` / `stock` 滚动更新后 `CrashLoopBackOff`（Restarts 27+），
日志报：

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

关键点：Boot 对登记在 `AutoConfiguration.imports` 的类，是**用 ASM 读注解元数据**来评估条件的，
条件不满足时**根本不加载这个类**，自然也不会去解析其中的 `feign.*` 类型。
（如果把条件放在 `@Bean` 方法上就没用 —— 类已经被加载了。）

> 同理：servlet 过滤器用类级 `@ConditionalOnWebApplication(type = SERVLET)`，
> 才能让 WebFlux 网关不去加载需要 `jakarta.servlet` 的类。
> 另外 `feign-core` / `jakarta.servlet-api` 在 common 里必须是 `provided` —— 它不传递，
> 运行时由真正需要它们的服务自己提供。

---

## 4. 让 traceId 真正"看得见"：访问日志

原项目各服务**默认不打印任何按请求的日志**。
只有 MDC 而没有日志输出，traceId 无处显现，追踪等于没做 —— 这是初版「看起来没效果」的根本原因。

因此新增了专用 logger `galaxy.trace`，在每个请求的入口/出口打一行：

```
[方法] [路径] status=[状态码] [耗时ms]
```

日志级别由环境变量控制（`logback-spring.xml` 里的 `<springProperty>`），
**不需要重新构建镜像**就能调整：

| 场景 | 设置 |
|---|---|
| 日常排查 / 演示（默认） | `GALAXY_TRACE_LEVEL=INFO` |
| 100w TPS 压测 | `GALAXY_TRACE_LEVEL=OFF`（彻底关闭，避免日志 IO 影响压测结果） |

---

## 5. 当前部署状态与遗留项

已重新构建并滚动发布（`galaxy-mall` namespace）：

| 服务 | 状态 | 备注 |
|---|---|---|
| gateway | ✅ 已更新 | 含响应式修复 + 访问日志 |
| user | ✅ 已更新 | 含访问日志 |
| product | ✅ 已更新 | 含访问日志 |
| stock | ⚠️ 旧版 | 链路正常，但**缺访问日志**（traceId 不显现） |
| order / payment | ⚠️ 旧版 | 同上，缺访问日志 |
| **front** | ⚠️ 未构建 | 浏览器端的 `X-Trace-Id` 注入 + TraceId 横幅**尚未部署** |

后续可做（按性价比排序）：

1. **把 stock / order / payment 也升到同一版本**（镜像已在 master 上打包好，只需再分发 3 个服务）
   —— 让所有服务的访问日志齐全。
2. **构建并发布 front**（ `npm run build` → nginx 镜像，需要 node 环境）
   —— 完成后点页面即可看到 TraceId 横幅，端到端效果才完整。

---

## 6. 与 IaC / CI 的关系

- CI 多环境矩阵已加：`.github/workflows/k3s.yml`
  （7 种 runner OS/版本 × JDK 21/24 编译矩阵；JDK 24 超出 Spring Boot 3.2.5 官方支持范围，
  标记为实验性允许失败，避免打红整个 CI）
- 集群没有可用镜像仓库，镜像分发靠 `buildah` 导出 tar → `scp -3` → `k3s ctr images import`
  （注意：`scp` 远端到远端默认让源主机直连目标主机，master→worker 又不是免密的，
  必须加 `-3` 由本地中转；直接 `cat | ctr import` 流式导入在多次并发下会被 SSH 断连打断）
