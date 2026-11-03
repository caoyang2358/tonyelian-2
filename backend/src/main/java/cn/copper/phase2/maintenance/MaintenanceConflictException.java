package cn.copper.phase2.maintenance;

import java.util.Map;

/**
 * 业务冲突（409）：请求标识重复但内容不同、版本过期等。
 * 由 {@link MaintenanceExceptionHandler} 转成对应 code 的 409 响应，data 可携带 currentVersion 等信息。
 */
public class MaintenanceConflictException extends RuntimeException {
    private final String code;
    private final transient Map<String, Object> data;

    public MaintenanceConflictException(String code, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> data() {
        return data;
    }
}
