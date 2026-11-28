package com.suxin.travel.traveljava.common;

import lombok.Getter;

/**
 * 业务异常：携带 API 契约中的错误码，由 GlobalExceptionHandler 统一转成 Result。
 * 抛出点只管说"这是什么错"，不关心 HTTP 细节。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    /** 429 限流：AI 配额用尽 / 发送邮箱验证码频控（契约 §1.3） */
    public static BusinessException tooManyRequests(String message) {
        return new BusinessException(429, message);
    }

    public static BusinessException notFound() {
        return new BusinessException(404, "资源不存在");
    }

    public static BusinessException internal(String message) {
        return new BusinessException(500, message);
    }
}
