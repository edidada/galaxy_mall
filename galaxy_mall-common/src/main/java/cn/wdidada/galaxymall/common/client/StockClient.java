package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
import cn.wdidada.galaxymall.common.api.StockView;
public interface StockClient {

    ApiResponse<StockView> reserve(StockDeductRequest request);

    ApiResponse<StockView> confirm(StockConfirmRequest request);

    ApiResponse<StockView> release(StockReleaseRequest request);

    ApiResponse<StockView> restock(StockRestockRequest request);

    ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request);
}
