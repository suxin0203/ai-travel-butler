package com.suxin.travel.traveljava.common;

import lombok.Data;

/**
 * 统一响应结构：{code, message, data}，与 docs/API契约.md §1.2 一一对应。
 * 相比旧版的 success/error/rawResponse 多字段结构：
 * 失败统一走 code + message，模型原始输出只进日志不回传。
 */
@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMessage("成功");
        r.setData(data);
        return r;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
