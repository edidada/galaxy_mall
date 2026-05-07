package cn.wdidada.galaxymall.common.api;

import java.io.Serial;
import java.io.Serializable;

public record ProductView(
        String productId,
        String name,
        int priceCent,
        boolean seckillProduct,
        String staticPage
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
