package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.UserView;
public interface UserClient {

    ApiResponse<UserView> getUser(String userId);
}
