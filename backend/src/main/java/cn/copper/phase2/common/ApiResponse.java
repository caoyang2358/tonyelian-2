package cn.copper.phase2.common;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ApiResponse<T>(String code, String message, T data, OffsetDateTime timestamp) {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, OffsetDateTime.now(ZONE));
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, OffsetDateTime.now(ZONE));
    }
}
