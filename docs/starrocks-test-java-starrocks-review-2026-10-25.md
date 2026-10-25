# StarRocks 实测仓库（test_java_starrocks）最新进展梳理

- 记录日期：2026-10-25
- 对象仓库：`D:\develops\git\github\java\test_java_starrocks`（GitHub `edidada/test_java_starrocks`，当前分支 `feat/starrocks-monitoring`）
- 最新 commit：`6eec49b` `feat: 新增 sr-demo-yield 端到端半导体报表流程演示`（2026-10-18，somessyy）

## 1. 最新 commit 概要（`6eec49b`）

端到端半导体报表演示，5 个文件 +374 行：

| 变更 | 说明 |
| --- | --- |
| `sr-demo-yield/.../YieldDemo.java`（+305） | 建表（按日分区明细表 / PK die 表 / 遥测 / 维表）→ 批插 324 行 → 窗口函数良率报表 → Stream Load 复测 UPSERT + label 幂等 → OEE 时间桶聚合 → BOM JOIN，每步 `check()` 断言，对 VM108 全绿（`== YIELD DEMO ALL PASS ==`） |
| `docs/starrocks_semiconductor_reporting.md`（+28） | 追加第 6 节 demo 用法 |
| `pom.xml` / `sr-demo-yield/pom.xml` / `sr-smoke-test/pom.xml` | 新模块声明、shade 发行包加入 demo 入口 |

近期 commit 链（新→旧）：`6eec49b` demo → `de2323c` 半导体报表场景文档 → `8eb6c5d` Grafana TUNA 源 → `52ec4fd` 看板数据源 uid → `8c0d24b` Prometheus+Grafana 监控（VM108 systemd）→ `90ec06b` GitHub Actions CI → `908543c` 连接配置外置 → `497c717` 3.5.21 全链路实测 ALL PASS。

## 2. 仓库环境与能力（README/docs 摘要）

- 服务端：Proxmox R630 VM108（192.168.31.162），Ubuntu 26.04，StarRocks 3.5.21 单 FE+单 BE，`replication_num=1`；FE 9030(MySQL/JDBC)/8030(HTTP)，BE 9050(心跳)/8040/8060。
- 模块：`sr-common`（连接配置外置 + `[sql]` 动态代理日志）、`sr-jdbc`（DDL/批插/聚合/PK upsert）、`sr-stream-load`（纯 JDK HttpClient，FE 8030 → 307 → BE 8040，必须 `Expect: 100-continue`）、`sr-smoke-test`（端到端冒烟）、`sr-demo-yield`（最新 demo）。
- 可观测性：客户端 `[sql]` 日志 ↔ FE `fe.audit.log` 对账；Prometheus(9090) 抓 FE/BE/node_exporter，Grafana(3000) 官方 Overview 看板（90+ 面板）。
- 验证结论（2026-10-17/18 实测）：JDBC 1000 行批插+聚合+upsert PASS；Stream Load 3/3 行 Success 且同 label 重发返回 `Label Already Exists`（幂等）；demo 良率 66.67%→100% 复测覆盖、OEE 79.2% 与手算一致。
- 注意点：`ALTER SYSTEM ADD BACKEND` 必须用心跳端口 **9050**；StarRocks `date_trunc('<unit>', ts)` 参数顺序与 Hive/Spark 相反。

## 3. 与 galaxy_mall 的衔接点（备忘）

1. k3s `middleware` 命名空间已有 Doris/StarRocks 类 OLAP 组件（`business-doris-fe/be` 在 worker2），商城的订单/埋点数据可按同样模式入仓出报表。
2. `sr-stream-load` 的 **label 幂等**模式适合商城埋点（`/api/collect`）批量入仓的重试安全。
3. `sr-demo-yield` 的"明细表 + 窗口聚合 + 主键 UPSERT 修正"三件套，可平移为商城的经营报表模型（GMV/转化漏斗类比良率 Bin 分布）。

## 4. 原始记录

`test_java_starrocks` 仓库内的同步存档：`docs/latest-commit-log.md`（该仓库 commit `08f2d39`）。
