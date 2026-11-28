package com.suxin.travel.traveljava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息。role: USER / ASSISTANT / TOOL
 * 关键约定：历史回放只取 USER 与最终 ASSISTANT（tool_calls_json 为空）两类，
 * TOOL 与中间 ASSISTANT 轨迹只落库展示，避免截断撕裂 tool_calls/tool 配对导致上游 400。
 */
@Data
@TableName("message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    /** ASSISTANT 消息的工具调用轨迹 JSON，仅展示用 */
    private String toolCallsJson;
    private LocalDateTime createdAt;
}
