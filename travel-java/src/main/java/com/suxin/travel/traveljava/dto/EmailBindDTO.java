package com.suxin.travel.traveljava.dto;

import lombok.Data;

/**
 * 修改邮箱请求体（契约 §2.5）。两个字段都可缺省：
 * email 缺省 = 不修改邮箱；email 变更时 emailCode 必填（必填性在 service 按
 * 「归一后与当前绑定是否相同」判定，DTO 层无法表达，故不加注解校验）。
 */
@Data
public class EmailBindDTO {
    private String email;
    private String emailCode;
}
