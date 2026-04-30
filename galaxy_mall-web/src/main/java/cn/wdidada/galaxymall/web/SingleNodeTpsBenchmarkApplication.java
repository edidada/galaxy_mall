package cn.wdidada.galaxymall.web;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.stock.InMemoryStockBackend;
import cn.wdidada.galaxymall.stock.StockBackend;
import cn.wdidada.galaxymall.gateway.WaitingRoomService;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class SingleNodeTpsBenchmarkApplication {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String PRODUCT_ID = "sku-benchmark";
    private static final int BENCHMARK_STOCK = 200_000_000;

    public static void main(String[] args) throws Exception {
        BenchmarkOptions options = BenchmarkOptions.parse(args);
        BenchmarkReport report = runBenchmarks(options);
        Path reportPath = Path.of(options.reportFile());
        Files.writeString(reportPath, report.renderMarkdown());
        System.out.println(report.renderConsoleSummary());
        System.out.println("report=" + reportPath.toAbsolutePath());
    }

    private static BenchmarkReport runBenchmarks(BenchmarkOptions options) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        results.add(runIdGeneratorBenchmark(options));
        results.add(runOrderRepositoryBenchmark(options));
        results.add(runStockReserveConfirmBenchmark(options));
        results.add(runWaitingRoomBenchmark(options));
        return new BenchmarkReport(options, results);
    }

    private static BenchmarkResult runIdGeneratorBenchmark(BenchmarkOptions options) throws Exception {
        return execute(
                "id-generator",
                "Theoretical in-process ceiling for order id generation",
                options,
                () -> new BenchmarkWorker() {
                    @Override
                    public void runOnce(long sequence) {
                        IdGenerator.nextOrderId();
                    }
                }
        );
    }

    private static BenchmarkResult runOrderRepositoryBenchmark(BenchmarkOptions options) throws Exception {
        return execute(
                "order-repository",
                "Single-JVM order object creation plus shard repository save/find/update",
                options,
                () -> {
                    OrderRepository repository = new OrderRepository();
                    return new BenchmarkWorker() {
                        @Override
                        public void runOnce(long sequence) {
                            Order order = new Order(
                                    "BENCH-" + sequence + '-' + ThreadLocalRandom.current().nextInt(10_000),
                                    "u" + (sequence & 1023),
                                    PRODUCT_ID,
                                    1,
                                    9999,
                                    false
                            );
                            repository.save(order);
                            repository.updateStatus(order.orderId(), cn.wdidada.galaxymall.common.domain.OrderStatus.WAITING_PAYMENT);
                            repository.findById(order.orderId());
                        }
                    };
                }
        );
    }

    private static BenchmarkResult runStockReserveConfirmBenchmark(BenchmarkOptions options) throws Exception {
        return execute(
                "stock-reserve-confirm",
                "Single-JVM inventory reserve and confirm path using InMemoryStockBackend",
                options,
                () -> {
                    StockLedgerRepository stockLedgerRepository = new StockLedgerRepository();
                    stockLedgerRepository.seed(PRODUCT_ID, BENCHMARK_STOCK);
                    MetricsCollector metricsCollector = new MetricsCollector();
                    StockBackend stockBackend = new InMemoryStockBackend(stockLedgerRepository, metricsCollector, Integer.MAX_VALUE);
                    stockBackend.preload(PRODUCT_ID);
                    return new BenchmarkWorker() {
                        @Override
                        public void runOnce(long sequence) {
                            String reservationId = "RSV-" + sequence + '-' + ThreadLocalRandom.current().nextInt(10_000);
                            if (!stockBackend.reserve(reservationId, PRODUCT_ID, 1).success()) {
                                throw new IllegalStateException("reserve-failed");
                            }
                            if (!stockBackend.confirm(reservationId).success()) {
                                throw new IllegalStateException("confirm-failed");
                            }
                        }
                    };
                }
        );
    }

    private static BenchmarkResult runWaitingRoomBenchmark(BenchmarkOptions options) throws Exception {
        return execute(
                "waiting-room-enter-pass",
                "Single-JVM waiting-room enter and pass path",
                options,
                () -> {
                    WaitingRoomService waitingRoomService = new WaitingRoomService(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    return new BenchmarkWorker() {
                        @Override
                        public void runOnce(long sequence) {
                            String businessKey = "u" + sequence + "|sku|" + sequence;
                            WaitingRoomService.WaitingTicket ticket = waitingRoomService.enter(businessKey);
                            waitingRoomService.canPass(businessKey, ticket.token());
                        }
                    };
                }
        );
    }

    private static BenchmarkResult execute(
            String name,
            String description,
            BenchmarkOptions options,
            Supplier<BenchmarkWorker> workerSupplier
    ) throws Exception {
        runPhase(options.warmupSeconds(), options.threads(), workerSupplier, false);
        PhaseResult measured = runPhase(options.durationSeconds(), options.threads(), workerSupplier, true);
        double throughput = measured.operations() / (double) options.durationSeconds();
        return new BenchmarkResult(name, description, options.threads(), options.durationSeconds(), measured.operations(), throughput);
    }

    private static PhaseResult runPhase(
            int durationSeconds,
            int threads,
            Supplier<BenchmarkWorker> workerSupplier,
            boolean countOperations
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        LongAdder operationCounter = new LongAdder();
        long deadlineNanos = System.nanoTime() + durationSeconds * 1_000_000_000L;
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(new WorkerTask(startLatch, deadlineNanos, workerSupplier.get(), operationCounter, countOperations)));
        }
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();
        return new PhaseResult(operationCounter.sum());
    }

    private record WorkerTask(
            CountDownLatch startLatch,
            long deadlineNanos,
            BenchmarkWorker worker,
            LongAdder operationCounter,
            boolean countOperations
    ) implements Callable<Void> {

        @Override
        public Void call() throws Exception {
            startLatch.await();
            long sequence = 0L;
            while (System.nanoTime() < deadlineNanos) {
                worker.runOnce(sequence++);
                if (countOperations) {
                    operationCounter.increment();
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface BenchmarkWorker {
        void runOnce(long sequence) throws Exception;
    }

    private record PhaseResult(long operations) {
    }

    private record BenchmarkResult(
            String name,
            String description,
            int threads,
            int durationSeconds,
            long operations,
            double throughputPerSecond
    ) {

        private boolean reachedTenMillionTps() {
            return throughputPerSecond >= 10_000_000.0d;
        }
    }

    private record BenchmarkOptions(int warmupSeconds, int durationSeconds, int threads, String reportFile) {

        private static BenchmarkOptions parse(String[] args) {
            Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    continue;
                }
                int split = arg.indexOf('=');
                values.put(arg.substring(2, split), arg.substring(split + 1));
            }
            int threads = Integer.parseInt(values.getOrDefault("threads", String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors()))));
            int warmupSeconds = Integer.parseInt(values.getOrDefault("warmupSeconds", "2"));
            int durationSeconds = Integer.parseInt(values.getOrDefault("durationSeconds", "8"));
            String reportFile = values.getOrDefault("reportFile", "single_node_tps_report.md");
            return new BenchmarkOptions(warmupSeconds, durationSeconds, threads, reportFile);
        }
    }

    private record BenchmarkReport(BenchmarkOptions options, List<BenchmarkResult> results) {

        private String renderConsoleSummary() {
            StringBuilder builder = new StringBuilder();
            builder.append("single-node benchmark\n");
            for (BenchmarkResult result : results) {
                builder.append(result.name())
                        .append(": ")
                        .append(String.format("%.2f", result.throughputPerSecond()))
                        .append(" ops/s")
                        .append(result.reachedTenMillionTps() ? " [>=10M]" : " [<10M]")
                        .append('\n');
            }
            return builder.toString();
        }

        private String renderMarkdown() {
            Runtime runtime = Runtime.getRuntime();
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            StringBuilder builder = new StringBuilder();
            builder.append("# Single Node TPS Report\n\n");
            builder.append("Generated: `").append(TIME_FORMATTER.format(Instant.now())).append("`\n\n");
            builder.append("## Goal\n\n");
            builder.append("Run a **single Java process** and measure hot-path throughput for this project without pretending that single-process in-memory throughput equals end-to-end distributed throughput.\n\n");
            builder.append("## Environment\n\n");
            builder.append("- Java: `").append(System.getProperty("java.version")).append("`\n");
            builder.append("- OS: `").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append("`\n");
            builder.append("- CPU cores visible to JVM: `").append(runtime.availableProcessors()).append("`\n");
            builder.append("- OS arch: `").append(osBean.getArch()).append("`\n");
            builder.append("- Warmup seconds: `").append(options.warmupSeconds()).append("`\n");
            builder.append("- Measure seconds: `").append(options.durationSeconds()).append("`\n");
            builder.append("- Worker threads: `").append(options.threads()).append("`\n\n");
            builder.append("## Method\n\n");
            builder.append("- `id-generator`: pure in-process ceiling for ID generation.\n");
            builder.append("- `order-repository`: order creation plus in-memory shard repository save/find/update.\n");
            builder.append("- `stock-reserve-confirm`: in-memory inventory reserve and confirm path.\n");
            builder.append("- `waiting-room-enter-pass`: waiting-room enqueue and pass path.\n\n");
            builder.append("## Results\n\n");
            builder.append("| Benchmark | Description | Ops | Throughput (ops/s) | 10M TPS |\n");
            builder.append("|---|---|---:|---:|---|\n");
            for (BenchmarkResult result : results) {
                builder.append('|')
                        .append(result.name()).append('|')
                        .append(result.description()).append('|')
                        .append(result.operations()).append('|')
                        .append(String.format("%.2f", result.throughputPerSecond())).append('|')
                        .append(result.reachedTenMillionTps() ? "reached" : "not reached")
                        .append("|\n");
            }
            builder.append("\n## Interpretation\n\n");
            builder.append("- A result `>= 10,000,000 ops/s` here only means the **single-JVM hot path** reached ten-million-class throughput.\n");
            builder.append("- It does **not** prove that HTTP + gateway + cache + MQ + DB full-chain throughput reached 10M TPS.\n");
            builder.append("- This report is appropriate for validating single-process algorithmic ceilings and lock contention, not distributed production capacity.\n");
            return builder.toString();
        }
    }
}
