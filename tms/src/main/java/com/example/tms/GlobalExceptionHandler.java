package com.example.tms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应。前端只认 {code, message} 这一种结构，
 * 不要让 Spring 默认的 {timestamp,status,error,path} 漏出去。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(String code, String message) {
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
     * 承运商回调的 token 鉴权失败（M2-4）。
     *
     * <p><b>401 与 403 分开是评审验收标准 ④ 的明确要求</b>，而且它们在 HTTP 语义里
     * 本来就是两件事：401 = 「你没出示凭据，去拿」（承运商该补头重发），
     * 403 = 「你出示了但不对，停手」（配置错了或 token 过期，重试无意义）。
     * 都返 401 的话承运商会无限重推，而运维从状态码上看不出是配置错误。</p>
     */
    @ExceptionHandler(com.example.tms.intl.callback.CallbackAuthException.class)
    public ResponseEntity<ApiError> handleCallbackAuth(
            com.example.tms.intl.callback.CallbackAuthException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ApiError(ex.code(), ex.getMessage()));
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
                .body(new ApiError("data_exists", "记录已存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_argument", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", ex.getMessage()));
    }

    /**
     * 被引用的数据不能删/不能改（国际运单域的「有运单引用就不能删承运商」这类约束）。
     * 与 DuplicateKeyException 的 data_exists 分开：前者是「冲突」，
     * 后者是「你提交的键已经存在」，前端提示话术不同。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("data_in_use", ex.getMessage()));
    }

    /** 国际物流写操作的角色闸（见 PermissionDeniedException）。403 而不是 401：身份有效，只是权限不够。 */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ApiError> handlePermissionDenied(PermissionDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("permission_denied", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "服务内部错误"));
    }
}
