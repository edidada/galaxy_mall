<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { submitOrder } from '../api';
import { cart, clearSelected, selectedItems, selectedTotalCent } from '../stores/cart';import { recordOrder } from '../stores/orders';
import { session, currentUserId } from '../stores/session';
import { formatPrice, humanMessage } from '../utils';
import { toast } from '../stores/ui';
import { track } from '../track';

const router = useRouter();

const ADDRESS_KEY = 'gm_address';

const address = ref({
    receiver: '',
    phone: '',
    region: '',
    detail: '',
    tag: '家'
});

const lines = ref([]);
const submitting = ref(false);
const results = ref([]);
const failed = ref('');

// 网关的下单接口是"单品 + 数量"（GatewayOrderRequest），
// 所以多商品购物车在这里按商品拆单，一单一次调用，与京东"一个商家一单"的体感一致。
const groups = computed(() => {
    const map = new Map();
    lines.value.forEach(line => {
        if (!map.has(line.productId)) map.set(line.productId, { productId: line.productId, quantity: 0, name: line.name, items: [] });
        const g = map.get(line.productId);
        g.quantity += line.quantity;
        g.items.push(line);
    });
    return [...map.values()];
});

const shipping = computed(() => (selectedTotalCent.value >= 9900 ? 0 : 1000));
const payCent = computed(() => selectedTotalCent.value + shipping.value);
const addressOk = computed(() =>
    address.value.receiver.trim() && address.value.phone.trim() && address.value.region.trim() && address.value.detail.trim()
);

const canSubmit = computed(() => groups.value.length > 0 && addressOk.value && !submitting.value);

function loadAddress() {
    try {
        const saved = JSON.parse(localStorage.getItem(ADDRESS_KEY) || 'null');
        if (saved) address.value = { ...address.value, ...saved };
        else {
            address.value = {
                receiver: session.user?.nickname || 'Galaxy 用户',
                phone: '13800138000',
                region: '北京市 朝阳区 望京街道',
                detail: ' Galaxy Mall 演示园区 1 号楼 3001 室',
                tag: '公司'
            };
        }
    } catch {
        /* 存坏了就用默认值 */
    }
}

function saveAddress() {
    try {
        localStorage.setItem(ADDRESS_KEY, JSON.stringify(address.value));
    } catch {
        /* ignore */
    }
    toast('地址已保存到本地');
}

/**
 * 逐单提交。
 * status=QUEUED 表示被网关限流挡进了排队大厅（返回的是 QUEUE-<token> 假单号），
 * 这时不能当成功，要把 queueToken 留着让用户点"重新提交"带回令牌放行。
 */
async function placeAll() {
    if (!currentUserId.value) {
        router.push({ path: '/login', query: { redirect: '/checkout' } });
        return;
    }
    submitting.value = true;
    failed.value = '';
    results.value = [];
    const queued = [];

    for (const group of groups.value) {
        const payload = {
            userId: currentUserId.value,
            productId: group.productId,
            quantity: group.quantity,
            clientIp: '10.0.0.1'
        };
        const res = await submitOrder(payload);
        if (res?.success && res.data && res.data.status !== 'QUEUED') {
            results.value.push({ ok: true, group, order: res.data });
            recordOrder(res.data, {
                skuText: group.items.map(i => i.specText).join(' / '),
                name: group.name,
                image: group.items[0].image
            });
            track('biz', 'order-submitted', {
                orderId: res.data.orderId,
                productId: group.productId,
                quantity: group.quantity,
                amountCent: res.data.amountCent
            });
        } else if (res?.data?.status === 'QUEUED') {
            queued.push({ group, queueToken: res.data.queueToken, ahead: res.data.quantity });
            track('biz', 'order-queued', { productId: group.productId, queueToken: res.data.queueToken });
        } else {
            failed.value = humanMessage(res?.message);
            track('biz', 'order-failed', { productId: group.productId, message: res?.message });
        }
    }

    if (results.value.length) {
        // 下单成功的商品从购物车里移除；失败的留在车里，避免用户以为买到了其实没有
        const done = new Set(results.value.map(r => r.group.productId));
        cart.items = cart.items.filter(item => !done.has(item.productId));
    }

    if (results.value.length === 1) {
        toast('下单成功，正在进入收银台', 'ok');
        submitting.value = false;
        router.push(`/pay/${results.value[0].order.orderId}`);
        return;
    }

    if (results.value.length > 1) {
        toast(`已拆成 ${results.value.length} 单，请逐个支付`, 'ok');
    }
    if (queued.length) {
        failed.value = `${queued.length} 个商品被网关限流放进了排队大厅（每用户窗口内下单次数有限）。稍后重试或减少下单数量。`;
    }
    if (results.value.length) {
        submitting.value = false;
        router.push('/orders');
        return;
    }
    submitting.value = false;
}

onMounted(() => {
    loadAddress();
    const buyNow = sessionStorage.getItem('gm_buy_now');
    if (buyNow) {
        try {
            const { productId, skuId } = JSON.parse(buyNow);
            // 立即购买：只结算刚加的那一条，其它购物车项保持未勾选
            cart.items.forEach(item => {
                item.selected = item.productId === productId && (item.skuId || '') === (skuId || '');
            });
        } catch {
            /* ignore */
        } finally {
            sessionStorage.removeItem('gm_buy_now');
        }
    }
    lines.value = selectedItems.value;
    if (!lines.value.length) {
        toast('购物车里没有勾选的商品', 'err');
        router.replace('/cart');
    }
    track('pv', 'checkout', { lines: lines.value.length });
});
</script>

<template>
    <div>
        <div class="gm-breadcrumb">
            <router-link to="/">首页</router-link> &nbsp;&gt;&nbsp;
            <router-link to="/cart">购物车</router-link> &nbsp;&gt;&nbsp; <b>订单确认</b>
        </div>

        <div class="steps">
            <span class="step on">1. 填写订单信息</span>
            <span class="bar"></span>
            <span class="step">2. 支付</span>
            <span class="bar"></span>
            <span class="step">3. 完成</span>
        </div>

        <div class="gm-alert error" v-if="failed" style="margin-bottom:12px">{{ failed }}</div>

        <div class="gm-panel">
            <div class="gm-panel-head"><span>收货地址</span>
                <button class="gm-btn sm" data-track="checkout-save-address" @click="saveAddress">保存地址到本地</button>
            </div>
            <div class="gm-panel-body addr">
                <div class="gm-field">
                    <label>收货人</label>
                    <input class="gm-input" v-model="address.receiver" placeholder="姓名" data-track-input="addr-receiver" />
                </div>
                <div class="gm-field">
                    <label>手机号</label>
                    <input class="gm-input" v-model="address.phone" placeholder="11 位手机号" data-track-input="addr-phone" />
                </div>
                <div class="gm-field wide">
                    <label>所在地区</label>
                    <input class="gm-input" v-model="address.region" placeholder="省 市 区 街道" data-track-input="addr-region" />
                </div>
                <div class="gm-field wide">
                    <label>详细地址</label>
                    <input class="gm-input" v-model="address.detail" placeholder="门牌号" data-track-input="addr-detail" />
                </div>
                <p class="note">地址只存在浏览器 localStorage，后端订单接口不收地址字段 —— 这版演示的订单只有
                    <code>userId / productId / quantity / clientIp</code>。</p>
            </div>
        </div>

        <div class="gm-panel">
            <div class="gm-panel-head"><span>商品清单</span><span class="gray">{{ lines.length }} 条 · 拆分为 {{ groups.length }} 个订单</span></div>
            <table class="gm-table">
                <thead>
                <tr><th>商品</th><th style="width:110px">单价</th><th style="width:90px">数量</th><th style="width:110px">小计</th><th style="width:150px">所属订单</th></tr>
                </thead>
                <tbody>
                <tr v-for="line in lines" :key="line.key">
                    <td>
                        <div class="gm-row">
                            <img :src="line.image" :alt="line.name" class="thumb" />
                            <div>
                                <div class="name">{{ line.name }}</div>
                                <div class="spec">{{ line.specText }}</div>
                            </div>
                        </div>
                    </td>
                    <td class="red">¥{{ formatPrice(line.priceCent) }}</td>
                    <td>{{ line.quantity }}</td>
                    <td class="red bold">¥{{ formatPrice(line.priceCent * line.quantity) }}</td>
                    <td class="gray">{{ line.productId }}<br/>SKU {{ line.skuId ? line.skuId.slice(-6) : '默认' }}</td>
                </tr>
                </tbody>
            </table>
            <div class="gm-panel-body summary">
                <div><span>商品总额</span><b>¥{{ formatPrice(selectedTotalCent) }}</b></div>
                <div><span>运费</span><b>{{ shipping ? `¥${formatPrice(shipping)}` : '免运费' }}</b></div>
                <div class="pay"><span>应付金额</span><b>¥{{ formatPrice(payCent) }}</b></div>
            </div>
        </div>

        <div class="gm-between bottom">
            <div class="gray">
                下单用户：<b>{{ session.user?.nickname }}</b>（{{ currentUserId }}）
                · 每用户网关下单窗口 3 次，超出会进入排队大厅
            </div>
            <div class="gm-row">
                <button class="gm-btn" :disabled="submitting" @click="router.push('/cart')">返回购物车</button>
                <button class="gm-btn primary lg" :disabled="!canSubmit || !addressOk" data-track="checkout-submit"
                        :data-track-groups="groups.length" @click="placeAll">
                    {{ submitting ? '提交中…' : `提交订单（${groups.length} 单）` }}
                </button>
            </div>
        </div>

        <div class="gm-panel" v-if="results.length" style="margin-top:16px">
            <div class="gm-panel-head"><span>提交结果</span></div>
            <table class="gm-table">
                <thead><tr><th>订单号</th><th>商品</th><th>金额</th><th>状态</th><th></th></tr></thead>
                <tbody>
                <tr v-for="r in results" :key="r.order.orderId">
                    <td><code>{{ r.order.orderId }}</code></td>
                    <td>{{ r.group.name }} × {{ r.group.quantity }}</td>
                    <td class="red">¥{{ formatPrice(r.order.amountCent) }}</td>
                    <td>{{ r.order.status }}</td>
                    <td><router-link class="gm-btn sm" :to="`/pay/${r.order.orderId}`">去支付</router-link></td>
                </tr>
                </tbody>
            </table>
        </div>
    </div>
</template>

<style scoped>
.steps {
    display: flex;
    align-items: center;
    gap: 12px;
    background: #fff;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    padding: 14px 20px;
    margin-bottom: 12px;
}

.step {
    color: var(--gm-text-light);
    font-size: 15px;
}

.step.on {
    color: var(--gm-red);
    font-weight: 600;
}

.bar {
    width: 90px;
    height: 2px;
    background: #e3e3e3;
}

.addr {
    display: grid;
    grid-template-columns: repeat(2, 280px);
    gap: 0 16px;
}

.wide {
    grid-column: 1 / -1;
}

.note {
    grid-column: 1 / -1;
    font-size: 12px;
    color: var(--gm-text-light);
    margin-top: 4px;
}

.note code {
    background: #f5f5f5;
    padding: 0 4px;
    border-radius: 2px;
}

.gray {
    font-size: 13px;
    font-weight: 400;
    color: var(--gm-text-light);
}

.thumb {
    width: 54px;
    height: 54px;
    object-fit: cover;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    flex: none;
}

.name {
    font-size: 13px;
}

.spec {
    font-size: 12px;
    color: var(--gm-text-light);
}

.red {
    color: var(--gm-price);
}

.bold {
    font-weight: 700;
}

.summary {
    border-top: 1px solid var(--gm-border);
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 6px;
    font-size: 13px;
}

.summary span {
    color: var(--gm-text-weak);
    margin-right: 16px;
}

.summary b {
    font-weight: 400;
    color: var(--gm-text);
    min-width: 120px;
    display: inline-block;
    text-align: right;
}

.summary .pay b {
    color: var(--gm-price);
    font-size: 22px;
    font-weight: 700;
}

.bottom {
    background: #fff;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    padding: 14px 18px;
    position: sticky;
    bottom: 0;
}
</style>
