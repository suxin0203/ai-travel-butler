package com.suxin.travel.traveljava.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** GET /api/auth/me 的响应结构，对应 API 契约 §2.4/§2.5b */
@Data
@AllArgsConstructor
public class UserVO {
    private Long userId;
    private String username;
    private String email;
    private Boolean wechatBound;
    /** 推送渠道偏好（邮件/微信两个离线渠道的开关，SSE 不受影响） */
    private Notify notify;
    private Quota quota;

    @Data
    @AllArgsConstructor
    public static class Quota {
        /** 今日已用 AI 调用次数（Redis 日计数） */
        private Integer used;
        /** 每日上限（llm.daily-limit 配置） */
        private Integer limit;
    }

    @Data
    @AllArgsConstructor
    public static class Notify {
        /** 邮件推送开关 */
        private Boolean mail;
        /** 微信推送开关 */
        private Boolean wechat;
    }
}
