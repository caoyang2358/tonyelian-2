package cn.copper.phase2.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验未通过。"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "对象不存在。"),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "编码已被占用。"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "记录已被他人修改，请重新读取后再提交。"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "相同请求编号提交了不同内容。"),
    RELATED_DATA_EXISTS(HttpStatus.CONFLICT, "存在关联数据，暂时不能执行该操作。"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "对象当前状态不允许该操作，请刷新后核对。"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "库存实物不足，无法完成本次过账。"),
    SUPPLY_CONFLICT(HttpStatus.CONFLICT, "供料条件不满足，请先完成前置入库或调整安排。"),
    PRECISION_EXCEEDED(HttpStatus.BAD_REQUEST, "数值小数位数超出约定精度。"),
    HANDOVER_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "交接误差超过 5%，需要填写放行原因。"),
    MISSING_REQUIRED_DATA(HttpStatus.UNPROCESSABLE_ENTITY, "缺少必要依据，暂时不能执行该操作。"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "该接口不支持此请求方法。"),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "数据库连接或读取尚未就绪，请检查开发库初始化状态。"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "请求暂时无法完成，请稍后重试。");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
