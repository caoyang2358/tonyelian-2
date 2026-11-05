package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * 运维管理模块的错误处理：只作用于本模块 Controller，避免影响其他模块尚未实现的校验约定。
 * 400 返回 code=VALIDATION_ERROR 与 data.fieldErrors；404 返回 code=NOT_FOUND。均按《接口通用约定》§4。
 */
@RestControllerAdvice(assignableTypes = {RepairOrderController.class, InspectionController.class,
        AlertEventController.class, MaintenanceOverviewController.class, cn.copper.phase2.knowledge.KnowledgeController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaintenanceExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> invalid(InvalidRequestException error) {
        return badRequest(error.field(), error.detail());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> wrongType(MethodArgumentTypeMismatchException error) {
        return badRequest(error.getName(), "参数类型不正确，应为整数。");
    }

    @ExceptionHandler(MaintenanceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(MaintenanceNotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", error.getMessage(), null));
    }

    @ExceptionHandler(MaintenanceConflictException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> conflict(MaintenanceConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(error.code(), error.getMessage(), error.data()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> unreadableBody(HttpMessageNotReadableException error) {
        return badRequest("body", "请求体格式不正确，应为 JSON。");
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String field, String detail) {
        Map<String, Object> body = Map.of("fieldErrors", List.of(Map.of("field", field, "message", detail)));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", detail, body));
    }
}
