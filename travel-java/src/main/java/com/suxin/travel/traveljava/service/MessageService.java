package com.suxin.travel.traveljava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.suxin.travel.traveljava.entity.Conversation;
import com.suxin.travel.traveljava.entity.Message;
import com.suxin.travel.traveljava.mapper.ConversationMapper;
import com.suxin.travel.traveljava.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 消息落库服务。工具轮的"assistant 轨迹 + 全部 tool 结果"放在一个事务里写：
 * 进程崩溃不会留下"有 tool_calls、无 tool 结果"的半截轨迹。
 * 事务内不含任何 LLM 远程调用——远程调用必须在事务外，避免长事务占住连接。
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    public Message save(long conversationId, String role, String content, String toolCallsJson) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setToolCallsJson(toolCallsJson);
        messageMapper.insert(m);
        return m;
    }

    /** 一轮工具调用的完整落库：assistant（带轨迹）+ N 条 tool 结果，同一事务 */
    @Transactional
    public void saveToolRound(long conversationId, String assistantContent, String toolCallsJson,
                              List<Message> toolMessages) {
        save(conversationId, "ASSISTANT", assistantContent, toolCallsJson);
        for (Message m : toolMessages) {
            messageMapper.insert(m);
        }
    }

    /** 历史回放查询：只取 USER 与最终 ASSISTANT（无 tool_calls），按 id 倒序取最近 N 条 */
    public List<Message> listReplayable(long conversationId, int limit) {
        return messageMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, conversationId)
                        .and(w -> w.eq(Message::getRole, "USER")
                                .or(w2 -> w2.eq(Message::getRole, "ASSISTANT")
                                        .isNull(Message::getToolCallsJson)))
                        .orderByDesc(Message::getId)
                        .last("LIMIT " + limit));
    }

    /** 会话内 USER 消息数 = 对话轮数，供轮数上限判定（归属校验在 ChatService 已完成） */
    public long countUserRounds(long conversationId) {
        return messageMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, conversationId)
                        .eq(Message::getRole, "USER"));
    }

    /**
     * 物理删除会话及其全部消息（会话上限自动清理用）。
     * 修复记录：曾漏删 conversation 本身导致 trim 循环 count 永不下降、线程无限自旋——
     * 方法名里有 "WithMessages" 就要两个都删，同类坑：改逻辑先跑一遍真实路径验证。
     */
    @Transactional
    public void deleteConversationWithMessages(long conversationId) {
        messageMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }
}
