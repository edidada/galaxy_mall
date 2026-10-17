<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { getOrder, payOrder } from '../api';
import { recordOrder, findOrder } from '../stores/orders';
import { formatPrice, humanMessage, orderStatusText } from '../utils';
import { toast } from '../stores/ui';
import { track } from '../track';

const props = defineProps({ orderId: { type: String, required: true } });
const router = useRouter();

const order = ref(null);
const loading = ref(true);
const error = ref('');
const paying = ref(false);
const paid = ref(false);
const local = ref(null);
const polling = ref(false);
const method = ref('wechat');
let timer = null;

const METHODS = [
    { id: 'wechat', name: '微信支付', desc: '扫码支付，立减 1 元', color: '#2aa512' },
    { id: 'jd', name: '京东白条', desc: '30 天免息 · 演示不产生账单', color: '#e1251b' },
    { id: 'card', name: '银行卡', desc: '储蓄卡 / 信用卡快捷支付', color: '#3f6fa8' },
    { id: 'balance', name: '余额支付', desc: '演示余额 ¥ 8,888.00', color: '#ff7600' }
];

// 订单在网关是"先提交、后异步处理"：SUBMITTED 要等 order 服务的消费者
// 走完 查商品 → 锁库存 → 确认库存 才变成 WAITING_PAYMENT，这时才可支付。
// 所以这里轮询状态，而不是提交后立刻让用户点支付。
const payable = computed(() => order.value?.status === 'WAITING_PAYMENT');
const waitReason = computed(() => {
    if (!order.value) return '';
    if (order.value.status === 'SUBMITTED') return '订单正在后台处理（扣减库存），稍等…';
    if (order.value.status === 'PROCESSING') return '正在锁定库存…';
    if (order.value.status === 'QUEUED') return '订单在排队大厅中，请稍后重新提交';
    if (order.value.status === 'FAILED') return '订单处理失败：库存不足或商品已下架，请回购物车调整';
    return '';
});

function stop() {
    if (timer) {
        clearInterval(timer);
        timer = null;
    }
}

async function refresh() {
    const res = await getOrder(props.orderId);
    if (res?.success && res.data) {
        order.value = res.data;
        local.value = findOrder(props.orderId);
        if (res.data.status === 'PAID') {
            paid.value = true;
            stop();
        }
        return true;
    }
    error.value = humanMessage(res?.message);
    return false;
}

async function startPolling() {
    polling.value = true;
    let tries = 0;
    await refresh();
    stop();
    timer = setInterval(async () => {
        tries += 1;
        const ok = await refresh();
        if (!ok || payable.value || order.value?.status === 'FAILED' || tries > 12) {
            stop();
            polling.value = false;
        }
    }, 1500);
}

async function doPay() {
    if (!payable.value || paying.value) return;
    paying.value = true;
    error.value = '';
    const res = await payOrder(props.orderId);
    paying.value = false;
    if (res?.success) {
        paid.value = true;
        track('biz', 'payment-success', {
            orderId: props.orderId,
            method: method.value,
            paymentId: res.data?.paymentId,
            amountCent: order.value?.amountCent
        });
        await refresh();
        if (local.value) recordOrder(local.value, { status: 'PAID' });
        toast('支付成功', 'ok');
    } else {
        error.value = humanMessage(res?.message);
        track('biz', 'payment-failed', { orderId: props.orderId, message: res?.message });
    }
}

onMounted(() => {
    if (props.orderId.startsWith('QUEUE-')) {
        error.value = '这是排队凭据而不是订单号，请回结算页重新提交。';
        loading.value = false;
        return;
    }
    startPolling().finally(() => {
        loading.value = false;
    });
});

onBeforeUnmount(stop);
</script>

<template>
    <div>
        <div class="gm-breadcrumb">
            <router-link to="/">首页</router-link> &nbsp;&gt;&nbsp;
            <router-link to="/orders">我的订单</router-link> &nbsp;&gt;&nbsp; <b>收银台</b>
        </div>

        <div class="steps">
            <span class="step">1. 填写订单信息</span><span class="bar"></span>
            <span class="step on">2. 支付</span><span class="bar"></span>
            <span class="step">3. 完成</span>
        </div>

        <div class="gm-alert error" v-if="error">{{ error }}</div>

        <div class="gm-panel" v-if="loading">
            <div class="gm-empty">正在查询订单…</div>
        </div>

        <template v-else-if="order">
            <div class="pay-box">
                <div class="gm-panel head">
                    <div class="gm-panel-body head-body">
                        <div>
                            <div class="status">
                                <span class="dot" :class="{ ok: paid }"></span>
                                <b>{{ paid ? '支付成功' : orderStatusText(order.status) }}</b>
                                <span class="gray">{{ waitReason }}</span>
                                <span class="gray" v-if="polling">（状态轮询中…）</span>
                            </div>
                            <div class="amount">
                                <small>¥</small>{{ formatPrice(order.amountCent) }}
                            </div>
                            <div class="gray">订单号 <code>{{ order.orderId }}</code> · 下单用户 {{ order.userId }}</div>
                        </div>
                        <div class="count">
                            <div class="ci">应付金额</div>
                            <div class="cv red">¥{{ formatPrice(order.amountCent) }}</div>
                            <div class="ci">数量</div>
                            <div class="cv">{{ order.quantity }} 件</div>
                            <div class="ci">状态</div>
                            <div class="cv">{{ orderStatusText(order.status) }}</div>
                        </div>
                    </div>
                </div>

                <div class="gm-panel">
                    <div class="gm-panel-head"><span>选择支付方式</span><span class="gray">演示支付：不跳三方、不扣款</span></div>
                    <div class="gm-panel-body methods">
                        <label v-for="m in METHODS" :key="m.id" class="method" :class="{ on: method === m.id }">
                            <input type="radio" :value="m.id" v-model="method" data-track-input="pay-method" />
                            <span class="mcolor" :style="{ background: m.color }">{{ m.name.slice(0, 1) }}</span>
                            <span>
                                <b>{{ m.name }}</b>
                                <i>{{ m.desc }}</i>
                            </span>
                        </label>
                    </div>
                    <div class="gm-row pay-actions">
                        <button class="gm-btn primary lg" :disabled="!payable || paying" data-track="pay-confirm"
                                :data-track-method="method" @click="doPay">
                            {{ paying ? '支付处理中…' : (paid ? '已支付' : `立即支付 ¥${formatPrice(order.amountCent)}`) }}
                        </button>
                        <button class="gm-btn lg" :disabled="paying" data-track="pay-refresh" @click="refresh">刷新状态</button>
                        <button class="gm-btn lg" @click="router.push('/orders')">稍后支付</button>
                    </div>
                </div>

                <div class="gm-panel" v-if="local">
                    <div class="gm-panel-head"><span>本单商品</span></div>
                    <div class="gm-panel-body gm-row">
                        <img :src="local.image || '/images/g-office.png'" :alt="local.name" class="thumb" />
                        <div>
                            <div class="name">{{ local.name }}</div>
                            <div class="gray">{{ local.skuText }} × {{ local.quantity }}</div>
                        </div>
                        <div class="red bold right">¥{{ formatPrice(order.amountCent) }}</div>
                    </div>
                </div>

                <div class="gm-panel ok-panel" v-if="paid">
                    <div class="gm-panel-body">
                        <div class="ok-title">✓ 支付成功，订单已进入 PAID</div>
                        <p>这条链路真实走过了：前端提交 → 网关限流/编排 → order 服务落单 → 异步消费者扣库存 →
                            payment 服务回调改单状态。各服务日志里 grep 订单号即可串起全链路。</p>
                        <div class="gm-row">
                            <router-link class="gm-btn primary" to="/orders" data-track="pay-view-orders">查看我的订单</router-link>
                            <router-link class="gm-btn" to="/products" data-track="pay-continue">继续购物</router-link>
                            <router-link class="gm-btn" to="/track" data-track="pay-view-track">看埋点看板</router-link>
                        </div>
                    </div>
                </div>
            </div>
        </template>

        <div class="gm-panel" v-else>
            <div class="gm-empty">
                <strong>查不到订单 {{ orderId }}</strong>
                <p>order 服务重启后内存订单会清空，旧订单号就查不到了。</p>
                <button class="gm-btn" @click="router.push('/orders')">回我的订单</button>
            </div>
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

.gray {
    font-size: 12px;
    color: var(--gm-text-light);
    font-weight: 400;
}

.head-body {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 24px;
}

.status {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
}

.status .dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--gm-orange);
}

.status .dot.ok {
    background: #2aa512;
}

.amount {
    color: var(--gm-price);
    font-size: 38px;
    font-weight: 700;
    margin: 6px 0;
}

.amount small {
    font-size: 20px;
}

.count {
    text-align: right;
    font-size: 13px;
}

.ci {
    color: var(--gm-text-light);
}

.cv {
    margin-bottom: 6px;
}

.red {
    color: var(--gm-price);
}

.bold {
    font-weight: 700;
}

.methods {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
}

.method {
    display: flex;
    align-items: center;
    gap: 10px;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    padding: 12px 14px;
    cursor: pointer;
}

.method.on {
    border-color: var(--gm-red);
    background: var(--gm-red-light);
}

.mcolor {
    width: 34px;
    height: 34px;
    border-radius: 6px;
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    flex: none;
}

.method b {
    display: block;
    font-size: 14px;
}

.method i {
    display: block;
    font-style: normal;
    font-size: 12px;
    color: var(--gm-text-light);
}

.pay-actions {
    padding: 0 16px 16px;
}

.thumb {
    width: 62px;
    height: 62px;
    object-fit: cover;
    border-radius: var(--gm-radius);
    border: 1px solid var(--gm-border);
}

.name {
    font-size: 14px;
}

.right {
    margin-left: auto;
}

.ok-panel {
    border-color: #b7e6c2;
}

.ok-title {
    color: #1c7a35;
    font-size: 18px;
    font-weight: 600;
    margin-bottom: 8px;
}

.ok-panel p {
    font-size: 13px;
    color: var(--gm-text-weak);
    margin-bottom: 14px;
}

code {
    background: #f5f5f5;
    padding: 1px 5px;
    border-radius: 2px;
}
</style>
