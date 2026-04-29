package cn.wdidada.galaxymall.common.domain;

public record Product(
        String productId,
        String name,
        int priceCent,
        boolean seckillProduct,
        String staticPage
) {
}
