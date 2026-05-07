package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public record ApiResponse<T>(boolean success, String message, T data) implements Serializable {

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
