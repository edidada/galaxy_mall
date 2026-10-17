<script setup>
import { useRouter } from 'vue-router';
const router = useRouter();
</script>

<template>
    <div class="gm-panel">
        <div class="gm-empty nf">
            <strong>404 · 这个页面不存在</strong>
            <p>地址可能来自旧的埋点看板或手输错了路径。</p>
            <div class="row">
                <button class="gm-btn primary" data-track="404-home" @click="router.push('/')">回首页</button>
                <button class="gm-btn" data-track="404-list" @click="router.push('/products')">看全部商品</button>
            </div>
        </div>
    </div>
</template>

<style scoped>
.nf {
    padding: 90px 0;
}

.nf p {
    color: var(--gm-text-light);
    font-size: 13px;
    margin-bottom: 16px;
}

.row {
    display: flex;
    gap: 10px;
    justify-content: center;
}
</style>
