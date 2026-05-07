package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductView;

public interface ProductClient {

    ApiResponse<ProductView> getProduct(String productId);
}
