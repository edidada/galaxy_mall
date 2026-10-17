<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { listMallProducts } from '../api';
import { formatPrice, humanMessage } from '../utils';
import ProductCard from '../components/ProductCard.vue';
import { track } from '../track';

const router = useRouter();
const loading = ref(true);
const error = ref('');
const products = ref([]);
const slide = ref(0);
let timer = null;

// 轮播是静态文案 + 场景图，刻意不打接口：首屏要"立刻有东西"，
// 商品数据回来后再填秒杀与楼层，两件事互不阻塞。
const SLIDES = [
    { image: '/images/g-deal.png', title: ' Galaxy 秒杀专场', sub: '每天 10 点 · 1 元限量抢', path: '/products?category=秒杀专场' },
    { image: '/images/g-phone.png', title: '新机首发', sub: 'Galaxy S25 Ultra 现货开卖', path: '/products?category=手机通讯' },
    { image: '/images/g-office.png', title: '办公焕新', sub: '轻薄本 / 4K 显示器 至高减 800', path: '/products?category=电脑办公' },
    { image: '/images/g-home.png', title: '品质生活', sub: '大家电以旧换新 · 冷链直达', path: '/products?category=家用电器' }
];

const seckill = computed(() => products.value.filter(p => p.seckillProduct));
const normal = computed(() => products.value.filter(p => !p.seckillProduct));
const hot = computed(() => normal.value.slice(0, 5));

// 楼层按后端下发的分类聚合，分类名不写死，加商品不用改前端
const floors = computed(() => {
    const map = new Map();
    normal.value.forEach(p => {
        if (!map.has(p.categoryName)) map.set(p.categoryName, []);
        map.get(p.categoryName).push(p);
    });
    return [...map.entries()].map(([name, items]) => ({ name, items: items.slice(0, 5) }));
});

async function load() {
    loading.value = true;
    error.value = '';
    const res = await listMallProducts();
    if (res?.success && Array.isArray(res.data)) {
        products.value = res.data;
        track('biz', 'home-products-loaded', { count: res.data.length });
    } else {
        error.value = humanMessage(res?.message);
    }
    loading.value = false;
}

function go(path) {
    router.push(path);
}

onMounted(() => {
    load();
    timer = setInterval(() => {
        slide.value = (slide.value + 1) % SLIDES.length;
    }, 4500);
});

onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
    <div class="home">
        <div class="gm-row banner-wrap">
            <div class="banner-side">
                <a v-for="c in ['手机通讯', '电脑办公', '数码配件', '智能穿戴', '家用电器', '服饰运动', '食品生鲜']"
                   :key="c"
                   :href="`/products?category=${encodeURIComponent(c)}`"
                   data-track="home-side-category"
                   :data-track-category="c"
                   @click.prevent="go(`/products?category=${encodeURIComponent(c)}`)">
                    {{ c }}<span class="arr">></span>
                </a>
            </div>

            <div class="banner"
                 data-track-expose="home-banner"
                 :data-track-slide="SLIDES[slide].title">
                <img :src="SLIDES[slide].image" :alt="SLIDES[slide].title" />
                <div class="banner-text">
                    <h2>{{ SLIDES[slide].title }}</h2>
                    <p>{{ SLIDES[slide].sub }}</p>
                    <button class="gm-btn primary lg"
                            data-track="home-banner-cta"
                            :data-track-slide="SLIDES[slide].title"
                            @click="go(SLIDES[slide].path)">立即抢购</button>
                </div>
                <div class="dots">
                    <i v-for="(s, i) in SLIDES" :key="s.title"
                       :class="{ on: i === slide }"
                       data-track="home-banner-dot"
                       @click="slide = i"></i>
                </div>
            </div>

            <aside class="banner-card">
                <div class="gm-between card-title"><b>我的 Galaxy</b><span></span></div>
                <p class="tip">{{ '登录后可同步购物车与订单' }}</p>
                <div class="quick">
                    <router-link to="/login" data-track="home-quick-login"><span>登录</span></router-link>
                    <router-link to="/cart" data-track="home-quick-cart"><span>购物车</span></router-link>
                    <router-link to="/orders" data-track="home-quick-orders"><span>订单</span></router-link>
                    <router-link to="/track" data-track="home-quick-track"><span>埋点</span></router-link>
                </div>
                <div class="server-note">
                    <small>演示站说明</small>
                    商品图文、SKU 与账号均为服务播种数据；下单、支付真实调用网关编排接口。
                </div>
            </aside>
        </div>

        <div class="gm-alert" v-if="error" style="margin-bottom:12px">
            {{ error }}
            <button class="gm-btn sm" style="margin-left:8px" @click="load">重试</button>
        </div>

        <section class="gm-panel" v-if="seckill.length" data-track-expose="home-seckill-floor">
            <div class="gm-panel-head">
                <span class="sk-title">⚡ 1 元秒杀 · 限量专场</span>
                <router-link to="/products?category=秒杀专场" class="more" data-track="home-seckill-more">查看全部 ></router-link>
            </div>
            <div class="gm-panel-body">
                <div class="sk-row">
                    <div class="gm-card sk-item"
                         v-for="p in seckill"
                         :key="p.productId"
                         data-track="home-seckill-item"
                         :data-track-product-id="p.productId"
                         @click="go(`/products/${p.productId}`)">
                        <img :src="p.mainImage" :alt="p.name" />
                        <div class="sk-name">{{ p.name }}</div>
                        <div class="sk-sub">{{ p.subtitle }}</div>
                        <div><span class="gm-price"><small>¥</small>{{ formatPrice(p.priceCent) }}</span>
                            <span class="gm-price-origin">¥{{ formatPrice(p.originalPriceCent) }}</span>
                        </div>
                        <div class="sk-bar"><i :style="{ width: '72%' }"></i><span>已抢 72%</span></div>
                    </div>
                </div>
            </div>
        </section>

        <section class="gm-panel">
            <div class="gm-panel-head">
                <span>今日热卖</span>
                <router-link to="/products" class="more" data-track="home-hot-more">更多热门 ></router-link>
            </div>
            <div class="gm-panel-body">
                <div class="gm-grid" v-if="hot.length">
                    <ProductCard v-for="p in hot" :key="p.productId" :product="p" />
                </div>
                <div class="gm-empty" v-else-if="loading">正在加载商品…</div>
                <div class="gm-empty" v-else>
                    <strong>商品服务没有返回数据</strong>
                    <button class="gm-btn sm" @click="load">重新加载</button>
                </div>
            </div>
        </section>

        <section class="gm-panel"
                 v-for="f in floors"
                 :key="f.name"
                 data-track-expose="home-floor"
                 :data-track-floor="f.name">
            <div class="gm-panel-head">
                <span>{{ f.name }}</span>
                <router-link :to="`/products?category=${encodeURIComponent(f.name)}`" class="more" data-track="home-floor-more">进入专场 ></router-link>
            </div>
            <div class="gm-panel-body">
                <div class="gm-grid">
                    <ProductCard v-for="p in f.items" :key="p.productId" :product="p" />
                </div>
            </div>
        </section>
    </div>
</template>

<style scoped>
.home {
    display: block;
}

.banner-wrap {
    align-items: stretch;
    gap: 10px;
    margin-bottom: 12px;
}

.banner-side {
    width: 210px;
    background: #f7f7f7;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    padding: 8px 0;
    flex: none;
}

.banner-side a {
    display: flex;
    justify-content: space-between;
    padding: 7px 14px;
    font-size: 13px;
    color: #4a4a4a;
}

.banner-side a:hover {
    color: var(--gm-red);
    background: #fff;
}

.banner-side .arr {
    color: #bbb;
}

.banner {
    position: relative;
    flex: 1;
    min-width: 0;
    height: 340px;
    border-radius: var(--gm-radius);
    overflow: hidden;
    border: 1px solid var(--gm-border);
    background: #111;
}

.banner img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0.9;
}

.banner-text {
    position: absolute;
    left: 32px;
    top: 96px;
    color: #fff;
    text-shadow: 0 2px 14px rgba(0, 0, 0, 0.5);
}

.banner-text h2 {
    font-size: 32px;
    letter-spacing: 1px;
}

.banner-text p {
    margin: 6px 0 18px;
    font-size: 16px;
    opacity: 0.92;
}

.dots {
    position: absolute;
    right: 16px;
    bottom: 16px;
    display: flex;
    gap: 7px;
}

.dots i {
    width: 22px;
    height: 4px;
    border-radius: 2px;
    background: rgba(255, 255, 255, 0.45);
    cursor: pointer;
}

.dots i.on {
    background: var(--gm-red);
}

.banner-card {
    width: 210px;
    flex: none;
    background: #fff;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.card-title {
    border-bottom: 1px solid var(--gm-border);
    padding-bottom: 8px;
}

.tip {
    font-size: 12px;
    color: var(--gm-text-light);
}

.quick {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
}

.quick a {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 48px;
    background: #fafafa;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    font-size: 13px;
    color: var(--gm-text-weak);
}

.quick a:hover {
    color: var(--gm-red);
    border-color: #ffc9c6;
}

.server-note {
    margin-top: auto;
    font-size: 12px;
    color: var(--gm-text-light);
    line-height: 1.7;
}

.server-note small {
    display: block;
    color: var(--gm-text-weak);
    font-weight: 600;
}

.sk-title {
    color: var(--gm-red);
}

.more {
    font-size: 13px;
    font-weight: 400;
    color: var(--gm-text-light);
}

.sk-row {
    display: flex;
    gap: 12px;
}

.sk-item {
    width: 210px;
    flex: none;
    padding: 12px;
    cursor: pointer;
}

.sk-name {
    margin-top: 8px;
    font-size: 13px;
}

.sk-sub {
    font-size: 12px;
    color: var(--gm-text-light);
    height: 18px;
    overflow: hidden;
}

.sk-bar {
    position: relative;
    margin-top: 8px;
    height: 16px;
    border-radius: 8px;
    background: var(--gm-red-light);
    overflow: hidden;
}

.sk-bar i {
    position: absolute;
    inset: 0 auto 0 0;
    background: linear-gradient(90deg, #ff5b45, var(--gm-red));
}

.sk-bar span {
    position: relative;
    display: block;
    text-align: center;
    font-size: 11px;
    line-height: 16px;
    color: #fff;
    text-shadow: 0 0 3px rgba(0, 0, 0, 0.3);
}
</style>
