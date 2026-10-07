package com.example.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误响应。前端只认 {code, message} 这一种结构，
 * 不要让 Spring 默认的 {timestamp,status,error,path} 漏出去。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(String code, String message) {
        /** 业务代码里快速构造错误体的工厂方法。 */
        public static ApiError of(String code, String message) {
            return new ApiError(code, message);
        }
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("forbidden", ex.getMessage()));
    }

    /** 花名被占用、已有在途申请之类的可预期冲突，前端据此给"换个名字试试"这类提示。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("参数不合法");
        return ResponseEntity.badRequest().body(new ApiError("invalid_argument", message));
    }

    /**
     * 缺 X-User-Id 之类的内部头，说明请求绕过了网关直达业务服务。
     * 这种情况必须显式失败，绝不能退化成匿名放行。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("missing_identity_header", "缺少网关身份头，请通过网关访问: " + ex.getHeaderName()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("account_exists", "该账号已存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_argument", ex.getMessage()));
    }

    /**
     * 路径或方法没匹配上。
     *
     * <p><b>必须显式接住</b>：下面那个 {@code Exception.class} 兜底一旦抢先处理，
     * 404/405 就会退化成 500「服务内部错误」。那不只是难看——它把「压根没有这个接口」
     * 和「服务坏了」混成同一种响应，调用方既没法区分，也没法据此判断该不该重试，
     * 日志里还会多出一条误导性的 unhandled exception。</p>
     *
     * <p>最典型的就是拿 PUT/DELETE 去调只读接口：本该一个干脆的 405 说清楚
     * 「这里没有写入口」。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", "接口不存在: " + ex.getResourcePath()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("method_not_allowed",
                        "该接口不支持 " + ex.getMethod() + " 方法"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("unsupported_media_type", "请求体必须是 application/json"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "服务内部错误"));
    }
}
