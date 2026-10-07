package com.example.oms;

import com.example.oms.linkage.LinkageException;
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

    /** 被引用的数据不能删/不能改（已组批子单不能改明细、有子单的母单不能删等）。 */
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

    /**
     * 发货联动失败（建 TMS 运单/WMS 出库记录的跨服务调用出错）。
     * OMS 的状态变更已随事务回滚——给用户明确文案，修好下游后重试即可，
     * 「先查后建」保证不会重复建档。502 = 下游服务故障，区别于自身参数错误。
     */
    @ExceptionHandler(LinkageException.class)
    public ResponseEntity<ApiError> handleLinkage(LinkageException ex) {
        log.warn("linkage failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("linkage_failed", "发货联动失败：" + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "服务内部错误"));
    }
}
