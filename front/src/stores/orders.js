/**
 * 本地订单簿。
 *
 * 后端订单服务只有 GET /api/orders/{orderId}（按单查），没有"按用户列订单"，
 * 所以这里把用户在本站下成功的订单号记在 localStorage，进入"我的订单"时再逐个回源查状态。
 * 这样列表是本地索引、状态是服务端真相，不会出现前端显示已支付、后端其实没有这单的情况。
 */
import { reactive } from 'vue';
import { getOrder } from '../api';

const LS_KEY = 'gm_orders';

const state = reactive({ orders: [] });

function load() {
    try {
        const raw = JSON.parse(localStorage.getItem(LS_KEY) || '[]');
        state.orders = Array.isArray(raw) ? raw : [];
    } catch {
        state.orders = [];
    }
}

function persist() {
    try {
        localStorage.setItem(LS_KEY, JSON.stringify(state.orders.slice(0, 200)));
    } catch {
        /* ignore */
    }
}

export function recordOrder(view, extra = {}) {
    if (!view?.orderId) return;
    const exists = state.orders.find(order => order.orderId === view.orderId);
    const entry = {
        orderId: view.orderId,
        userId: view.userId,
        productId: view.productId,
        quantity: view.quantity,
        amountCent: view.amountCent,
        status: view.status,
        queueToken: view.queueToken || null,
        createdAt: view.acceptedEpochMillis || Date.now(),
        updatedAt: Date.now(),
        ...extra
    };
    if (exists) Object.assign(exists, entry);
    else state.orders.unshift(entry);
    persist();
}

/** 逐单回源；某一条查失败不影响其它条展示（服务重启后旧订单号会查不到）。 */
export async function refreshOrders() {
    const results = await Promise.all(state.orders.map(async order => {
        try {
            const res = await getOrder(order.orderId);
            if (res?.success && res.data) {
                order.status = res.data.status;
                order.amountCent = res.data.amountCent;
                order.updatedAt = Date.now();
                return { orderId: order.orderId, ok: true, status: res.data.status };
            }
            return { orderId: order.orderId, ok: false, message: res?.message };
        } catch (e) {
            return { orderId: order.orderId, ok: false, message: e.message };
        }
    }));
    persist();
    return results;
}

export function findOrder(orderId) {
    return state.orders.find(order => order.orderId === orderId) || null;
}

export function clearOrders() {
    state.orders = [];
    persist();
}

export const orders = state;

load();
