<script setup>
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { cart, removeItem, setQuantity, toggleSelected, selectAll, clearSelected, allSelected, selectedCount, selectedTotalCent } from '../stores/cart';
import { formatPrice } from '../utils';
import { toast } from '../stores/ui';
import QtyStepper from '../components/QtyStepper.vue';
import { isLoggedIn } from '../stores/session';

const router = useRouter();
const confirmClear = ref(false);

const rows = computed(() => cart.items);
const shipping = computed(() => selectedTotalCent.value >= 9900 || selectedCount.value === 0 ? 0 : 1000);
const payCent = computed(() => selectedTotalCent.value + shipping.value);
const invalidLines = computed(() => rows.value.filter(item => item.stock === 0));

function checkout() {
    if (!selectedCount.value) {
        toast('先勾选要结算的商品', 'err');
        return;
    }
    // 未登录也能进结算页看清单，但提交那一步必须登录：下单要用 userId 过后网关的用户校验
    if (!isLoggedIn.value) {
        toast('请先登录后再结算', 'err');
        router.push({ path: '/login', query: { redirect: '/checkout' } });
        return;
    }
    router.push('/checkout');
}

function doClearSelected() {
    clearSelected();
    confirmClear.value = false;
    toast('已删除勾选商品');
}
</script>

<template>
    <div>
        <div class="gm-breadcrumb"><router-link to="/">首页</router-link> &nbsp;&gt;&nbsp; <b>购物车</b></div>

        <div class="gm-panel">
            <div class="gm-panel-head">
                <span>购物车</span>
                <span class="gray">共 {{ rows.length }} 种商品 · 勾选 {{ selectedCount }} 件</span>
            </div>

            <div class="gm-empty" v-if="!rows.length">
                <strong>购物车空空如也</strong>
                <p>去挑点东西吧，购物车只在本地浏览器保存（刷新不丢，换设备不同步）。</p>
                <button class="gm-btn primary" data-track="cart-go-list" @click="router.push('/products')">开始购物</button>
            </div>

            <template v-else>
                <table class="gm-table">
                    <thead>
                    <tr>
                        <th style="width:60px">
                            <input type="checkbox" :checked="allSelected" aria-label="全选" data-track-input="cart-select-all"
                                   @change="selectAll($event.target.checked)" />
                        </th>
                        <th>商品</th>
                        <th style="width:110px">单价</th>
                        <th style="width:130px">数量</th>
                        <th style="width:110px">小计</th>
                        <th style="width:90px">操作</th>
                    </tr>
                    </thead>
                    <tbody>
                    <tr v-for="item in rows" :key="item.key" :class="{ dead: item.stock === 0 }">
                        <td>
                            <input type="checkbox" :checked="item.selected" aria-label="选择该商品"
                                   data-track-input="cart-select-item" :data-track-product-id="item.productId"
                                   @change="toggleSelected(item.key, $event.target.checked)" />
                        </td>
                        <td>
                            <div class="gm-row line">
                                <img :src="item.image" :alt="item.name" class="thumb"
                                     data-track="cart-line-image" :data-track-product-id="item.productId"
                                     @click="router.push(`/products/${item.productId}`)" />
                                <div class="line-info">
                                    <router-link :to="`/products/${item.productId}`" class="name"
                                                 data-track="cart-line-title" :data-track-product-id="item.productId">{{ item.name }}</router-link>
                                    <div class="spec">{{ item.specText }}</div>
                                    <div class="tags">
                                        <span class="gm-tag" v-if="item.seckill">秒杀</span>
                                        <span class="gm-tag gray" v-if="item.skuId">SKU {{ item.skuId.slice(-6) }}</span>
                                        <span class="gm-tag orange" v-else>默认规格</span>
                                    </div>
                                    <div class="oos" v-if="item.stock === 0">该规格已售罄，下单会被库存服务拒绝</div>
                                </div>
                            </div>
                        </td>
                        <td class="red">¥{{ formatPrice(item.priceCent) }}</td>
                        <td>
                            <QtyStepper :model-value="item.quantity" :max="Math.min(99, item.stock || 99)"
                                        @update:model-value="setQuantity(item.key, $event)" />
                        </td>
                        <td class="red bold">¥{{ formatPrice(item.priceCent * item.quantity) }}</td>
                        <td>
                            <a href="javascript:void(0)" class="del"
                               data-track="cart-remove" :data-track-product-id="item.productId"
                               @click="removeItem(item.key)">删除</a>
                        </td>
                    </tr>
                    </tbody>
                </table>

                <div class="gm-between bar">
                    <div class="gm-row">
                        <label class="sel-all">
                            <input type="checkbox" :checked="allSelected" aria-label="全选" data-track-input="cart-select-all-bottom"
                                   @change="selectAll($event.target.checked)" />
                            全选
                        </label>
                        <a href="javascript:void(0)" data-track="cart-clear-selected" @click="confirmClear = true">删除勾选商品</a>
                        <a href="javascript:void(0)" data-track="cart-go-list" @click="router.push('/products')">继续购物</a>
                        <span class="gray" v-if="invalidLines.length">有 {{ invalidLines.length }} 条已售罄</span>
                    </div>
                    <div class="gm-row settle">
                        <span>已选 <b class="red">{{ selectedCount }}</b> 件</span>
                        <span>运费 <b>{{ shipping ? `¥${formatPrice(shipping)}` : '免运费' }}</b></span>
                        <span class="total">应付：<b>¥{{ formatPrice(payCent) }}</b></span>
                        <button class="gm-btn primary lg" data-track="cart-checkout" :data-track-count="selectedCount" @click="checkout">去结算</button>
                    </div>
                </div>
            </template>
        </div>

        <div class="mask" v-if="confirmClear" @click.self="confirmClear = false">
            <div class="gm-card dialog">
                <div class="gm-panel-head"><span>确认删除？</span></div>
                <div class="gm-panel-body">
                    只删除购物车里勾选的 {{ selectedCount }} 件，不影响后端数据。
                </div>
                <div class="gm-row dlg-actions">
                    <button class="gm-btn" @click="confirmClear = false">取消</button>
                    <button class="gm-btn primary" data-track="cart-clear-confirm" @click="doClearSelected">确认删除</button>
                </div>
            </div>
        </div>
    </div>
</template>

<style scoped>
.gray {
    font-size: 13px;
    font-weight: 400;
    color: var(--gm-text-light);
}

.thumb {
    width: 70px;
    height: 70px;
    object-fit: cover;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    cursor: pointer;
    flex: none;
}

.line {
    align-items: flex-start;
}

.line-info {
    min-width: 0;
}

.name {
    font-size: 13px;
    line-height: 1.5;
}

.spec {
    font-size: 12px;
    color: var(--gm-text-light);
    margin: 2px 0 4px;
}

.tags {
    display: flex;
    gap: 4px;
}

.oos {
    margin-top: 4px;
    font-size: 12px;
    color: var(--gm-red-dark);
}

.red {
    color: var(--gm-price);
}

.bold {
    font-weight: 700;
}

.del:hover {
    color: var(--gm-red);
    text-decoration: underline;
}

.dead {
    opacity: 0.55;
}

.bar {
    padding: 12px 16px;
    background: #fafafa;
    border-top: 1px solid var(--gm-border);
    flex-wrap: wrap;
}

.bar a {
    font-size: 13px;
    color: var(--gm-text-weak);
}

.sel-all {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    cursor: pointer;
}

.settle {
    font-size: 13px;
    color: var(--gm-text-weak);
}

.total b {
    color: var(--gm-price);
    font-size: 22px;
    font-weight: 700;
}

.mask {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.35);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 2000;
}

.dialog {
    width: 420px;
}

.dlg-actions {
    justify-content: flex-end;
    padding: 0 16px 16px;
}

p {
    color: var(--gm-text-light);
    font-size: 13px;
    margin-bottom: 14px;
}
</style>
