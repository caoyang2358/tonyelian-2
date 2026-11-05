package cn.copper.phase2.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> business(BusinessException error) {
        ErrorCode code = error.errorCode();
        if (code.status().is5xxServerError()) {
            LOG.warn("Business failure; code={}; type={}", code.name(), error.getClass().getSimpleName());
        }
        return ResponseEntity.status(code.status())
                .body(ApiResponse.<Object>error(code.name(), error.getMessage(), error.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> invalidBody(MethodArgumentNotValidException error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fieldError : error.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage() == null
                    ? "取值不符合约定" : fieldError.getDefaultMessage());
        }
        return validationFailure(fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> invalidParameter(ConstraintViolationException error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : error.getConstraintViolations()) {
            fields.putIfAbsent(String.valueOf(violation.getPropertyPath()), violation.getMessage());
        }
        return validationFailure(fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> invalidType(MethodArgumentTypeMismatchException error) {
        return validationFailure(Map.of(error.getName(), "取值类型不符合约定"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> missingParameter(MissingServletRequestParameterException error) {
        return validationFailure(Map.of(error.getParameterName(), "该参数为必填项"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> unreadableBody(HttpMessageNotReadableException error) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.<Object>error(ErrorCode.VALIDATION_ERROR.name(),
                        "请求体格式不符合约定，请检查字段类型与日期格式。", Map.of()));
    }

    /**
     * multipart 在进入控制器之前就会被容器解析，超限时抛的是这里的异常，
     * 不处理会落到通用 500，页面只能看到"请求暂时无法完成"而不知道是文件太大。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> uploadTooLarge(MaxUploadSizeExceededException error) {
        return validationFailure(Map.of("file", "上传文件超过服务允许的大小，请压缩或拆分后重试。"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> integrity(DataIntegrityViolationException error) {
        // Only the constraint kind is logged; SQL text, parameters and credentials stay out of the log.
        boolean duplicate = isDuplicateKey(error);
        LOG.warn("Database constraint rejected a request; duplicateKey={}", duplicate);
        if (duplicate) {
            return ResponseEntity.status(ErrorCode.DUPLICATE_CODE.status())
                    .body(ApiResponse.<Object>error(ErrorCode.DUPLICATE_CODE.name(), "编码已被占用。",
                            Map.of("fields", Map.of("code", "该编码已存在"))));
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.<Object>error(ErrorCode.VALIDATION_ERROR.name(),
                        "提交内容不符合数据约束，请检查必填项与取值范围。", Map.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Object>> databaseUnavailable(DataAccessException error) {
        // Do not log SQL exception messages, connection strings, credentials or stack traces.
        LOG.warn("Database request unavailable; type={}", error.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.DATABASE_UNAVAILABLE.status())
                .body(ApiResponse.<Object>error(ErrorCode.DATABASE_UNAVAILABLE.name(),
                        ErrorCode.DATABASE_UNAVAILABLE.defaultMessage(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> notFound(NoResourceFoundException error) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.<Object>error(ErrorCode.NOT_FOUND.name(), "接口不存在。", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                .body(ApiResponse.<Object>error(ErrorCode.METHOD_NOT_ALLOWED.name(),
                        ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> unexpected(Exception error) {
        LOG.error("Request could not be completed; type={}", error.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.<Object>error(ErrorCode.INTERNAL_ERROR.name(),
                        ErrorCode.INTERNAL_ERROR.defaultMessage(), null));
    }

    private static ResponseEntity<ApiResponse<Object>> validationFailure(Map<String, Object> fields) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.<Object>error(ErrorCode.VALIDATION_ERROR.name(),
                        ErrorCode.VALIDATION_ERROR.defaultMessage(), Map.of("fields", fields)));
    }

    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
