package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonView;

/** 统一 API 包；字段标 {@link OrderViews.Public} 以便订单接口 @JsonView 裁剪时外壳仍序列化。 */
public record ApiResponse<T>(
        @JsonView(OrderViews.Public.class) int code,
        @JsonView(OrderViews.Public.class) String message,
        @JsonView(OrderViews.Public.class) T data
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
