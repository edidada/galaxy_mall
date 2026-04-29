package cn.wdidada.galaxymall.web;

import cn.wdidada.galaxymall.cache.BloomFilterGuard;
import cn.wdidada.galaxymall.cache.MultiLevelCache;
import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.db.ProductRepository;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.db.UserRepository;
import cn.wdidada.galaxymall.gateway.GatewayFacade;
import cn.wdidada.galaxymall.message.InMemoryMessageBus;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import cn.wdidada.galaxymall.order.OrderFulfillmentConsumer;
import cn.wdidada.galaxymall.order.OrderService;
import cn.wdidada.galaxymall.order.OrderTopics;
import cn.wdidada.galaxymall.payment.PaymentService;
import cn.wdidada.galaxymall.product.ProductCatalogService;
import cn.wdidada.galaxymall.seckill.SeckillService;
import cn.wdidada.galaxymall.stock.StockService;
import cn.wdidada.galaxymall.user.UserService;
import java.util.List;

public final class MallConsoleApplication {

    private MallConsoleApplication() {
    }

    public static void main(String[] args) {
        ProductRepository productRepository = new ProductRepository();
        UserRepository userRepository = new UserRepository();
        OrderRepository orderRepository = new OrderRepository();
        StockLedgerRepository stockLedgerRepository = new StockLedgerRepository();
        LocalBinlogRepository localBinlogRepository = new LocalBinlogRepository();
        MetricsCollector metricsCollector = new MetricsCollector();
        TraceLogger traceLogger = new TraceLogger();
        InMemoryMessageBus messageBus = new InMemoryMessageBus(2);

        seedProducts(productRepository);
        seedUsers(userRepository);
        seedStocks(stockLedgerRepository);

        ProductCatalogService productCatalogService = new ProductCatalogService(
                productRepository,
                new MultiLevelCache<>(),
                new BloomFilterGuard(2048)
        );
        productCatalogService.warmHotProducts();

        StockService stockService = new StockService(stockLedgerRepository, metricsCollector, 10000);
        stockService.preload("sku-iphone");
        stockService.preload("sku-mouse");
        stockService.preload("sku-seckill");

        OrderService orderService = new OrderService(
                orderRepository,
                localBinlogRepository,
                messageBus,
                stockService,
                metricsCollector,
                traceLogger
        );
        messageBus.register(OrderTopics.ORDER_CREATED, new OrderFulfillmentConsumer(orderRepository, stockService, traceLogger));

        UserService userService = new UserService(userRepository);
        GatewayFacade gatewayFacade = new GatewayFacade(productCatalogService, userService, orderService, metricsCollector, traceLogger);
        PaymentService paymentService = new PaymentService(orderRepository, metricsCollector);
        SeckillService seckillService = new SeckillService(orderService);

        runCacheScenario(gatewayFacade, productCatalogService);
        Order placedOrder = runAsyncOrderScenario(gatewayFacade, messageBus, paymentService, orderRepository, stockService);
        runStockRejectScenario(gatewayFacade);
        pauseForNewWindow();
        runGatewayQueueScenario(gatewayFacade);
        pauseForNewWindow();
        runSeckillScenario(seckillService, messageBus, orderRepository, userRepository, productRepository);
        runDeadLetterScenario(messageBus);

        printSummary(localBinlogRepository, metricsCollector, traceLogger, placedOrder, orderRepository);
    }

    private static void runCacheScenario(GatewayFacade gatewayFacade, ProductCatalogService productCatalogService) {
        OperationResult<Product> first = gatewayFacade.queryProduct("u100", "sku-iphone", "10.0.0.1");
        OperationResult<Product> second = gatewayFacade.queryProduct("u100", "sku-iphone", "10.0.0.2");
        SelfCheck.expect(first.success(), "product query should succeed");
        SelfCheck.expect(second.success(), "second product query should succeed");
        SelfCheck.expect(productCatalogService.cache().stats().l1Hits() >= 1, "cache should record l1 hit");
        SelfCheck.expect(productCatalogService.renderStaticPage("sku-iphone").contains("iPhone"), "static page should be returned");
    }

    private static Order runAsyncOrderScenario(
            GatewayFacade gatewayFacade,
            InMemoryMessageBus messageBus,
            PaymentService paymentService,
            OrderRepository orderRepository,
            StockService stockService
    ) {
        OperationResult<Order> result = gatewayFacade.placeOrder("u100", "sku-iphone", 2, "10.0.1.1", false);
        SelfCheck.expect(result.success(), "place order should be accepted");

        messageBus.drain();
        Order order = orderRepository.findById(result.data().orderId()).orElseThrow();
        SelfCheck.expect(order.asyncAttempts() == 2, "async consumer should retry once");
        SelfCheck.expect(order.status() == OrderStatus.WAITING_PAYMENT, "order should wait for payment");

        OperationResult<Order> paymentResult = paymentService.pay(order.orderId());
        SelfCheck.expect(paymentResult.success(), "payment should succeed");
        SelfCheck.expect(order.status() == OrderStatus.PAID, "order should be paid");
        SelfCheck.expect(stockService.databaseStock("sku-iphone") == 8, "database stock should be decreased");
        SelfCheck.expect(stockService.redisStock("sku-iphone") == 8, "redis stock should stay aligned");
        return order;
    }

    private static void runStockRejectScenario(GatewayFacade gatewayFacade) {
        OperationResult<Order> result = gatewayFacade.placeOrder("u101", "sku-mouse", 2, "10.0.2.1", false);
        SelfCheck.expect(!result.success(), "insufficient stock should fail");
        SelfCheck.expect("stock-not-enough".equals(result.message()), "stock failure reason should be explicit");
    }

    private static void runGatewayQueueScenario(GatewayFacade gatewayFacade) {
        gatewayFacade.queryProduct("u300", "sku-iphone", "10.0.3.1");
        gatewayFacade.queryProduct("u300", "sku-iphone", "10.0.3.1");
        OperationResult<Product> queued = gatewayFacade.queryProduct("u300", "sku-iphone", "10.0.3.1");
        SelfCheck.expect(!queued.success(), "third request from same ip should be queued");
        SelfCheck.expect(queued.message().contains("Galaxy Mall Queue"), "queue page should be returned");
    }

    private static void runSeckillScenario(
            SeckillService seckillService,
            InMemoryMessageBus messageBus,
            OrderRepository orderRepository,
            UserRepository userRepository,
            ProductRepository productRepository
    ) {
        User user = userRepository.findById("u200").orElseThrow();
        Product product = productRepository.findById("sku-seckill").orElseThrow();

        OperationResult<Order> first = seckillService.attempt(user, product, 1, "10.0.9.1");
        SelfCheck.expect(first.success(), "first seckill attempt should pass");
        messageBus.drain();
        Order order = orderRepository.findById(first.data().orderId()).orElseThrow();
        SelfCheck.expect(order.status() == OrderStatus.WAITING_PAYMENT, "seckill order should reach waiting payment");

        OperationResult<Order> second = seckillService.attempt(user, product, 1, "10.0.9.1");
        SelfCheck.expect(!second.success(), "second seckill attempt should be throttled");
    }

    private static void runDeadLetterScenario(InMemoryMessageBus messageBus) {
        messageBus.register("poison", message -> {
            throw new IllegalStateException("always fail");
        });
        messageBus.publish("poison", "dead-1", "dead-1");
        messageBus.drain();
        SelfCheck.expect(messageBus.deadLetters().size() == 1, "poison message should enter dead letter queue");
    }

    private static void printSummary(
            LocalBinlogRepository localBinlogRepository,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            Order placedOrder,
            OrderRepository orderRepository
    ) {
        System.out.println("Galaxy Mall self-check passed");
        System.out.println("paid-order=" + placedOrder.orderId());
        System.out.println("binlog-events=" + localBinlogRepository.snapshot().size());
        System.out.println("metrics=" + metricsCollector.counterSnapshot());
        System.out.println("orders=" + orderRepository.findAll().size());
        System.out.println("trace-log-sample=");
        List<String> logs = traceLogger.snapshot();
        for (int i = 0; i < Math.min(5, logs.size()); i++) {
            System.out.println(logs.get(i));
        }
    }

    private static void pauseForNewWindow() {
        try {
            Thread.sleep(1100L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("self-check interrupted", ex);
        }
    }

    private static void seedProducts(ProductRepository productRepository) {
        productRepository.save(new Product(
                "sku-iphone",
                "iPhone Galaxy Edition",
                899900,
                false,
                "<html><body><h1>iPhone Galaxy Edition</h1><p>static page</p></body></html>"
        ));
        productRepository.save(new Product(
                "sku-mouse",
                "Nebula Mouse",
                9900,
                false,
                "<html><body><h1>Nebula Mouse</h1></body></html>"
        ));
        productRepository.save(new Product(
                "sku-seckill",
                "Meteor Flash Sale",
                19900,
                true,
                "<html><body><h1>Meteor Flash Sale</h1></body></html>"
        ));
    }

    private static void seedUsers(UserRepository userRepository) {
        userRepository.save(new User("u100", "andromeda", 3));
        userRepository.save(new User("u101", "orion", 2));
        userRepository.save(new User("u200", "nova", 5));
        userRepository.save(new User("u300", "lyra", 1));
    }

    private static void seedStocks(StockLedgerRepository stockLedgerRepository) {
        stockLedgerRepository.seed("sku-iphone", 10);
        stockLedgerRepository.seed("sku-mouse", 1);
        stockLedgerRepository.seed("sku-seckill", 2);
    }
}
