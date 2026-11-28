package com.suxin.travel.traveljava.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有未捕获异常都转成统一 Result 结构。
 * 与旧版的关键差异：HTTP 状态码与 body.code 一致（契约 §1.3/§1.4），
 * 前端 axios 只需按 HTTP 状态码分流，不用再解析两次。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败：拼接各字段的可读错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(java.util.stream.Collectors.joining("；"));
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /** 请求体不是合法 JSON / 字段类型错配：归为客户端 400，不进 500 兜底 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    /** GET 查询参数类型错配（如 ?from=abc、?limit=x）：归为客户端 400 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("查询参数类型错误：{}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "参数格式不正确");
    }

    /** 业务异常：按异常携带的契约错误码返回（400/404/...） */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return build(HttpStatus.valueOf(e.getCode()), e.getMessage());
    }

    /** 兜底：不把堆栈细节暴露给前端 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试");
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.fail(status.value(), message));
    }
}
