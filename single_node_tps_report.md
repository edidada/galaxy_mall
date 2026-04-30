# Single Node TPS Report

Generated: `2026-04-30 12:01:17`

## Goal

Run a **single Java process** and measure hot-path throughput for this project without pretending that single-process in-memory throughput equals end-to-end distributed throughput.

## Environment

- Java: `17.0.12`
- OS: `Mac OS X 12.7.6`
- CPU cores visible to JVM: `8`
- OS arch: `x86_64`
- Warmup seconds: `1`
- Measure seconds: `6`
- Worker threads: `8`

## Method

- `id-generator`: pure in-process ceiling for ID generation.
- `order-repository`: order creation plus in-memory shard repository save/find/update.
- `stock-reserve-confirm`: in-memory inventory reserve and confirm path.
- `waiting-room-enter-pass`: waiting-room enqueue and pass path.

## Results

| Benchmark | Description | Ops | Throughput (ops/s) | 10M TPS |
|---|---|---:|---:|---|
|id-generator|Theoretical in-process ceiling for order id generation|91906635|15317772.50|reached|
|order-repository|Single-JVM order object creation plus shard repository save/find/update|4191295|698549.17|not reached|
|stock-reserve-confirm|Single-JVM inventory reserve and confirm path using InMemoryStockBackend|13401400|2233566.67|not reached|
|waiting-room-enter-pass|Single-JVM waiting-room enter and pass path|1358386|226397.67|not reached|

## Interpretation

- A result `>= 10,000,000 ops/s` here only means the **single-JVM hot path** reached ten-million-class throughput.
- It does **not** prove that HTTP + gateway + cache + MQ + DB full-chain throughput reached 10M TPS.
- This report is appropriate for validating single-process algorithmic ceilings and lock contention, not distributed production capacity.

  本机实测结果是：

    - id-generator: 15,317,772.50 ops/s，达到千万级
    - order-repository: 698,549.17 ops/s
    - stock-reserve-confirm: 2,233,566.67 ops/s
    - waiting-room-enter-pass: 226,397.67 ops/s
