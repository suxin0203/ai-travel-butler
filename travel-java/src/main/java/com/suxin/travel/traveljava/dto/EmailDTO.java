package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送邮箱验证码请求体（契约 §2.5a）。格式校验交给 service 在归一后做
 * （Emails.isValid）——@Email 注解会先于 trim 拒绝带空格的输入，与归一规则冲突。
 */
@Data
public class EmailDTO {
    @NotBlank(message = "邮箱不能为空")
    private String email;
}
