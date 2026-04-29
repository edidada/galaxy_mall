package cn.wdidada.galaxymall.common.api;

public record ProductView(
        String productId,
        String name,
        int priceCent,
        boolean seckillProduct,
        String staticPage
) {
}
