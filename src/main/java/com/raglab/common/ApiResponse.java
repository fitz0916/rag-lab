package com.raglab.common;

/** 统一响应包装，前端只看 ok 字段决定走成功还是错误分支 */
public record ApiResponse<T>(boolean ok, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
