# galaxy_mall 存储层现状与改造方向

> 记录日期：2026-11-01
> 结论先行：当前代码里**只有订单走了 MySQL 分库分表**，user/product/stock 账本/payment 全是进程内存，属于有意的 demo 定位，但已实际引发过线上-style 问题（详见第 2 节后果）。

## 1. 各模块存储现状

| 模块 | 存储 | 代码证据 | 生命周期 |
| --- | --- | --- | --- |
| order | ✅ MySQL 分库分表：`galaxy_order_0/1`（各 4 张 `t_order_0..3`，启动时 `create table if not exists` 自动建） | `GALAXY_ORDER_STORE_BACKEND=jdbc-sharded` → `JdbcShardedOrderStore`；按 `orderId.hashCode()` 取模路由 2 库 × 4 表 | 持久 |
| user | ❌ 进程内存 `ConcurrentHashMap` | `galaxy_mall-db/UserRepository`；种子账号写死在 `UserServiceApplication`（u100/u101/u200/u300） | 重启即回种子 |
| product | ❌ 进程内存种子 + 静态页 | `ProductShowcaseSeeds`（静态种子 + 静态 HTML） | 重启回种子 |
| stock | ❌ Redis（库存数/预留 Lua）+ **进程内存账本** | `RedisLuaStockBackend` + `StockLedgerRepository`（内存）；真正决定 confirm 成败的是内存账本 | Redis 持久，账本重启重播种 |
| payment | ❌ 无独立表 | 支付直接推进订单状态；退款记录在内存 `RefundRecordRepository` | 隐含持久（挂靠订单） |
| order 旁路日志 | ❌ 进程内存模拟 binlog | `LocalBinlogRepository` 内存环形日志，供补偿任务扫描 | 重启丢弃 |

定位依据：`AccountService` javadoc 自述"演示级账号与会话服务，刻意不做生产级鉴权"；中间件依赖报告（middleware-dependencies-test-report）确认只有 order 用 MySQL、stock 用 Redis。

## 2. 已实际发生的问题（不是理论风险）

| 问题 | 现象 | 根因 |
| --- | --- | --- |
| 冒烟脚本排队用户不存在 | `queue-u1` 报 `user-not-found` | user 内存种子里没有该账号；动态注册的账号 Pod 重启即消失 |
| stock confirm 失败 → 订单 FAILED | 补库存后新订单仍全 FAILED | 每个 stock Pod 的内存账本 `sku-iphone` 只播种 10，耗尽后 confirm 走 `db-confirm-failed`，与 Redis 事实脱节 |
| 多副本数据分裂 | 双 stock 副本各自一份账本、双 gateway 各自一份等待室票据 | 内存状态无法跨副本共享，"confirm 成败取决于请求落在哪个副本" |
| 等待室重放失败 | 直连单 Pod 才能冒烟通过 | `WaitingRoomService` 票据在网关进程内存 |

## 3. 改造方向（按优先级）

1. **user → `t_user`（本次已改造）**：user 模块加 JDBC 后端，`galaxy.user.store-backend=jdbc` 时自动建表/播种（INSERT IGNORE 语义，不覆盖运行期新账号新昵称），本地内存后端保留供单测。
2. **stock 账本 → `t_stock_ledger`（本次已改造）**：抽 `StockLedger` 接口，新增 MySQL 实现（`SELECT ... FOR UPDATE` 扣减），`galaxy.stock.ledger-backend=jdbc`；根治 confirm 失败与多副本分裂。
3. **payment → `t_payment`（待做）**：支付流水持久化（`order_id` 幂等键，与订单同分片路由或单库），`PAID/REFUNDED` 状态审计可查。
4. **product 主数据 → `t_product`（待做）**：商品目录落库 + 缓存，静态页生成保留。
5. **等待室票据 → Redis（待做）**：网关票据迁 Redis 后多副本重放才真正成立；这是网关无状态化的前置条件。
6. 编译期**幂等播种**原则：所有种子数据"不存在才插入"，重启不覆盖运行期数据（本次 user/stock 改造均已遵循）。

## 4. 配置开关速查

| 开关 | 默认 | JDBC 取值 | 作用范围 |
| --- | --- | --- | --- |
| `GALAXY_USER_STORE_BACKEND` | local | `jdbc` | user 模块用户存储 |
| `GALAXY_USER_DB_URL/USERNAME/PASSWORD` | 内置默认 | MySQL 连接串 | 同上 |
| `GALAXY_STOCK_LEDGER_BACKEND` | local | `jdbc` | stock 内存账本 |
| `GALAXY_STOCK_DB_URL/USERNAME/PASSWORD` | 复用 order 分库配置 | MySQL 连接串 | 同上 |
| `GALAXY_ORDER_STORE_BACKEND` | local | `jdbc-sharded` | 订单分库（原有，未改） |
