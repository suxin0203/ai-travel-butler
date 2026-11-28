package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 推送渠道偏好请求体（契约 §2.5b）：双布尔全量提交，最后写入胜出。
 * 不校验绑定状态——未绑定时渠道按「未绑定」规则不可用（§4.6），灰显留给前端。
 */
@Data
public class NotifyChannelsDTO {
    @NotNull(message = "mail 不能为空")
    private Boolean mail;
    @NotNull(message = "wechat 不能为空")
    private Boolean wechat;
}
