package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 对话请求（API 契约 §3.1）：conversationId 缺省 = 新建会话 */
@Data
public class ChatRequestDTO {
    private Long conversationId;

    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息不能超过 2000 字符")
    private String message;
}
