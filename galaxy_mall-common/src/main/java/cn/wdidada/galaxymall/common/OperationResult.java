package cn.wdidada.galaxymall.common;

public record OperationResult<T>(boolean success, String message, T data) {

    public static <T> OperationResult<T> success(String message, T data) {
        return new OperationResult<>(true, message, data);
    }

    public static <T> OperationResult<T> failure(String message) {
        return new OperationResult<>(false, message, null);
    }
}
