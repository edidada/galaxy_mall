
<template>
    <div id="app">
        <!-- 顶部导航栏 -->
        <header class="navbar">
            <div class="container">
                <div class="brand">
                    <h1>🌌 Galaxy Mall</h1>
                </div>
                <nav>
                    <button @click="activeTab = 'home'" :class="{ active: activeTab === 'home' }">首页</button>
                    <button @click="activeTab = 'orders'" :class="{ active: activeTab === 'orders' }">我的订单</button>
                    <button @click="activeTab = 'cart'" :class="{ active: activeTab === 'cart' }">购物车 ({{ cart.length }})</button>
                    <button @click="activeTab = 'api-test'" :class="{ active: activeTab === 'api-test' }">API 测试</button>
                </nav>
                <div class="user-info" v-if="currentUser">
                    <span>{{ currentUser.nickname }} ({{ currentUser.userId }})</span>
                    <span class="user-level">Lv.{{ currentUser.level }}</span>
                </div>
            </div>
        </header>

        <!-- 全链路追踪横幅：显示最近一次请求的 TraceId，可在各服务日志 grep 此 ID 串联 -->
        <div class="trace-banner" v-if="lastTraceId">
            <span class="trace-label">本次请求 TraceId</span>
            <code class="trace-id">{{ lastTraceId }}</code>
            <span class="trace-hint">在各微服务日志中 grep 此 ID，即可串联「前端 → 网关 → product/user/order…」的完整链路</span>
        </div>

        <!-- 主内容区 -->
        <main class="main-content">
            <div class="container">
                <!-- 首页 - 商品展示 -->
                <div v-if="activeTab === 'home'">
                    <div class="section-title">
                        <h2>🎉 热门商品</h2>
                    </div>

                    <!-- 商品搜索/选择 -->
                    <div class="product-search">
                        <div class="form-group">
                            <label>商品 ID</label>
                            <input v-model="productId" placeholder="sku-iphone" />
                        </div>
                        <div class="form-group">
                            <label>用户 ID</label>
                            <input v-model="userId" placeholder="u100" />
                        </div>
                        <div class="form-group">
                            <label>客户端 IP</label>
                            <input v-model="clientIp" placeholder="10.0.0.1" />
                        </div>
                        <button @click="fetchProduct" class="btn-primary">查询商品</button>
                    </div>

                    <!-- 商品展示 -->
                    <div v-if="productResult?.success && productResult.data" class="product-card">
                        <div class="product-image">📱</div>
                        <div class="product-details">
                            <h3>{{ productResult.data.name }}</h3>
                            <p class="product-price">¥{{ (productResult.data.priceCent / 100).toFixed(2) }}</p>
                            <p v-if="productResult.data.seckillProduct" class="tag tag-danger">秒杀商品</p>
                            <div class="product-actions">
                                <div class="quantity-selector">
                                    <button @click="quantity = Math.max(1, quantity - 1)">-</button>
                                    <span>{{ quantity }}</span>
                                    <button @click="quantity++">+</button>
                                </div>
                                <button @click="addToCart(productResult.data)" class="btn-success">加入购物车</button>
                                <button @click="quickBuy(productResult.data)" class="btn-warning">立即购买</button>
                            </div>
                        </div>
                        <div class="product-static" v-if="productResult.data.staticPage" v-html="productResult.data.staticPage"></div>
                    </div>

                    <div v-else-if="productResult" class="result error">
                        <h3>查询失败</h3>
                        <pre>{{ JSON.stringify(productResult, null, 2) }}</pre>
                    </div>
                </div>

                <!-- 购物车 -->
                <div v-if="activeTab === 'cart'">
                    <div class="section-title">
                        <h2>🛒 购物车</h2>
                    </div>

                    <div v-if="cart.length === 0" class="empty-state">
                        <p>购物车是空的，快去首页逛逛吧！</p>
                    </div>

                    <div v-else>
                        <div v-for="(item, index) in cart" :key="index" class="cart-item">
                            <div class="cart-item-info">
                                <h4>{{ item.name }}</h4>
                                <p class="cart-price">¥{{ (item.priceCent / 100).toFixed(2) }} × {{ item.quantity }}</p>
                            </div>
                            <div class="cart-item-actions">
                                <button @click="removeFromCart(index)" class="btn-danger">删除</button>
                            </div>
                        </div>

                        <div class="cart-summary">
                            <div class="total">
                                总计: ¥{{ (cartTotal / 100).toFixed(2) }}
                            </div>
                            <button @click="checkout" class="btn-lg btn-success">结算</button>
                        </div>
                    </div>
                </div>

                <!-- 订单管理 -->
                <div v-if="activeTab === 'orders'">
                    <div class="section-title">
                        <h2>📦 我的订单</h2>
                    </div>

                    <!-- 订单查询 -->
                    <div class="order-search">
                        <div class="form-group">
                            <label>订单 ID</label>
                            <input v-model="orderId" placeholder="输入订单 ID" />
                        </div>
                        <button @click="fetchOrder" class="btn-primary">查询订单</button>
                    </div>

                    <!-- 订单列表 -->
                    <div v-if="orderDetailResult?.success && orderDetailResult.data" class="order-card">
                        <div class="order-header">
                            <span class="order-id">{{ orderDetailResult.data.orderId }}</span>
                            <span :class="'status status-' + orderDetailResult.data.status.toLowerCase()">{{ orderDetailResult.data.status }}</span>
                        </div>
                        <div class="order-content">
                            <div class="order-item">
                                <div>用户: {{ orderDetailResult.data.userId }}</div>
                                <div>商品: {{ orderDetailResult.data.productId }}</div>
                                <div>数量: {{ orderDetailResult.data.quantity }}</div>
                                <div>金额: ¥{{ (orderDetailResult.data.amountCent / 100).toFixed(2) }}</div>
                            </div>
                            <div class="order-actions" v-if="orderDetailResult.data.status === 'WAITING_PAYMENT' || orderDetailResult.data.status === 'SUBMITTED'">
                                <button @click="doPayment(orderDetailResult.data.orderId)" class="btn-warning">立即支付</button>
                            </div>
                        </div>
                    </div>

                    <!-- 历史订单 -->
                    <div v-if="orderHistory.length > 0">
                        <h3>历史订单</h3>
                        <div v-for="(order, index) in orderHistory" :key="index" class="order-card-mini">
                            <span class="order-id">{{ order.orderId }}</span>
                            <span :class="'status status-' + order.status.toLowerCase()">{{ order.status }}</span>
                        </div>
                    </div>
                </div>

                <!-- API 测试 -->
                <div v-if="activeTab === 'api-test'">
                    <div class="section-title">
                        <h2>🔌 API 测试</h2>
                    </div>

                    <div class="api-tabs">
                        <button v-for="tab in apiTestTabs" :key="tab.id" @click="activeApiTestTab = tab.id" :class="{ active: activeApiTestTab === tab.id }">{{ tab.name }}</button>
                    </div>

                    <!-- 用户服务 -->
                    <div v-if="activeApiTestTab === 'user'" class="api-section">
                        <h3>用户服务</h3>
                        <div class="form-group">
                            <label>用户 ID</label>
                            <input v-model="apiTestUserId" placeholder="u100" />
                        </div>
                        <div class="api-actions">
                            <button @click="testGetUser" class="btn-primary">查询用户 (Edge)</button>
                            <button @click="testGetDirectUser" class="btn-secondary">查询用户 (直连)</button>
                        </div>
                        <div v-if="apiResult" class="result">
                            <h4>结果</h4>
                            <pre>{{ JSON.stringify(apiResult, null, 2) }}</pre>
                        </div>
                    </div>

                    <!-- 商品服务 -->
                    <div v-if="activeApiTestTab === 'product'" class="api-section">
                        <h3>商品服务</h3>
                        <div class="form-group">
                            <label>商品 ID</label>
                            <input v-model="apiTestProductId" placeholder="sku-iphone" />
                        </div>
                        <div class="api-actions">
                            <button @click="testGetEdgeProduct" class="btn-primary">查询商品 (Edge)</button>
                            <button @click="testGetDirectProduct" class="btn-secondary">查询商品 (直连)</button>
                            <button @click="testGetDirectProductPage" class="btn-info">查询商品静态页 (直连)</button>
                        </div>
                        <div v-if="apiResult" class="result">
                            <h4>结果</h4>
                            <pre>{{ typeof apiResult === 'string' ? apiResult : JSON.stringify(apiResult, null, 2) }}</pre>
                        </div>
                    </div>

                    <!-- 库存服务 -->
                    <div v-if="activeApiTestTab === 'stock'" class="api-section">
                        <h3>库存服务</h3>
                        <div class="form-group">
                            <label>预扣 ID</label>
                            <input v-model="apiTestReservationId" placeholder="预扣 ID" />
                        </div>
                        <div class="form-group">
                            <label>商品 ID</label>
                            <input v-model="apiTestStockProductId" placeholder="sku-iphone" />
                        </div>
                        <div class="form-group">
                            <label>数量</label>
                            <input type="number" v-model.number="apiTestStockQuantity" min="1" />
                        </div>
                        <div class="form-group">
                            <label>释放原因</label>
                            <input v-model="apiTestReleaseReason" placeholder="释放原因" />
                        </div>
                        <div class="api-actions">
                            <button @click="testReserveStock" class="btn-primary">预扣库存 (Edge)</button>
                            <button @click="testConfirmStock" class="btn-success">确认扣减 (Edge)</button>
                            <button @click="testReleaseStock" class="btn-warning">释放库存 (Edge)</button>
                        </div>
                        <div v-if="apiResult" class="result">
                            <h4>结果</h4>
                            <pre>{{ JSON.stringify(apiResult, null, 2) }}</pre>
                        </div>
                    </div>

                    <!-- 订单服务 -->
                    <div v-if="activeApiTestTab === 'order'" class="api-section">
                        <h3>订单服务</h3>
                        <div class="form-group">
                            <label>用户 ID</label>
                            <input v-model="apiTestOrderUserId" placeholder="u100" />
                        </div>
                        <div class="form-group">
                            <label>商品 ID</label>
                            <input v-model="apiTestOrderProductId" placeholder="sku-iphone" />
                        </div>
                        <div class="form-group">
                            <label>数量</label>
                            <input type="number" v-model.number="apiTestOrderQuantity" min="1" />
                        </div>
                        <div class="form-group">
                            <label>订单 ID (查询/支付)</label>
                            <input v-model="apiTestOrderId" placeholder="订单 ID" />
                        </div>
                        <div class="api-actions">
                            <button @click="testCreateEdgeOrder" class="btn-primary">创建订单 (Edge)</button>
                            <button @click="testGetEdgeOrder" class="btn-secondary">查询订单 (Edge)</button>
                            <button @click="testMarkOrderPaid" class="btn-success">标记已支付 (Edge)</button>
                        </div>
                        <div v-if="apiResult" class="result">
                            <h4>结果</h4>
                            <pre>{{ JSON.stringify(apiResult, null, 2) }}</pre>
                        </div>
                    </div>

                    <!-- 支付服务 -->
                    <div v-if="activeApiTestTab === 'payment'" class="api-section">
                        <h3>支付服务</h3>
                        <div class="form-group">
                            <label>订单 ID</label>
                            <input v-model="apiTestPaymentOrderId" placeholder="订单 ID" />
                        </div>
                        <div class="api-actions">
                            <button @click="testCreateEdgePayment" class="btn-primary">发起支付 (Edge)</button>
                            <button @click="testCreateDirectPayment" class="btn-secondary">发起支付 (直连)</button>
                        </div>
                        <div v-if="apiResult" class="result">
                            <h4>结果</h4>
                            <pre>{{ JSON.stringify(apiResult, null, 2) }}</pre>
                        </div>
                    </div>

                    <!-- Actuator -->
                    <div v-if="activeApiTestTab === 'actuator'" class="api-section">
                        <h3>Actuator</h3>
                        <div class="api-actions">
                            <button @click="testActuatorHealth" class="btn-primary">查询健康状态</button>
                            <button @click="testActuatorInfo" class="btn-secondary">查询服务信息</button>
                        </div>
                        <div v-if="apiResult" class="result">
                            <h4>结果</h4>
                            <pre>{{ JSON.stringify(apiResult, null, 2) }}</pre>
                        </div>
                    </div>
                </div>
            </div>
        </main>

        <!-- Toast 通知 -->
        <div v-if="toast.show" class="toast" :class="toast.type">
            {{ toast.message }}
        </div>
    </div>
</template>

<script>
import * as api from '../api';

export default {
    data() {
        return {
            activeTab: 'home',
            userId: 'u100',
            clientIp: '10.0.0.1',
            productId: 'sku-iphone',
            quantity: 1,
            productResult: null,
            currentUser: null,
            cart: [],
            orderId: '',
            orderDetailResult: null,
            orderHistory: [],
            paymentResult: null,

            apiTestTabs: [
                { id: 'user', name: '👤 用户' },
                { id: 'product', name: '📦 商品' },
                { id: 'stock', name: '📊 库存' },
                { id: 'order', name: '🛒 订单' },
                { id: 'payment', name: '💰 支付' },
                { id: 'actuator', name: '🔧 健康检查' }
            ],
            activeApiTestTab: 'user',
            apiTestUserId: 'u100',
            apiTestProductId: 'sku-iphone',
            apiTestReservationId: '',
            apiTestStockProductId: 'sku-iphone',
            apiTestStockQuantity: 1,
            apiTestReleaseReason: '',
            apiTestOrderUserId: 'u100',
            apiTestOrderProductId: 'sku-iphone',
            apiTestOrderQuantity: 1,
            apiTestOrderId: '',
            apiTestPaymentOrderId: '',
            apiResult: null,
            toast: { show: false, message: '', type: 'success' },
            lastTraceId: ''
        };
    },
    computed: {
        cartTotal() {
            return this.cart.reduce((sum, item) => sum + item.priceCent * item.quantity, 0);
        }
    },
    mounted() {
        this.fetchUser();
        window.addEventListener('galaxy-trace', (e) => {
            this.lastTraceId = e.detail;
        });
    },
    methods: {
        showToast(msg, type = 'success') {
            this.toast = { show: true, message: msg, type };
            setTimeout(() => this.toast.show = false, 3000);
        },

        async fetchUser() {
            try {
                const res = await api.getUser(this.userId);
                if (res.success && res.data) {
                    this.currentUser = res.data;
                }
            } catch (e) {
                console.error(e);
            }
        },

        async fetchProduct() {
            this.productResult = await api.getProduct(this.productId, this.userId, this.clientIp);
            if (this.productResult.success) {
                this.showToast('商品查询成功');
            }
        },

        addToCart(product) {
            const existing = this.cart.find(item => item.productId === product.productId);
            if (existing) {
                existing.quantity += this.quantity;
            } else {
                this.cart.push({
                    ...product,
                    quantity: this.quantity
                });
            }
            this.showToast('已添加到购物车');
        },

        async quickBuy(product) {
            const data = {
                userId: this.userId,
                productId: product.productId,
                quantity: this.quantity,
                clientIp: this.clientIp,
                queueToken: null
            };
            const res = await api.submitOrder(data);
            if (res.success && res.data) {
                this.orderId = res.data.orderId;
                this.orderDetailResult = res;
                this.orderHistory.unshift(res.data);
                this.showToast('订单创建成功');
                this.activeTab = 'orders';
            } else {
                this.showToast('订单创建失败: ' + (res.message || '未知错误'), 'error');
            }
        },

        removeFromCart(index) {
            this.cart.splice(index, 1);
            this.showToast('已从购物车删除');
        },

        async checkout() {
            if (this.cart.length === 0) {
                this.showToast('购物车是空的', 'error');
                return;
            }

            for (const item of this.cart) {
                const data = {
                    userId: this.userId,
                    productId: item.productId,
                    quantity: item.quantity,
                    clientIp: this.clientIp,
                    queueToken: null
                };
                const res = await api.submitOrder(data);
                if (res.success && res.data) {
                    this.orderHistory.unshift(res.data);
                }
            }

            this.cart = [];
            this.showToast('结算成功');
            this.activeTab = 'orders';
        },

        async fetchOrder() {
            if (!this.orderId) {
                this.showToast('请输入订单 ID', 'error');
                return;
            }
            this.orderDetailResult = await api.getOrder(this.orderId);
            if (this.orderDetailResult.success) {
                this.showToast('订单查询成功');
            }
        },

        async doPayment(orderId) {
            const res = await api.payOrder(orderId);
            if (res.success) {
                this.showToast('支付成功');
                if (this.orderDetailResult?.data?.orderId === orderId) {
                    this.fetchOrder();
                }
            }
        },

        async testGetUser() {
            this.apiResult = await api.getUser(this.apiTestUserId);
        },

        async testGetDirectUser() {
            this.apiResult = await api.getDirectUser(this.apiTestUserId);
        },

        async testGetEdgeProduct() {
            this.apiResult = await api.getEdgeProduct(this.apiTestProductId);
        },

        async testGetDirectProduct() {
            this.apiResult = await api.getDirectProduct(this.apiTestProductId);
        },

        async testGetDirectProductPage() {
            this.apiResult = await api.getDirectProductPage(this.apiTestProductId);
        },

        async testReserveStock() {
            const data = {
                reservationId: this.apiTestReservationId || 'test-' + Date.now(),
                productId: this.apiTestStockProductId,
                quantity: this.apiTestStockQuantity
            };
            this.apiResult = await api.reserveStock(data);
            if (this.apiResult.success) {
                this.apiTestReservationId = data.reservationId;
            }
        },

        async testConfirmStock() {
            if (!this.apiTestReservationId) {
                this.showToast('请先预扣库存', 'error');
                return;
            }
            this.apiResult = await api.confirmStock(this.apiTestReservationId);
        },

        async testReleaseStock() {
            if (!this.apiTestReservationId) {
                this.showToast('请先预扣库存', 'error');
                return;
            }
            this.apiResult = await api.releaseStock(this.apiTestReservationId, this.apiTestReleaseReason || 'test');
        },

        async testCreateEdgeOrder() {
            const data = {
                userId: this.apiTestOrderUserId,
                productId: this.apiTestOrderProductId,
                quantity: this.apiTestOrderQuantity
            };
            this.apiResult = await api.createEdgeOrder(data);
            if (this.apiResult.success && this.apiResult.data?.orderId) {
                this.apiTestOrderId = this.apiResult.data.orderId;
            }
        },

        async testGetEdgeOrder() {
            if (!this.apiTestOrderId) {
                this.showToast('请输入订单 ID', 'error');
                return;
            }
            this.apiResult = await api.getEdgeOrder(this.apiTestOrderId);
        },

        async testMarkOrderPaid() {
            if (!this.apiTestOrderId) {
                this.showToast('请输入订单 ID', 'error');
                return;
            }
            this.apiResult = await api.markOrderPaid(this.apiTestOrderId);
        },

        async testCreateEdgePayment() {
            if (!this.apiTestPaymentOrderId) {
                this.showToast('请输入订单 ID', 'error');
                return;
            }
            this.apiResult = await api.createEdgePayment(this.apiTestPaymentOrderId);
        },

        async testCreateDirectPayment() {
            if (!this.apiTestPaymentOrderId) {
                this.showToast('请输入订单 ID', 'error');
                return;
            }
            this.apiResult = await api.createDirectPayment(this.apiTestPaymentOrderId);
        },

        async testActuatorHealth() {
            this.apiResult = await api.getActuatorHealth();
        },

        async testActuatorInfo() {
            this.apiResult = await api.getActuatorInfo();
        }
    }
};
</script>

<style scoped>
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    min-height: 100vh;
}

.container {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 20px;
}

/* 导航栏 */
.navbar {
    background: linear-gradient(90deg, #1a1a2e 0%, #16213e 100%);
    color: white;
    padding: 20px 0;
    box-shadow: 0 4px 20px rgba(0,0,0,0.3);
    position: sticky;
    top: 0;
    z-index: 100;
}

.navbar .container {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.brand h1 {
    font-size: 24px;
    font-weight: 700;
}

.navbar nav {
    display: flex;
    gap: 10px;
}

.navbar nav button {
    background: rgba(255,255,255,0.1);
    border: none;
    color: white;
    padding: 10px 20px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 14px;
    transition: all 0.3s;
}

.navbar nav button:hover,
.navbar nav button.active {
    background: #667eea;
}

.user-info {
    display: flex;
    gap: 10px;
    align-items: center;
    font-size: 14px;
}

.user-level {
    background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
    padding: 4px 12px;
    border-radius: 20px;
    font-weight: 600;
}

/* 全链路追踪横幅 */
.trace-banner {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
    max-width: 1200px;
    margin: 16px auto 0;
    padding: 12px 20px;
    background: rgba(255,255,255,0.12);
    border: 1px solid rgba(255,255,255,0.25);
    border-radius: 12px;
    color: #fff;
    font-size: 13px;
    backdrop-filter: blur(10px);
}

.trace-label {
    font-weight: 700;
    color: #ffe082;
}

.trace-id {
    font-family: monospace;
    font-size: 14px;
    background: rgba(0,0,0,0.35);
    padding: 4px 10px;
    border-radius: 8px;
    color: #90caf9;
    user-select: all;
}

.trace-hint {
    opacity: 0.85;
}

/* 主内容 */
.main-content {
    padding: 40px 0;
}

.section-title {
    margin-bottom: 30px;
}

.section-title h2 {
    color: white;
    font-size: 28px;
    text-shadow: 0 2px 10px rgba(0,0,0,0.2);
}

/* 表单 */
.form-group {
    margin-bottom: 15px;
}

.form-group label {
    display: block;
    color: white;
    margin-bottom: 8px;
    font-weight: 500;
    font-size: 14px;
}

.form-group input {
    width: 100%;
    padding: 12px 16px;
    border: none;
    border-radius: 10px;
    font-size: 15px;
    background: rgba(255,255,255,0.95);
    box-shadow: 0 2px 10px rgba(0,0,0,0.1);
}

.form-group input:focus {
    outline: 2px solid #667eea;
}

/* 按钮 */
button {
    border: none;
    border-radius: 10px;
    padding: 12px 24px;
    cursor: pointer;
    font-weight: 600;
    font-size: 15px;
    transition: all 0.3s;
}

.btn-primary {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    color: white;
}

.btn-primary:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 15px rgba(102,126,234,0.4);
}

.btn-success {
    background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
    color: white;
}

.btn-success:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 15px rgba(56,239,125,0.4);
}

.btn-warning {
    background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
    color: white;
}

.btn-warning:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 15px rgba(245,87,108,0.4);
}

.btn-danger {
    background: linear-gradient(135deg, #eb3349 0%, #f45c43 100%);
    color: white;
}

.btn-danger:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 15px rgba(244,92,67,0.4);
}

.btn-secondary {
    background: rgba(255,255,255,0.2);
    color: white;
    border: 1px solid rgba(255,255,255,0.3);
}

.btn-secondary:hover {
    background: rgba(255,255,255,0.3);
}

.btn-info {
    background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
    color: white;
}

.btn-info:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 15px rgba(79,172,254,0.4);
}

.btn-lg {
    padding: 16px 32px;
    font-size: 16px;
}

/* 商品搜索 */
.product-search {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 15px;
    margin-bottom: 30px;
    background: rgba(255,255,255,0.1);
    padding: 30px;
    border-radius: 20px;
    backdrop-filter: blur(10px);
}

.product-search button {
    align-self: end;
}

/* 商品卡片 */
.product-card {
    background: white;
    border-radius: 20px;
    padding: 40px;
    box-shadow: 0 10px 40px rgba(0,0,0,0.2);
    display: grid;
    grid-template-columns: 150px 1fr;
    gap: 30px;
    align-items: center;
}

.product-image {
    font-size: 80px;
    text-align: center;
}

.product-details h3 {
    font-size: 28px;
    color: #1a1a2e;
    margin-bottom: 10px;
}

.product-price {
    font-size: 32px;
    font-weight: 700;
    color: #f5576c;
    margin-bottom: 15px;
}

.tag {
    display: inline-block;
    padding: 4px 12px;
    border-radius: 20px;
    font-size: 12px;
    font-weight: 600;
    margin-bottom: 20px;
}

.tag-danger {
    background: linear-gradient(135deg, #eb3349 0%, #f45c43 100%);
    color: white;
}

.product-actions {
    display: flex;
    gap: 15px;
    align-items: center;
}

.quantity-selector {
    display: flex;
    align-items: center;
    gap: 10px;
    background: #f5f5f5;
    border-radius: 10px;
}

.quantity-selector button {
    background: #667eea;
    color: white;
    padding: 8px 16px;
    border-radius: 10px;
}

.quantity-selector span {
    width: 40px;
    text-align: center;
    font-weight: 600;
}

.product-static {
    grid-column: 1 / -1;
    margin-top: 30px;
    padding: 20px;
    background: #f5f5f5;
    border-radius: 10px;
}

/* 购物车 */
.empty-state {
    background: rgba(255,255,255,0.1);
    padding: 60px;
    border-radius: 20px;
    text-align: center;
    color: white;
    font-size: 18px;
}

.cart-item {
    background: white;
    padding: 25px;
    border-radius: 15px;
    margin-bottom: 15px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    box-shadow: 0 4px 20px rgba(0,0,0,0.1);
}

.cart-item-info h4 {
    color: #1a1a2e;
    margin-bottom: 8px;
}

.cart-price {
    color: #666;
    font-weight: 500;
}

.cart-summary {
    background: white;
    padding: 30px;
    border-radius: 15px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    box-shadow: 0 4px 20px rgba(0,0,0,0.1);
}

.total {
    font-size: 24px;
    font-weight: 700;
    color: #1a1a2e;
}

/* 订单 */
.order-search {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 15px;
    margin-bottom: 30px;
    background: rgba(255,255,255,0.1);
    padding: 30px;
    border-radius: 20px;
    backdrop-filter: blur(10px);
}

.order-card {
    background: white;
    padding: 30px;
    border-radius: 20px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.1);
    margin-bottom: 20px;
}

.order-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    padding-bottom: 15px;
    border-bottom: 1px solid #eee;
}

.order-id {
    font-family: monospace;
    font-size: 16px;
    font-weight: 600;
    color: #667eea;
}

.status {
    padding: 6px 16px;
    border-radius: 20px;
    font-weight: 600;
    font-size: 13px;
}

.status-submitted {
    background: #e3f2fd;
    color: #1976d2;
}

.status-waiting_payment {
    background: #fff3e0;
    color: #f57c00;
}

.status-paid {
    background: #e8f5e9;
    color: #388e3c;
}

.status-queued {
    background: #f3e5f5;
    color: #7b1fa2;
}

.order-content {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.order-item {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 15px;
    color: #555;
}

.order-card-mini {
    background: white;
    padding: 20px;
    border-radius: 15px;
    margin-bottom: 10px;
    display: flex;
    justify-content: space-between;
    align-items: center;
}

/* API 测试 */
.api-tabs {
    display: flex;
    gap: 10px;
    margin-bottom: 30px;
    overflow-x: auto;
    padding-bottom: 10px;
}

.api-tabs button {
    background: rgba(255,255,255,0.15);
    color: white;
    white-space: nowrap;
}

.api-tabs button.active {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.api-section {
    background: rgba(255,255,255,0.1);
    padding: 30px;
    border-radius: 20px;
    backdrop-filter: blur(10px);
}

.api-section h3 {
    color: white;
    margin-bottom: 20px;
    font-size: 20px;
}

.api-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 20px;
}

/* 结果 */
.result {
    background: white;
    padding: 20px;
    border-radius: 15px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.1);
}

.result h4 {
    color: #1a1a2e;
    margin-bottom: 10px;
}

.result.error {
    background: #ffebee;
    border-left: 4px solid #f44336;
}

pre {
    overflow-x: auto;
    font-size: 12px;
    background: #f5f5f5;
    padding: 15px;
    border-radius: 10px;
}

/* Toast */
.toast {
    position: fixed;
    top: 100px;
    right: 30px;
    padding: 15px 25px;
    border-radius: 10px;
    color: white;
    font-weight: 600;
    box-shadow: 0 4px 20px rgba(0,0,0,0.2);
    animation: slideIn 0.3s ease-out;
    z-index: 1000;
}

.toast.success {
    background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
}

.toast.error {
    background: linear-gradient(135deg, #eb3349 0%, #f45c43 100%);
}

@keyframes slideIn {
    from {
        transform: translateX(100%);
        opacity: 0;
    }
    to {
        transform: translateX(0);
        opacity: 1;
    }
}
</style>
