<script setup>
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { listMallProducts } from '../api';
import { formatPrice, humanMessage } from '../utils';
import ProductCard from '../components/ProductCard.vue';
import { track } from '../track';

const route = useRoute();
const router = useRouter();

const loading = ref(true);
const error = ref('');
const all = ref([]);
const keyword = ref(route.query.keyword || '');
const category = ref(route.query.category || '');
const sort = ref('default');
const onlySelf = ref(false);
const maxPrice = ref(0);

const CATEGORIES = ['全部', '秒杀专场', '手机通讯', '电脑办公', '数码配件', '智能穿戴', '家用电器', '服饰运动', '食品生鲜'];

// 价格区间用后端下发列表里的最高价做上限，避免出现"区间比最贵的商品还贵"的死区间
const priceStep = computed(() => Math.max(100000, Math.ceil(maxPrice.value / 5 / 10000) * 10000));
const PRICE_RANGES = computed(() => {
    const step = priceStep.value;
    return [
        { label: '全部价格', min: 0, max: Infinity },
        { label: `¥0 - ${formatPrice(step)}`, min: 0, max: step },
        { label: `¥${formatPrice(step)} - ${formatPrice(step * 2)}`, min: step, max: step * 2 },
        { label: `¥${formatPrice(step * 2)} - ${formatPrice(step * 4)}`, min: step * 2, max: step * 4 },
        { label: `¥${formatPrice(step * 4)} 以上`, min: step * 4, max: Infinity }
    ];
});
const priceRange = ref(0);

const filtered = computed(() => {
    const kw = keyword.value.trim().toLowerCase();
    const range = PRICE_RANGES.value[priceRange.value];
    let list = all.value.filter(p => {
        if (category.value && category.value !== '全部' && p.categoryName !== category.value) return false;
        if (onlySelf.value && !(p.tags || []).includes('自营')) return false;
        if (p.priceCent < range.min || p.priceCent > range.max) return false;
        if (!kw) return true;
        const hay = [p.name, p.subtitle, p.categoryName, ...(p.tags || []), ...(p.specNames || [])]
            .join(' ').toLowerCase();
        return hay.includes(kw);
    });
    if (sort.value === 'price-asc') list = [...list].sort((a, b) => a.priceCent - b.priceCent);
    else if (sort.value === 'price-desc') list = [...list].sort((a, b) => b.priceCent - a.priceCent);
    else if (sort.value === 'review') list = [...list].sort((a, b) => b.reviewCount - a.reviewCount);
    else if (sort.value === 'new') list = [...list].sort((a, b) => b.productId.localeCompare(a.productId));
    return list;
});

async function load() {
    loading.value = true;
    error.value = '';
    const res = await listMallProducts();
    if (res?.success && Array.isArray(res.data)) {
        all.value = res.data;
        maxPrice.value = res.data.reduce((m, p) => Math.max(m, p.priceCent), 0);
    } else {
        error.value = humanMessage(res?.message);
    }
    loading.value = false;
}

function pickCategory(name) {
    category.value = name;
    const query = { ...route.query };
    if (name && name !== '全部') query.category = name;
    else delete query.category;
    router.push({ path: '/products', query });
}

// URL 是唯一真相：顶栏搜索、导航分类都改写 URL，这里回读，避免两处状态打架
watch(() => route.query, q => {
    keyword.value = q.keyword || '';
    category.value = q.category || '';
}, { deep: true });

watch(() => [keyword.value, category.value, sort.value, priceRange.value, onlySelf.value], () => {
    track('biz', 'list-filter', {
        keyword: keyword.value,
        category: category.value || '全部',
        sort: sort.value,
        price: PRICE_RANGES.value[priceRange.value].label,
        self: onlySelf.value,
        result: filtered.value.length
    });
});

onMounted(load);
</script>

<template>
    <div>
        <div class="gm-breadcrumb">
            <router-link to="/">首页</router-link> &nbsp;&gt;&nbsp; <b>商品列表</b>
            <template v-if="category && category !== '全部'"> &nbsp;&gt;&nbsp; <b>{{ category }}</b></template>
            <template v-if="keyword"> &nbsp;&gt;&nbsp; 搜索“<b>{{ keyword }}</b>”</template>
        </div>

        <div class="gm-panel filter">
            <div class="gm-panel-body">
                <div class="frow">
                    <span class="flabel">分类</span>
                    <div class="fvals">
                        <a v-for="c in CATEGORIES" :key="c"
                           :class="{ on: (category || '全部') === c }"
                           data-track="list-filter-category"
                           :data-track-category="c"
                           @click.prevent="pickCategory(c)">{{ c }}</a>
                    </div>
                </div>
                <div class="frow">
                    <span class="flabel">价格</span>
                    <div class="fvals">
                        <a v-for="(r, i) in PRICE_RANGES" :key="r.label"
                           :class="{ on: priceRange === i }"
                           data-track="list-filter-price"
                           :data-track-range="r.label"
                           @click.prevent="priceRange = i">{{ r.label }}</a>
                    </div>
                </div>
                <div class="frow">
                    <span class="flabel">其他</span>
                    <div class="fvals">
                        <a :class="{ on: onlySelf }" data-track="list-filter-self" @click.prevent="onlySelf = !onlySelf">只看自营</a>
                    </div>
                </div>
            </div>
        </div>

        <div class="gm-panel">
            <div class="gm-between sortbar">
                <div class="gray">共 <b class="count">{{ filtered.length }}</b> 件商品<span v-if="keyword">，关键词“{{ keyword }}”</span></div>
                <div class="gm-row">
                    <span class="gray">排序</span>
                    <a :class="{ on: sort === 'default' }" data-track="list-sort" data-track-sort="default" @click.prevent="sort = 'default'">综合</a>
                    <a :class="{ on: sort === 'price-asc' }" data-track="list-sort" data-track-sort="price-asc" @click.prevent="sort = 'price-asc'">价格低到高</a>
                    <a :class="{ on: sort === 'price-desc' }" data-track="list-sort" data-track-sort="price-desc" @click.prevent="sort = 'price-desc'">价格高到低</a>
                    <a :class="{ on: sort === 'review' }" data-track="list-sort" data-track-sort="review" @click.prevent="sort = 'review'">评价数</a>
                    <a :class="{ on: sort === 'new' }" data-track="list-sort" data-track-sort="new" @click.prevent="sort = 'new'">新品</a>
                    <div class="gm-search mini">
                        <input v-model="keyword" placeholder="在这页里搜" aria-label="页内搜索" data-track-input="list-inner-search" />
                    </div>
                </div>
            </div>
            <div class="gm-panel-body">
                <div class="gm-alert error" v-if="error">{{ error }}
                    <button class="gm-btn sm" style="margin-left:8px" @click="load">重试</button>
                </div>
                <div class="gm-empty" v-else-if="loading">正在加载商品…</div>
                <div class="gm-empty" v-else-if="!filtered.length">
                    <strong>没有符合条件的商品</strong>
                    试试放宽价格区间，或
                    <a href="javascript:void(0)" @click="pickCategory('全部'); priceRange = 0; onlySelf = false; keyword = ''">清空筛选</a>
                </div>
                <div class="gm-grid list-grid" v-else>
                    <ProductCard v-for="p in filtered" :key="p.productId" :product="p" />
                </div>
            </div>
        </div>
    </div>
</template>

<style scoped>
.filter .gm-panel-body {
    padding: 6px 16px;
}

.frow {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px dashed var(--gm-border);
}

.frow:last-child {
    border-bottom: 0;
}

.flabel {
    width: 44px;
    flex: none;
    color: var(--gm-text-light);
    font-size: 13px;
    line-height: 26px;
}

.fvals {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.fvals a,
.sortbar a {
    cursor: pointer;
    display: inline-block;
    padding: 3px 12px;
    border-radius: 13px;
    font-size: 13px;
    color: var(--gm-text-weak);
    border: 1px solid transparent;
}

.fvals a:hover,
.sortbar a:hover {
    color: var(--gm-red);
}

.fvals a.on,
.sortbar a.on {
    color: #fff;
    background: var(--gm-red);
    border-color: var(--gm-red);
}

.sortbar {
    padding: 10px 16px;
    border-bottom: 1px solid var(--gm-border);
}

.count {
    color: var(--gm-red);
    font-weight: 700;
    margin: 0 2px;
}

.gray {
    color: var(--gm-text-light);
    font-size: 13px;
}

.mini {
    height: 30px;
    max-width: 180px;
    border-width: 1px;
    margin-left: 8px;
}

.list-grid {
    grid-template-columns: repeat(5, 1fr);
}

@media (max-width: 1280px) {
    .list-grid {
        grid-template-columns: repeat(4, 1fr);
    }
}
</style>
