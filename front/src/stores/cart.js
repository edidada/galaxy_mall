/**
 * 购物车：纯前端态（localStorage 持久化）。
 *
 * 后端没有购物车服务，这是刻意的范围决定：本次只补了登录、图文列表、埋点上报三组接口，
 * 购物车要真实落库得加一张按 userId 分片的表 + 一套失效/合并逻辑，属于另一个话题。
 * 这里做到"跨页面刷新不丢、结算时把 SKU 与数量原样带给下单接口"，
 * 结算链路仍然真实走网关 /api/gateway/orders，所以限流与排队大厅都生效。
 */
import { reactive, computed, watch } from 'vue';
import { tracker } from '../track';

const LS_KEY = 'gm_cart';

const state = reactive({ items: [] });

function load() {
    try {
        const raw = JSON.parse(localStorage.getItem(LS_KEY) || '[]');
        state.items = Array.isArray(raw) ? raw : [];
    } catch {
        state.items = [];
    }
}

function persist() {
    try {
        localStorage.setItem(LS_KEY, JSON.stringify(state.items));
    } catch {
        /* ignore */
    }
}

function lineKey({ productId, skuId }) {
    return `${productId}::${skuId || 'default'}`;
}

export function addToCart({ product, sku, quantity = 1 }) {
    const key = lineKey({ productId: product.productId, skuId: sku?.skuId });
    const existing = state.items.find(item => item.key === key);
    if (existing) {
        existing.quantity += quantity;
        // 价格以商品服务当前下发值为准，避免挂着旧页面把秒杀价当常态价
        existing.priceCent = sku?.priceCent ?? product.priceCent;
    } else {
        state.items.push({
            key,
            productId: product.productId,
            skuId: sku?.skuId || '',
            name: product.name,
            subtitle: product.subtitle,
            image: sku?.image || product.mainImage,
            specText: sku?.specText || '默认规格',
            priceCent: sku?.priceCent ?? product.priceCent,
            stock: sku?.stock ?? null,
            seckill: !!product.seckillProduct,
            quantity,
            selected: true,
            addedAt: Date.now()
        });
    }
    tracker.track('biz', 'cart-add', {
        productId: product.productId,
        skuId: sku?.skuId || 'default',
        quantity,
        priceCent: sku?.priceCent ?? product.priceCent
    });
    return state.items.find(item => item.key === key);
}

export function setQuantity(key, quantity) {
    const item = state.items.find(entry => entry.key === key);
    if (!item) return;
    item.quantity = Math.max(1, Math.min(99, Number(quantity) || 1));
    tracker.track('biz', 'cart-quantity', { productId: item.productId, skuId: item.skuId, quantity: item.quantity });
}

export function removeItem(key) {
    const index = state.items.findIndex(item => item.key === key);
    if (index < 0) return;
    const [removed] = state.items.splice(index, 1);
    tracker.track('biz', 'cart-remove', { productId: removed.productId, skuId: removed.skuId });
}

export function toggleSelected(key, selected) {
    const item = state.items.find(entry => entry.key === key);
    if (item) item.selected = selected;
}

export function selectAll(selected) {
    state.items.forEach(item => { item.selected = selected; });
}

export function clearSelected() {
    state.items = state.items.filter(item => !item.selected);
}

export function clearCart() {
    state.items = [];
}

export const cartItemCount = computed(() => state.items.reduce((sum, item) => sum + item.quantity, 0));
export const selectedItems = computed(() => state.items.filter(item => item.selected));
export const selectedCount = computed(() => selectedItems.value.reduce((sum, item) => sum + item.quantity, 0));
export const selectedTotalCent = computed(() =>
    selectedItems.value.reduce((sum, item) => sum + item.priceCent * item.quantity, 0)
);
export const allSelected = computed(() => state.items.length > 0 && state.items.every(item => item.selected));

load();
watch(() => state.items, persist, { deep: true });

export const cart = state;
