package cn.copper.phase2.maintenance;

/**
 * 请求参数不符合契约时抛出，由 {@link MaintenanceExceptionHandler} 转成 400 VALIDATION_ERROR。
 * 携带 fieldErrors，便于前端按字段回显并保留用户已填内容。
 */
public class InvalidRequestException extends RuntimeException {
    private final String field;
    private final String detail;

    public InvalidRequestException(String field, String detail) {
        super(detail);
        this.field = field;
        this.detail = detail;
    }

    public String field() {
        return field;
    }

    public String detail() {
        return detail;
    }
}
