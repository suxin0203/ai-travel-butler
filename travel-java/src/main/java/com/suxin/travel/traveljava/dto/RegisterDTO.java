package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "用户名须为 3~20 位字母/数字/下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为 6~32 位")
    private String password;

    // email 格式校验交给 service 归一后做（Emails.isValid，契约 §2.5 归一规则），注解会先于 trim 挡输入
    private String email;
}
