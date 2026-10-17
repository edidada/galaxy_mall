# Vue 3 + Vite

This template should help get you started developing with Vue 3 in Vite. The template uses Vue 3 `<script setup>` SFCs, check out the [script setup docs](https://v3.vuejs.org/api/sfc-script-setup.html#sfc-script-setup) to learn more.

Learn more about IDE Support for Vue in the [Vue Docs Scaling up Guide](https://vuejs.org/guide/scaling-up/tooling.html#ide-support).

---

## 2026-10-17 追加：京东 PC 风格商城前台 + 行为采集 SDK

> 上面是 Vite 模板原文，未改动。以下是本次新增内容。

### 页面

| 路由 | 视图 | 说明 |
| --- | --- | --- |
| `/` | HomeView | 轮播 + 秒杀专区 + 今日热卖 + 按后端分类自动生成的楼层 |
| `/products` | ProductListView | 分类/价格/自营筛选 + 综合·价格·评价·新品排序 + 关键词过滤 |
| `/products/:productId` | ProductDetailView | 图文详情、多维 SKU 选择（售罄规格置灰）、数量、加购、立即购买 |
| `/cart` | CartView | 本地购物车：勾选、改数量、删除、合计与免邮门槛 |
| `/checkout` | CheckoutView | 收货地址（本地）+ 按商品拆单提交到网关 `/api/gateway/orders` |
| `/pay/:orderId` | PayView | 收银台：轮询订单状态直到可支付，四种支付方式，支付后回查 |
| `/orders` | MyOrdersView | 本地索引单号 + 逐单回源 order 服务查真实状态 |
| `/login` | LoginView | 登录/注册（注册会真实写入 user 服务的用户表，所以新账号能下单） |
| `/track` | TrackView | 埋点看板：SDK 侧计数 + 网关 `/api/collect/stats` 的类型/页面/target 分布与最近事件 |
| `/console` | ApiConsoleView | 原有 API 测试台，功能未改 |

演示账号 `andromeda / orion / nova`，口令统一 `123456`（`AccountService.DEFAULT_PASSWORD`）。

### 本地跑起来

```bash
# 1. 后端（仓库根目录）。本机 8080 被 httpd 占用，网关用 18080
mvn -DskipTests package -pl galaxy_mall-gateway,galaxy_mall-user,galaxy_mall-product,galaxy_mall-stock,galaxy_mall-order,galaxy_mall-payment -am
java -jar galaxy_mall-user/target/galaxy_mall-user-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-product/target/galaxy_mall-product-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-stock/target/galaxy_mall-stock-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-order/target/galaxy_mall-order-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-payment/target/galaxy_mall-payment-1.0-SNAPSHOT-exec.jar
java -jar galaxy_mall-gateway/target/galaxy_mall-gateway-1.0-SNAPSHOT-exec.jar --server.port=18080

# 2. 前端
npm install
python ../scripts/gen_product_images.py   # 生成 public/images 的占位商品图
npm run dev                               # http://127.0.0.1:5173
```

vite 的 `server.proxy` 把 `/api`、`/edge`、`/actuator` 同源转发到 `http://localhost:18080`。
集群入口不下发 CORS 头、OPTIONS 预检直接 403，所以开发态必须同源，不能直连。
要连集群：`front/.env.local` 写 `VITE_API_TARGET=http://192.168.31.77:30080`。

### 行为采集 SDK（`src/track/`）

`document` 捕获阶段事件委托 + IntersectionObserver 曝光 + 路由 PV + 表单 change +
`window.error`/`unhandledrejection` + PerformanceObserver（FCP/LCP/longtask），
业务侧只需写 `data-track="xxx"`、`data-track-input="xxx"`、`data-track-expose="xxx"`，
维度用 `data-track-product-id` 这类声明式属性带进 payload。

批量 20 条或 2s 上报一次 `POST /api/collect`；页面隐藏/卸载走 `navigator.sendBeacon`；
网络失败或 `accepted < received` 的剩余事件进 localStorage 死信队列，下次 flush 优先带出。
控制台 `window.__gmTrack.snapshot()` 随时核对。采样率默认 1（全量）—— 本地演示量小，
采样只会让看板对不上数；真上线应按类型分级（error/biz 不采、perf 采 1%）。

接收端是网关里的内存环形缓冲（`CollectService`，默认容量 5000，`GALAXY_COLLECT_BUFFER_CAPACITY` 可调），
不入库、不建索引，`GET /api/collect/events?limit=` 与 `GET /api/collect/stats` 只读。

### 已实测的链路（vite dev + 真实浏览器）

- 首页/列表/详情/购物车/结算/收银台/订单/看板/404 全部渲染正常，16 张商品图无破图；
- SKU 切换：`256GB 全网通 国行` ¥8999 → `1TB 港版` ¥11799，价格/库存/"已选"随规格变化；
  `白色钛金属 512GB 港版` 库存 0 时两个购买按钮自动 disabled；
- 登录 → 顶栏变昵称、令牌落 localStorage、刷新后 `/api/mall/me` 能恢复登录态；退出登录清空本地态；
- 注册新账号 `demo****` → userId `u-demodqke` 下单没有被网关 `user-not-found` 挡掉，支付后服务端状态 `PAID`；
- 购物车 3 行（2 个商品）→ 结算拆成 2 单，单号与金额正确（¥27125 = ¥8999 + 2×¥8999 + ¥128）；
- 收银台轮询：`SUBMITTED` → 异步消费者扣库存 → `WAITING_PAYMENT` 才允许支付 → `PAID`；
- 反向用例：鼠标库存 1，下 5 件 → 消费者返回 `stock-not-enough` → 页面显示"订单处理失败"且支付按钮禁用；
- 埋点：本次实测累计 49 批 / 205 事件入网关缓冲，`droppedByOverflow=0`，SDK 侧 `failed=0`、死信 0。

### 已知边界（不是 bug，是范围决定）

1. **购物车不落后端**：本次只补了登录、图文列表/SKU、埋点三组接口。购物车要做真实落库需要按 userId 分片的表 +
   失效/合并逻辑，是另一个话题；现在是本地持久化，结算时把 SKU 与数量原样带给下单接口。
2. **多商品 = 拆单**：`GatewayOrderRequest` 是"单品 + 数量"，所以一单一品，与京东"一个商家一单"体感一致。
3. **订单列表是本地索引**：order 服务只有按单号查，没有"按用户列单"。服务重启后内存订单清空，
   旧单号会查不到，此时列表提示"其中 N 单已失效"。
4. **收货地址只存浏览器**：后端订单接口不收地址字段。
5. **图片是脚本生成的占位图**：`python ../scripts/gen_product_images.py`，换真图直接覆盖同名文件，前后端都不用改。
6. **埋点曝光（expose）在自动化浏览器里测不到**：Qoder 内置浏览器视口是 0×0，IntersectionObserver 永远不触发，
   所以看板 expose=0 是环境限制；首页已确认 9 个 `data-track-expose` 挂载点存在，人工浏览器里会正常出数。
7. **`/edge/**` 响应头 X-Trace-Id 重复三次**：Spring Cloud Gateway 路由过滤器与我们自己的 WebFilter、
   拦截器各写一次。前端 tracebar 已去重显示；没动后端，因为那三处属于压测链路。
