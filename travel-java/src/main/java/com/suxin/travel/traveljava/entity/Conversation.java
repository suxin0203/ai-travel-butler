package com.suxin.travel.traveljava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话：一次多轮对话的容器 */
@Data
@TableName("conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 用首条消息截断生成，便于会话列表辨认 */
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
