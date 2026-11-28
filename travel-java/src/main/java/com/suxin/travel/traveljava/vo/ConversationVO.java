package com.suxin.travel.traveljava.vo;

import com.suxin.travel.traveljava.entity.Conversation;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话列表项（API 契约 §3.2） */
@Data
public class ConversationVO {
    private Long id;
    private String title;
    private LocalDateTime updatedAt;

    public static ConversationVO from(Conversation c) {
        ConversationVO vo = new ConversationVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setUpdatedAt(c.getUpdatedAt());
        return vo;
    }
}
