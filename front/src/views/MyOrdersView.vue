<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { orders, refreshOrders, clearOrders } from '../stores/orders';
import { getMallProductDetail } from '../api';
import { formatPrice, formatTime, humanMessage, orderStatusText } from '../utils';
import { session } from '../stores/session';
import { toast } from '../stores/ui';
import { track } from '../track';

const router = useRouter();
const loading = ref(false);
const tab = ref('all');
const error = ref('');
const names = ref({});   // productId -> { name, image, specText }，列表接口没有"按单查详情"，这里补齐商品图文

const TABS = [
    { id: 'all', label: '全部' },
    { id: 'WAITING_PAYMENT', label: '待付款' },
    { id: 'PAID', label: '已付款' },
    { id: 'SUBMITTED', label: '处理中' },
    { id: 'FAILED', label: '失败' }
];

const list = computed(() => {
    const rows = [...orders.orders].sort((a, b) => b.createdAt - a.createdAt);
    return tab.value === 'all' ? rows : rows.filter(o => o.status === tab.value);
});

const counts = computed(() => {
    const map = { all: orders.orders.length };
    orders.orders.forEach(o => {
        map[o.status] = (map[o.status] || 0) + 1;
    });
    return map;
});

function describe(order) {
    const cached = names.value[order.productId];
    if (cached) return cached;
    return {
        name: order.name || order.productId,
        image: order.image || '/images/g-office.png',
        specText: order.skuText || ''
    };
}

async function fillProductNames() {
    const ids = [...new Set(orders.orders.map(o => o.productId))].filter(id => !names.value[id]);
    // 逐个回源商品详情只为拿图文；失败不影响订单本身展示
    await Promise.all(ids.map(async id => {
        const res = await getMallProductDetail(id);
        if (res?.success && res.data?.item) {
            names.value[id] = { name: res.data.item.name, image: res.data.item.mainImage };
        }
    }));
}

async function refresh() {
    loading.value = true;
    error.value = '';
    const results = await refreshOrders();
    const dead = results.filter(r => !r.ok);
    if (dead.length === results.length && results.length) {
        error.value = '订单服务查不到任何历史订单（多半是 order 服务重启过，内存单已清空）。';
    } else if (dead.length) {
        error.value = humanMessage(`其中 ${dead.length} 单已失效`);
    }
    await fillProductNames();
    loading.value = false;
    track('biz', 'orders-refresh', { total: orders.orders.length, lost: dead.length });
}

function removeOrder(orderId) {
    orders.orders = orders.orders.filter(o => o.orderId !== orderId);
    toast(`已从本地列表移除 ${orderId}`);
}

function clearAll() {
    clearOrders();
    toast('已清空本地订单列表（后端数据不动）');
}

onMounted(() => {
    refresh();
    fillProductNames();
});
</script>

<template>
    <div>
        <div class="gm-breadcrumb"><router-link to="/">首页</router-link> &nbsp;&gt;&nbsp; <b>我的订单</b></div>

        <div class="gm-panel">
            <div class="gm-panel-head">
                <span>我的订单</span>
                <div class="gm-row">
                    <span class="gray">当前用户 {{ session.user?.nickname || '未登录' }}<template v-if="session.user">（{{ session.user.userId }}）</template></span>
                    <button class="gm-btn sm" data-track="orders-refresh" :disabled="loading" @click="refresh">{{ loading ? '刷新中…' : '刷新状态' }}</button>
                    <button class="gm-btn sm" data-track="orders-clear" :disabled="!orders.orders.length" @click="clearAll">清空列表</button>
                </div>
            </div>

            <div class="tabs">
                <button v-for="t in TABS" :key="t.id" :class="{ on: tab === t.id }"
                        data-track="orders-tab" :data-track-tab="t.id" @click="tab = t.id">
                    {{ t.label }}<span v-if="counts[t.id]"> {{ counts[t.id] }}</span>
                </button>
            </div>

            <div class="gm-panel-body">
                <div class="gm-alert error" v-if="error">{{ error }}</div>

                <div class="gm-empty" v-if="!list.length && !loading">
                    <strong>{{ tab === 'all' ? '还没有订单' : '这个状态下没有订单' }}</strong>
                    <p>订单列表是本地索引（localStorage 记单号），状态每次回源 order 服务查询。</p>
                    <button class="gm-btn primary" data-track="orders-go-list" @click="router.push('/products')">去下单</button>
                </div>

                <div class="order" v-for="o in list" :key="o.orderId" data-track-expose="order-row" :data-track-order-id="o.orderId">
                    <div class="oh">
                        <span><code>{{ o.orderId }}</code></span>
                        <span>{{ formatTime(o.createdAt) }} 提交</span>
                        <span class="spacer"></span>
                        <span class="st" :class="o.status.toLowerCase()">{{ orderStatusText(o.status) }}</span>
                    </div>
                    <div class="ob">
                        <div class="gm-row prod">
                            <img :src="describe(o).image" :alt="describe(o).name" class="thumb"
                                 data-track="order-row-image" :data-track-product-id="o.productId"
                                 @click="router.push(`/products/${o.productId}`)" />
                            <div>
                                <router-link :to="`/products/${o.productId}`" class="name" data-track="order-row-title">{{ describe(o).name }}</router-link>
                                <div class="gray">{{ o.skuText || describe(o).specText || o.productId }} × {{ o.quantity }}</div>
                            </div>
                        </div>
                        <div class="price">¥{{ formatPrice(o.amountCent) }}</div>
                        <div class="acts">
                            <router-link v-if="o.status === 'WAITING_PAYMENT'" class="gm-btn primary sm" :to="`/pay/${o.orderId}`"
                                         data-track="order-go-pay" :data-track-order-id="o.orderId">去支付</router-link>
                            <button v-else class="gm-btn sm" data-track="order-view" :data-track-order-id="o.orderId"
                                    @click="router.push(`/pay/${o.orderId}`)">查看详情</button>
                            <a href="javascript:void(0)" class="gray del" data-track="order-remove" :data-track-order-id="o.orderId"
                               @click="removeOrder(o.orderId)">移除</a>
                        </div>
                    </div>
                    <div class="of gray">
                        下单用户 {{ o.userId }}
                        <template v-if="o.queueToken"> · 曾用排队凭据 {{ o.queueToken.slice(0, 8) }}…</template>
                        · 更新于 {{ formatTime(o.updatedAt) }}
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<style scoped>
.gray {
    font-size: 12px;
    color: var(--gm-text-light);
    font-weight: 400;
}

.tabs {
    display: flex;
    gap: 4px;
    border-bottom: 1px solid var(--gm-border);
    padding: 0 16px;
}

.tabs button {
    height: 38px;
    padding: 0 16px;
    border: 0;
    background: none;
    cursor: pointer;
    color: var(--gm-text-weak);
    border-bottom: 2px solid transparent;
}

.tabs button.on {
    color: var(--gm-red);
    border-bottom-color: var(--gm-red);
    font-weight: 600;
}

.order {
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    margin-bottom: 12px;
    overflow: hidden;
}

.oh {
    display: flex;
    align-items: center;
    gap: 18px;
    background: #fafafa;
    padding: 8px 14px;
    font-size: 12px;
    color: var(--gm-text-weak);
}

.spacer {
    flex: 1;
}

.st {
    font-weight: 600;
    color: var(--gm-orange);
}

.st.paid {
    color: #1c7a35;
}

.st.failed {
    color: var(--gm-red);
}

.st.waiting_payment {
    color: var(--gm-red);
}

.ob {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 12px 14px;
}

.thumb {
    width: 62px;
    height: 62px;
    object-fit: cover;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    cursor: pointer;
    flex: none;
}

.prod {
    flex: 1;
    min-width: 0;
    align-items: flex-start;
}

.name {
    font-size: 13px;
}

.price {
    width: 120px;
    text-align: right;
    color: var(--gm-price);
    font-weight: 700;
}

.acts {
    width: 170px;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 6px;
}

.del:hover {
    color: var(--gm-red);
}

.of {
    border-top: 1px dashed var(--gm-border);
    padding: 6px 14px;
}

code {
    background: #f1f1f1;
    padding: 1px 5px;
    border-radius: 2px;
}

p {
    font-size: 13px;
    color: var(--gm-text-light);
    margin-bottom: 14px;
}
</style>
