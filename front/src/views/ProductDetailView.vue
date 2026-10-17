<script setup>
import { ref, computed, watch, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { getMallProductDetail, listMallProducts } from '../api';
import { formatPrice, humanMessage } from '../utils';
import { addToCart } from '../stores/cart';
import { toast } from '../stores/ui';
import { isLoggedIn, session } from '../stores/session';
import QtyStepper from '../components/QtyStepper.vue';
import ProductCard from '../components/ProductCard.vue';
import { track } from '../track';

const props = defineProps({ productId: { type: String, required: true } });
const router = useRouter();

const loading = ref(true);
const error = ref('');
const detail = ref(null);
const activeImage = ref(0);
const quantity = ref(1);
// selectedSpecs 形如 { "颜色": "黑色", "内存": "12G" }，键来自后端 specNames，值来自 SKU 的 specs
const selectedSpecs = ref({});
const related = ref([]);

const item = computed(() => detail.value?.item || null);
const skus = computed(() => item.value?.skus || []);
const specNames = computed(() => item.value?.specNames || []);

/** 每个规格维度可选项：从 SKU 反推，且只保留在当前已选维度下仍有 SKU 的组合（JD 的"灰掉"逻辑）。 */
const specGroups = computed(() => {
    return specNames.value.map(name => {
        const options = [];
        skus.value.forEach(sku => {
            const value = sku.specs?.[name];
            if (!value || options.includes(value)) return;
            options.push(value);
        });
        const live = options.filter(value => {
            const probe = { ...selectedSpecs.value, [name]: value };
            return skus.value.some(sku => Object.entries(probe).every(([k, v]) => sku.specs?.[k] === v));
        });
        return { name, options, live };
    });
});

const matchedSku = computed(() => {
    if (!skus.value.length) return null;
    const need = Object.entries(selectedSpecs.value);
    return skus.value.find(sku => need.every(([k, v]) => sku.specs?.[k] === v)) || null;
});

const priceCent = computed(() => matchedSku.value?.priceCent ?? item.value?.priceCent ?? 0);
const stock = computed(() => matchedSku.value?.stock ?? null);
const soldOut = computed(() => matchedSku.value && matchedSku.value.stock === 0);

const savings = computed(() => {
    const origin = item.value?.originalPriceCent || 0;
    return origin > priceCent.value ? origin - priceCent.value : 0;
});

/** 切规格时把该维度默认值填上，保证一进详情页就有可选中的 SKU。 */
function initSpecs() {
    const next = {};
    specNames.value.forEach(name => {
        const first = skus.value.find(s => s.specs?.[name] && s.stock !== 0) || skus.value[0];
        next[name] = first?.specs?.[name] || '';
    });
    selectedSpecs.value = next;
}

function pickSpec(name, value) {
    if (!specGroups.value.find(g => g.name === name)?.live.includes(value)) return;
    selectedSpecs.value = { ...selectedSpecs.value, [name]: value };
    track('click', 'detail-spec', { productId: props.productId, spec: `${name}=${value}`, skuId: matchedSku.value?.skuId });
    if (matchedSku.value?.image) {
        const idx = images.value.findIndex(img => img === matchedSku.value.image);
        if (idx >= 0) activeImage.value = idx;
    }
}

const images = computed(() => {
    const list = [...(item.value?.images || [])];
    const skuImage = matchedSku.value?.image;
    if (skuImage && !list.includes(skuImage)) list.unshift(skuImage);
    return list.length ? list : [item.value?.mainImage].filter(Boolean);
});

watch(() => images.value.length, () => {
    if (activeImage.value >= images.value.length) activeImage.value = 0;
});

async function load() {
    loading.value = true;
    error.value = '';
    const res = await getMallProductDetail(props.productId);
    if (res?.success && res.data) {
        detail.value = res.data;
        initSpecs();
        activeImage.value = 0;
        loadRelated(res.data.item.categoryName, res.data.item.productId);
    } else {
        error.value = humanMessage(res?.message);
    }
    loading.value = false;
}

/** 相关推荐直接复用列表接口取同类，不额外加后端接口。 */
async function loadRelated(category, selfId) {
    const res = await listMallProducts({ category });
    if (res?.success && Array.isArray(res.data)) {
        related.value = res.data.filter(p => p.productId !== selfId).slice(0, 5);
    }
}

function requireLoginFor(action) {
    if (isLoggedIn.value) return true;
    toast(`${action}需要先登录`, 'err');
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } });
    return false;
}

function addCart() {
    if (!item.value) return;
    if (soldOut.value) {
        toast('该规格已售罄', 'err');
        return;
    }
    addToCart({ product: item.value, sku: matchedSku.value, quantity: quantity.value });
    toast(`已加入购物车：${matchedSku.value?.specText || item.value.name}`, 'ok');
}

function buyNow() {
    if (!requireLoginFor('立即购买')) return;
    if (soldOut.value) {
        toast('该规格已售罄', 'err');
        return;
    }
    // 立即购买把"这一条"当作临时购物车项，结算页只读 selected 的行
    addToCart({ product: item.value, sku: matchedSku.value, quantity: quantity.value });
    const line = { productId: item.value.productId, skuId: matchedSku.value?.skuId || '' };
    sessionStorage.setItem('gm_buy_now', JSON.stringify(line));
    router.push('/checkout');
}

onMounted(() => {
    load();
    track('biz', 'detail-view', { productId: props.productId, userId: session.user?.userId || '' });
});

watch(() => props.productId, load);
</script>

<template>
    <div>
        <div class="gm-breadcrumb">
            <router-link to="/">首页</router-link> &nbsp;&gt;&nbsp;
            <router-link to="/products">商品</router-link> &nbsp;&gt;&nbsp;
            <router-link :to="`/products?category=${encodeURIComponent(item?.categoryName || '')}`">{{ item?.categoryName || '分类' }}</router-link>
            &nbsp;&gt;&nbsp; <b>{{ item?.name || '商品详情' }}</b>
        </div>

        <div class="gm-alert error" v-if="error">{{ error }}
            <button class="gm-btn sm" style="margin-left:8px" @click="load">重试</button>
        </div>

        <div class="gm-panel" v-else-if="loading">
            <div class="gm-empty">正在加载商品详情…</div>
        </div>

        <template v-else-if="item">
            <div class="gm-panel detail-top">
                <div class="detail-body">
                    <div class="gallery">
                        <div class="gallery-main" data-track-expose="detail-gallery" :data-track-product-id="item.productId">
                            <img :src="images[activeImage]" :alt="item.name" />
                        </div>
                        <div class="thumbs">
                            <img v-for="(img, i) in images" :key="img + i"
                                 :src="img" :alt="`${item.name} 图 ${i + 1}`"
                                 :class="{ on: i === activeImage }"
                                 data-track="detail-thumb" :data-track-index="i"
                                 @click="activeImage = i" />
                        </div>
                        <div class="gal-actions">
                            <span class="gray">收藏商品</span>
                            <span class="gray">已收藏 {{ (item.reviewCount || 0) % 900 + 36 }}</span>
                        </div>
                    </div>

                    <div class="info">
                        <h1>{{ item.name }}</h1>
                        <p class="sub">{{ item.subtitle }}</p>
                        <div class="promo">
                            <span class="promo-label">促销</span>
                            <span class="gm-tag" v-for="t in item.tags" :key="t">{{ t }}</span>
                            <span class="gm-tag gray" v-if="item.seckillProduct">秒杀限量</span>
                        </div>

                        <div class="price-box">
                            <div class="price-line">
                                <span class="plabel">{{ item.seckillProduct ? '秒杀价' : '京东价' }}</span>
                                <span class="now"><small>¥</small>{{ formatPrice(priceCent) }}</span>
                                <span class="origin" v-if="item.originalPriceCent > priceCent">¥{{ formatPrice(item.originalPriceCent) }}</span>
                                <span class="save" v-if="savings">立省 ¥{{ formatPrice(savings) }}</span>
                            </div>
                            <div class="meta-line">
                                <span>累计评价 <b>{{ (item.reviewCount || 0).toLocaleString() }}</b></span>
                                <span>评分 <b>{{ item.rating }}</b></span>
                                <span v-if="matchedSku">库存 <b :class="{ red: soldOut }">{{ soldOut ? '已售罄' : stock + ' 件' }}</b></span>
                            </div>
                        </div>

                        <div class="spec-rows">
                            <div class="spec-row" v-for="g in specGroups" :key="g.name">
                                <span class="slabel">{{ g.name }}</span>
                                <div class="svals">
                                    <button v-for="o in g.options" :key="o"
                                            :class="{ on: selectedSpecs[g.name] === o, dim: !g.live.includes(o) }"
                                            :disabled="!g.live.includes(o)"
                                            @click="pickSpec(g.name, o)">
                                        {{ o }}
                                    </button>
                                </div>
                            </div>

                            <div class="spec-row">
                                <span class="slabel">数量</span>
                                <QtyStepper v-model="quantity" :min="1" :max="Math.max(1, Math.min(99, stock || 99))" :disabled="soldOut" />
                            </div>

                            <div class="spec-row" v-if="matchedSku">
                                <span class="slabel">已选</span>
                                <span class="chosen">{{ item.name }} · {{ matchedSku.specText }} × {{ quantity }}</span>
                            </div>
                        </div>

                        <div class="buy-row">
                            <button class="gm-btn orange lg" :disabled="soldOut" data-track="detail-add-cart"
                                    :data-track-product-id="item.productId" :data-track-sku-id="matchedSku?.skuId"
                                    @click="addCart">加入购物车</button>
                            <button class="gm-btn primary lg" :disabled="soldOut" data-track="detail-buy-now"
                                    :data-track-product-id="item.productId" :data-track-sku-id="matchedSku?.skuId"
                                    @click="buyNow">立即购买</button>
                            <router-link class="gm-btn lg" to="/cart" data-track="detail-go-cart">查看购物车</router-link>
                        </div>

                        <div class="service">
                            <span class="slabel">服务</span>
                            <div>
                                <p><b>{{ detail.shopName }}</b> · {{ detail.serviceNote }}</p>
                                <p class="gray">配送 {{ detail.deliveryNote }} · 本单为演示数据，不会产生真实交易</p>
                            </div>
                        </div>

                        <ul class="highlights">
                            <li v-for="h in detail.highlights" :key="h">✓ {{ h }}</li>
                        </ul>
                    </div>
                </div>
            </div>

            <div class="gm-panel">
                <div class="gm-panel-head"><span>商品详情</span><span class="gray">图文由商品服务下发（ProductShowcaseCatalog）</span></div>
                <div class="gm-panel-body">
                    <div class="rich" v-html="detail.detailHtml"></div>
                </div>
            </div>

            <div class="gm-panel" v-if="related.length">
                <div class="gm-panel-head"><span>同类推荐</span>
                    <router-link class="more" :to="`/products?category=${encodeURIComponent(item.categoryName)}`">更多同类</router-link>
                </div>
                <div class="gm-panel-body">
                    <div class="gm-grid">
                        <ProductCard v-for="p in related" :key="p.productId" :product="p" compact />
                    </div>
                </div>
            </div>
        </template>
    </div>
</template>

<style scoped>
.detail-top .detail-body {
    display: grid;
    grid-template-columns: 400px 1fr;
    gap: 24px;
    padding: 18px;
}

.gallery-main {
    width: 400px;
    height: 400px;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    overflow: hidden;
    background: #fafafa;
}

.gallery-main img {
    width: 100%;
    height: 100%;
    object-fit: cover;
}

.thumbs {
    display: flex;
    gap: 8px;
    margin-top: 10px;
}

.thumbs img {
    width: 62px;
    height: 62px;
    object-fit: cover;
    border: 2px solid transparent;
    border-radius: var(--gm-radius);
    cursor: pointer;
}

.thumbs img.on {
    border-color: var(--gm-red);
}

.gal-actions {
    display: flex;
    gap: 16px;
    margin-top: 12px;
    font-size: 12px;
}

.gray {
    color: var(--gm-text-light);
}

.info h1 {
    font-size: 20px;
    font-weight: 600;
    line-height: 1.4;
}

.sub {
    color: var(--gm-red);
    font-size: 13px;
    margin: 6px 0 12px;
}

.promo {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    margin-bottom: 12px;
}

.promo-label {
    color: var(--gm-text-light);
}

.price-box {
    background: #fafafa;
    border-radius: var(--gm-radius);
    padding: 12px 14px;
    margin-bottom: 14px;
}

.price-line {
    display: flex;
    align-items: baseline;
    gap: 10px;
}

.plabel {
    font-size: 13px;
    color: var(--gm-text-weak);
}

.now {
    color: var(--gm-price);
    font-size: 30px;
    font-weight: 700;
}

.now small {
    font-size: 15px;
    margin-right: 2px;
}

.origin {
    color: var(--gm-text-light);
    text-decoration: line-through;
    font-size: 13px;
}

.save {
    background: var(--gm-red-light);
    color: var(--gm-red-dark);
    font-size: 12px;
    padding: 1px 6px;
    border-radius: 2px;
}

.meta-line {
    display: flex;
    gap: 20px;
    margin-top: 8px;
    font-size: 12px;
    color: var(--gm-text-weak);
}

.meta-line b {
    color: var(--gm-text);
    font-weight: 400;
}

.red {
    color: var(--gm-red);
}

.spec-rows {
    border-top: 1px dashed var(--gm-border);
    padding-top: 6px;
}

.spec-row {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 8px 0;
}

.slabel {
    width: 46px;
    flex: none;
    color: var(--gm-text-light);
    font-size: 13px;
    line-height: 30px;
}

.svals {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.svals button {
    height: 30px;
    padding: 0 14px;
    border: 1px solid var(--gm-border-strong);
    border-radius: var(--gm-radius);
    background: #fff;
    cursor: pointer;
    font-size: 13px;
}

.svals button.on {
    border-color: var(--gm-red);
    color: var(--gm-red);
    background: var(--gm-red-light);
}

.svals button.dim {
    color: #ccc;
    background: #f7f7f7;
    cursor: not-allowed;
}

.chosen {
    font-size: 13px;
    color: var(--gm-text-weak);
    line-height: 32px;
}

.buy-row {
    display: flex;
    gap: 12px;
    margin: 16px 0;
}

.service {
    display: flex;
    gap: 12px;
    border-top: 1px dashed var(--gm-border);
    padding-top: 10px;
    font-size: 13px;
}

.service p {
    margin-bottom: 2px;
}

.highlights {
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
    margin-top: 12px;
    font-size: 12px;
    color: var(--gm-text-weak);
}

.rich :deep(img) {
    width: 100%;
}

.more {
    font-size: 13px;
    font-weight: 400;
    color: var(--gm-text-light);
}

@media (max-width: 1000px) {
    .detail-top .detail-body {
        grid-template-columns: 1fr;
    }
}
</style>
