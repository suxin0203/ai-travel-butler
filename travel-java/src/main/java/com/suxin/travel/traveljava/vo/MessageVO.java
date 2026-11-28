package com.suxin.travel.traveljava.vo;

import com.suxin.travel.traveljava.entity.Message;
import lombok.Data;

import java.time.LocalDateTime;

/** 历史消息（API 契约 §3.3）：只回 USER 与最终 ASSISTANT，工具轨迹不回传 */
@Data
public class MessageVO {
    private Long id;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public static MessageVO from(Message m) {
        MessageVO vo = new MessageVO();
        vo.setId(m.getId());
        vo.setRole(m.getRole());
        vo.setContent(m.getContent());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }
}
