package cn.copper.phase2.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message == null || message.isBlank() ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static BusinessException invalid(String field, String message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return invalid(fields);
    }

    public static BusinessException invalid(Map<String, Object> fields) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                Map.of("fields", Collections.unmodifiableMap(new LinkedHashMap<>(fields))));
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public static BusinessException duplicateCode(String code) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", "该编码已存在");
        return new BusinessException(ErrorCode.DUPLICATE_CODE, "编码 " + code + " 已被占用。", Map.of("fields", fields));
    }

    public static BusinessException versionConflict(long expected, long current) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expectedVersion", expected);
        details.put("currentVersion", current);
        return new BusinessException(ErrorCode.VERSION_CONFLICT, null, details);
    }

    public static BusinessException idempotencyConflict() {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    public static BusinessException relatedData(String message, Object blockers) {
        return new BusinessException(ErrorCode.RELATED_DATA_EXISTS, message, Map.of("blockers", blockers));
    }
}
