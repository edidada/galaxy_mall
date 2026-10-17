<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { cartItemCount, cart } from './stores/cart';
import { session, logout } from './stores/session';
import { toasts, toast } from './stores/ui';
import { tracker } from './track';

const route = useRoute();
const router = useRouter();
const keyword = ref(route.query.keyword || '');
const traceId = ref('');

// 分类导航与商品服务播种的分类一一对应，写死是为了首屏不依赖接口就能出导航。
const CATEGORIES = [
    { name: '全部', path: '/products' },
    { name: '秒杀专场', path: '/products?category=秒杀专场' },
    { name: '手机通讯', path: '/products?category=手机通讯' },
    { name: '电脑办公', path: '/products?category=电脑办公' },
    { name: '数码配件', path: '/products?category=数码配件' },
    { name: '智能穿戴', path: '/products?category=智能穿戴' },
    { name: '家用电器', path: '/products?category=家用电器' },
    { name: '服饰运动', path: '/products?category=服饰运动' },
    { name: '食品生鲜', path: '/products?category=食品生鲜' }
];

const isConsole = computed(() => route.name === 'api-console');

function onTrace(event) {
    traceId.value = event.detail || '';
}

onMounted(() => {
    window.addEventListener('galaxy-trace', onTrace);
});

onBeforeUnmount(() => {
    window.removeEventListener('galaxy-trace', onTrace);
});

function search() {
    router.push({ path: '/products', query: keyword.value ? { keyword: keyword.value } : {} });
}

async function doLogout() {
    await logout();
    toast('已退出登录');
    router.push('/');
}

function flushTrack() {
    tracker.flush().then(() => toast(`已上报，会话 ${tracker.sessionId.slice(0, 8)}…`));
}
</script>

<template>
    <div class="gm-topbar">
        <div class="gm-container gm-between">
            <div>
                <span>🌌 Galaxy Mall 京东 PC 风格演示站</span>
                <span class="dot">|</span>
                <router-link to="/track">埋点看板</router-link>
                <span class="dot">|</span>
                <router-link to="/console">API 测试台</router-link>
            </div>
            <div>
                <template v-if="session.status === 'authed'">
                    <span>Hi, <b>{{ session.user?.nickname }}</b></span>
                    <span class="dot">|</span>
                    <router-link to="/orders">我的订单</router-link>
                    <span class="dot">|</span>
                    <a href="javascript:void(0)" @click="doLogout">退出</a>
                </template>
                <template v-else>
                    <router-link to="/login">你好，请登录</router-link>
                    <span class="dot">|</span>
                    <router-link to="/login?mode=register">免费注册</router-link>
                </template>
            </div>
        </div>
    </div>

    <header class="gm-header">
        <div class="gm-container gm-row">
            <router-link to="/" class="gm-logo" data-track="header-logo">
                Galaxy Mall<small>京东 PC 风格</small>
            </router-link>
            <div class="gm-search">
                <input
                    v-model="keyword"
                    placeholder="搜索商品，例如：手机 / 显示器 / 咖啡豆"
                    aria-label="搜索商品"
                    data-track-input="header-search"
                    @keyup.enter="search"
                />
                <button type="button" data-track="header-search-submit" @click="search">搜索</button>
            </div>
            <div class="gm-row" style="margin-left: auto">
                <router-link class="gm-cart-entry" to="/cart" data-track="header-cart">
                    🛒 购物车
                    <span class="gm-badge" v-if="cartItemCount">{{ cartItemCount }}</span>
                </router-link>
            </div>
        </div>
    </header>

    <nav class="gm-nav">
        <div class="gm-container gm-nav-inner">
            <router-link
                v-for="c in CATEGORIES"
                :key="c.name"
                :to="c.path"
                :class="{ active: (route.path === '/products' || route.path === '/') && route.query.category === c.name }"
                data-track="nav-category"
                :data-track-category="c.name"
            >{{ c.name }}</router-link>
            <span class="spacer"></span>
            <span class="hint">埋点：{{ tracker.snapshot().sent }} 条已上报 / {{ tracker.snapshot().pending + tracker.snapshot().deadLetter }} 条待发</span>
            <button class="gm-btn sm" style="margin-right: 4px" data-track="nav-track-flush" @click="flushTrack">立即上报</button>
        </div>
    </nav>

    <div class="gm-tracebar" v-if="traceId">
        <div class="gm-container">
            最近一次请求 TraceId<code>{{ traceId }}</code>
            在各微服务日志中 grep 它，即可串联「前端 → 网关 → user/product/order/payment」
        </div>
    </div>

    <main class="gm-main">
        <div :class="isConsole ? '' : 'gm-container'">
            <router-view v-slot="{ Component }">
                <component :is="Component" />
            </router-view>
        </div>
    </main>

    <footer class="gm-footer">
        <div class="links">
            <router-link to="/">首页</router-link>
            <router-link to="/products">全部商品</router-link>
            <router-link to="/orders">我的订单</router-link>
            <router-link to="/track">埋点看板</router-link>
            <router-link to="/console">API 测试台</router-link>
        </div>
        <div>Galaxy Mall · Spring Cloud 微服务演示工程 · 前端行为采集上报至网关 /api/collect</div>
        <div>本站为技术演示，商品图文与账号均为播种数据，不涉及真实交易</div>
    </footer>

    <div class="gm-toast-wrap">
        <div v-for="t in toasts.items" :key="t.id" class="gm-toast" :class="t.kind">{{ t.message }}</div>
    </div>
</template>
