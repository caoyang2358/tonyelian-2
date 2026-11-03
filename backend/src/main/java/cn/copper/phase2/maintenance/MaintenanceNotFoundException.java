package cn.copper.phase2.maintenance;

/**
 * 请求的目标对象不存在时抛出，由 {@link MaintenanceExceptionHandler} 转成 404 NOT_FOUND。
 */
public class MaintenanceNotFoundException extends RuntimeException {
    public MaintenanceNotFoundException(String message) {
        super(message);
    }
}
