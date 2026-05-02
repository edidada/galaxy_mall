# 高并发架构审查建议（100w+ TPS）

## 1. 限流层问题

### 1.1 TokenBucketRateLimiter — synchronized 单锁瓶颈
**文件**: `galaxy_mall-gateway/.../TokenBucketRateLimiter.java:19`  
**问题**: `synchronized tryAcquire()` 是单实例全局锁，100w TPS 下所有线程串行竞争，吞吐上限约 10w~20w TPS。  
**建议**: 改用 `AtomicLong` + CAS 无锁实现，或直接换 Guava `RateLimiter`（内部已是无锁令牌桶）。

### 1.2 WindowRateLimiter — 计数器重置竞态
**文件**: `galaxy_mall-gateway/.../WindowRateLimiter.java:23`  
**问题**: `second.compareAndSet` 成功后 `counter.set(0)` 不是原子操作，两个线程可能同时通过 CAS 并都执行 `counter.set(0)`，导致计数丢失，实际放行量超过阈值。  
**建议**: 将 second + counter 合并为一个 `AtomicReference<WindowState>` 做整体 CAS，或使用滑动窗口（`ConcurrentHashMap<Long, AtomicInteger>` 按秒分桶）。

### 1.3 LocalRateGuard — 同样的重置竞态
**文件**: `galaxy_mall-stock/.../LocalRateGuard.java:20`  
**问题**: 与 WindowRateLimiter 相同的 CAS + set(0) 竞态，在高并发下计数器可能被多次清零。  
**建议**: 同上，合并为原子状态对象。

---

## 2. 缓存层问题

### 2.1 MultiLevelCache — 无驱逐机制，内存泄漏
**文件**: `galaxy_mall-cache/.../MultiLevelCache.java:12`  
**问题**: L1/L2/L3 均为无界 `ConcurrentHashMap`，过期 entry 只在 `get` 时惰性判断，从不主动清除。双11 百万商品 SKU 场景下会持续占用堆内存直至 OOM。  
**建议**: 引入定时清扫线程（`ScheduledExecutorService`），或直接换 Caffeine（支持 `expireAfterWrite` + 自动驱逐）。

### 2.2 MultiLevelCache — 缓存击穿（无互斥锁）
**文件**: `galaxy_mall-cache/.../MultiLevelCache.java:20`  
**问题**: 热点 key 过期瞬间，大量并发请求同时 miss，全部穿透到数据库（缓存击穿）。  
**建议**: 对热点 key 加 `ConcurrentHashMap<K, CompletableFuture<V>>` 的 singleflight 机制，同一 key 只有一个请求回源。

### 2.3 BloomFilterGuard — 哈希质量差，误判率高
**文件**: `galaxy_mall-cache/.../BloomFilterGuard.java:35`  
**问题**: `value.hashCode() * seed` 使用 Java 默认 `hashCode()`，分布不均匀；3 个 seed 的独立性不足，实际误判率远高于理论值。  
**建议**: 改用 Guava `BloomFilter`（MurmurHash3，误判率可配置），或实现 double-hashing：`h1 + i*h2`。

### 2.4 BloomFilterGuard — 非线程安全
**文件**: `galaxy_mall-cache/.../BloomFilterGuard.java:7`  
**问题**: `BitSet` 不是线程安全的，并发 `add` 和 `mightContain` 存在数据竞争。  
**建议**: 换 `java.util.concurrent.atomic.AtomicIntegerArray` 实现，或加读写锁。

---

## 3. 库存层问题

### 3.1 RedisLuaStockBackend.confirm — Redis 预占与 DB 扣减不一致
**文件**: `galaxy_mall-stock/.../RedisLuaStockBackend.java:70`  
**问题**: `confirm` 先执行 `stockLedgerRepository.decreaseWithLock`（DB），成功后再执行 `confirmScript`（Redis 清除预占）。若 DB 成功但 Redis 调用失败，预占 key 永久残留，导致库存虚减。  
**建议**: 将 Redis confirm 脚本放在 DB 操作之前，或引入本地事务补偿日志，定时对账清理孤立预占。

### 3.2 RedisLuaStockBackend.preload — 无原子性保证
**文件**: `galaxy_mall-stock/.../RedisLuaStockBackend.java:38`  
**问题**: `preload` 直接 `SET` 覆盖，若多个实例并发 preload 同一商品，最后写入的值覆盖其他实例已处理的预占，导致库存数据错误。  
**建议**: 使用 `SET NX`（仅首次写入）或在 Lua 脚本中加版本号 CAS。

---

## 4. 等待室问题

### 4.1 WaitingRoomService — tickets 无界增长
**文件**: `galaxy_mall-gateway/.../WaitingRoomService.java:14`  
**问题**: `tickets` Map 中的 entry 只在 `canPass` 返回 true 时删除。若用户拿到 token 后不再轮询（断网、关页面），ticket 永久驻留内存。  
**建议**: 给 Ticket 加 `expireAt`（readyAtMillis + 容忍窗口），定时清扫过期 ticket 并归还 `size` 计数。

### 4.2 WaitingRoomService — size 计数与 tickets Map 不一致
**文件**: `galaxy_mall-gateway/.../WaitingRoomService.java:27`  
**问题**: `size.incrementAndGet()` 和 `tickets.put()` 不是原子操作，并发场景下可能出现 size 已增但 put 尚未完成，或 size 超限后 decrementAndGet 与其他线程的 incrementAndGet 交错，导致实际容量超出 `capacity`。  
**建议**: 用 `synchronized` 或 `ReentrantLock` 保护 enter 的整个判断+写入逻辑，或改用 `Semaphore` 管理容量。

---

## 5. 订单层问题

### 5.1 OrderAsyncConsumer — 无重试上限保护
**文件**: `galaxy_mall-order/.../OrderAsyncConsumer.java:43`  
**问题**: `asyncAttempts` 字段虽然存在，但消费者逻辑中未检查上限，若消息队列持续重投（RocketMQ 默认重试 16 次），同一订单可能多次扣减库存。  
**建议**: 在 `handle` 开头检查 `attempts >= MAX_ATTEMPTS`，超限直接进死信队列并告警。

### 5.2 OrderAsyncConsumer — reserve 成功后 confirm 失败未回滚 Redis 预占
**文件**: `galaxy_mall-order/.../OrderAsyncConsumer.java:64`  
**问题**: confirm 失败时调用了 `stockClient.release`，但 release 本身也可能失败（网络超时），此时 Redis 预占永久残留。  
**建议**: release 失败时写入补偿日志，由独立的对账任务定期扫描并重试释放。

---

## 6. 数据库分片问题

### 6.1 JdbcShardedOrderStore — DB shard 与 table shard 使用同一 hashCode
**文件**: `galaxy_mall-db/.../JdbcShardedOrderStore.java:109`  
**问题**: `shard = hashCode % shards.size()`，`table = hashCode % tableShards`。当 `shards.size() == tableShards` 时，同一 orderId 永远路由到 shard[i] 的 table[i]，其他 shard 的其他 table 永远为空，分片失效。  
**建议**: table shard 使用二次哈希：`table = (hashCode / shards.size()) % tableShards`，确保两个维度独立分布。

### 6.2 JdbcShardedOrderStore.findAll — 全表扫描，生产不可用
**文件**: `galaxy_mall-db/.../JdbcShardedOrderStore.java:78`  
**问题**: `findAll` 遍历所有 shard 和 table 做全量查询，数据量大时会打爆数据库连接池和内存。  
**建议**: 生产环境禁用此接口，或改为分页查询 + 游标，仅供运维/对账使用。

---

## 7. 稳定性与可观测性

### 7.1 服务间调用无熔断
**问题**: `OrderAsyncConsumer` 通过 `ProductClient` / `StockClient` 调用下游，无熔断（Circuit Breaker）保护。下游超时会导致消费者线程池耗尽，引发级联雪崩。  
**建议**: 引入 Resilience4j `CircuitBreaker` + `TimeLimiter`，或在 Feign 客户端配置 fallback。

### 7.2 无服务注册中心
**问题**: 各服务通过硬编码 URL 互调，无法动态扩缩容，K8s Pod 重启后 IP 变化导致调用失败。  
**建议**: 接入 Nacos 或 Kubernetes Service DNS，Feign 客户端改为服务名寻址。

### 7.3 MetricsCollector 仅计数，无延迟分布
**问题**: 当前 metrics 只有 `increment` 计数，缺少 P99 延迟、队列深度等关键指标，无法定位性能瓶颈。  
**建议**: 接入 Micrometer + Prometheus，暴露 `/actuator/prometheus`，补充 histogram 指标。

---

## 优先级汇总

| 优先级 | 问题 | 影响 |
|--------|------|------|
| P0 | TokenBucketRateLimiter synchronized 锁 | 直接限制吞吐上限 |
| P0 | MultiLevelCache 无驱逐 | OOM 风险 |
| P0 | BloomFilterGuard 非线程安全 | 数据竞争 |
| P0 | Redis confirm 与 DB 扣减不一致 | 库存数据错误 |
| P1 | WindowRateLimiter / LocalRateGuard 重置竞态 | 限流失效 |
| P1 | WaitingRoomService tickets 泄漏 | 内存泄漏 |
| P1 | OrderAsyncConsumer 无重试上限 | 重复扣库存 |
| P1 | DB shard 与 table shard 同 hashCode | 分片失效 |
| P2 | 缓存击穿无 singleflight | 数据库压力 |
| P2 | 无熔断保护 | 级联雪崩风险 |
| P2 | 无服务注册中心 | 扩缩容不可用 |
| P2 | findAll 全表扫描 | 生产不可用 |
