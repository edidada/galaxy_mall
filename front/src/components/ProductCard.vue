<script setup>
import { useRouter } from 'vue-router';
import { formatPrice } from '../utils';
import { addToCart } from '../stores/cart';
import { toast } from '../stores/ui';

const props = defineProps({
    product: { type: Object, required: true },
    compact: { type: Boolean, default: false }
});

const router = useRouter();

// 列表卡片的"加购"用默认 SKU（skus[0]），JD 也是这个交互：
// 列表页不展开规格，点了先进车，规格去购物车/详情页再改。
function quickAdd(product) {
    if (product.seckillProduct) {
        toast('秒杀商品请进详情页参与', 'err');
        return;
    }
    addToCart({ product, sku: product.skus?.[0], quantity: 1 });
    toast('已加入购物车');
}
</script>

<template>
    <div class="gm-card gm-pcard">
        <div
            class="gm-pcard-img"
            data-track="product-card"
            :data-track-product-id="product.productId"
            @click="router.push(`/products/${product.productId}`)"
        >
            <img :src="product.mainImage" :alt="product.name" loading="lazy" />
            <span class="gm-pcard-flag" v-if="product.seckillProduct">秒杀</span>
        </div>
        <div
            class="gm-pcard-title"
            data-track="product-card-title"
            :data-track-product-id="product.productId"
            @click="router.push(`/products/${product.productId}`)"
        >{{ product.name }}</div>
        <div class="gm-pcard-sub">{{ product.subtitle }}</div>
        <div v-if="!compact" style="margin: 4px 0 2px">
            <span v-for="t in (product.tags || []).slice(0, 2)" :key="t" class="gm-tag">{{ t }}</span>
        </div>
        <div class="gm-between" style="margin-top: 6px">
            <div>
                <span class="gm-price"><small>¥</small>{{ formatPrice(product.priceCent) }}</span>
                <span class="gm-price-origin" v-if="product.originalPriceCent > product.priceCent">
                    ¥{{ formatPrice(product.originalPriceCent) }}
                </span>
            </div>
            <button class="gm-btn sm" data-track="product-card-add" :data-track-product-id="product.productId" @click.stop="quickAdd(product)">
                加入购物车
            </button>
        </div>
        <div class="gm-review" style="margin-top: 4px">
            {{ product.reviewCount ? product.reviewCount.toLocaleString() : 0 }} 条评价 · {{ product.rating }} 分
        </div>
    </div>
</template>
