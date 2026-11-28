package com.suxin.travel.traveljava.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.entity.Conversation;
import com.suxin.travel.traveljava.entity.Message;
import com.suxin.travel.traveljava.mapper.ConversationMapper;
import com.suxin.travel.traveljava.mapper.MessageMapper;
import com.suxin.travel.traveljava.service.MessageService;
import com.suxin.travel.traveljava.vo.ConversationVO;
import com.suxin.travel.traveljava.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话与历史消息（API 契约 §3.2/§3.3/§3.2a），全部带归属校验 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MessageService messageService;

    @GetMapping
    public Result<List<ConversationVO>> list(@RequestParam(defaultValue = "50") int limit) {
        long uid = UserContext.requireUid();
        List<Conversation> list = conversationMapper.selectList(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, uid)
                .orderByDesc(Conversation::getUpdatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        return Result.ok(list.stream().map(ConversationVO::from).toList());
    }

    @GetMapping("/{id}/messages")
    public Result<List<MessageVO>> messages(@PathVariable long id) {
        long uid = UserContext.requireUid();
        // 归属校验：非本人一律 404，不暴露存在性
        Conversation conv = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getId, id).eq(Conversation::getUserId, uid));
        if (conv == null) {
            throw BusinessException.notFound();
        }
        // 只回可回放消息（USER + 最终 ASSISTANT），工具轨迹不回传前端
        List<Message> list = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, id)
                .and(w -> w.eq(Message::getRole, "USER")
                        .or(w2 -> w2.eq(Message::getRole, "ASSISTANT").isNull(Message::getToolCallsJson)))
                .orderByAsc(Message::getId));
        return Result.ok(list.stream().map(MessageVO::from).toList());
    }

    /** 删除会话（契约 §3.2a）：物理删除会话及全部消息；非本人/不存在/重复删除一律 404（防探测） */
    @DeleteMapping("/{id}")
    public Result<java.util.Map<String, Boolean>> delete(@PathVariable long id) {
        long uid = UserContext.requireUid();
        Conversation conv = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getId, id).eq(Conversation::getUserId, uid));
        if (conv == null) {
            throw BusinessException.notFound();
        }
        // 与 §3.1 上限自动清理同一实现：事务内先删消息再删会话
        messageService.deleteConversationWithMessages(id);
        return Result.ok(java.util.Map.of("deleted", true));
    }
}
