package com.suxin.travel.traveljava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.common.SseConstants;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.ChatRequestDTO;
import com.suxin.travel.traveljava.entity.Conversation;
import com.suxin.travel.traveljava.entity.Message;
import com.suxin.travel.traveljava.llm.LLMClient;
import com.suxin.travel.traveljava.llm.ToolRegistry;
import com.suxin.travel.traveljava.mapper.ConversationMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 对话服务：多轮会话 + Function Calling 工具循环 + SSE 流式推送（难点一+难点二的交汇处）。
 *
 * 关键机制：
 * 1. 会话与消息落库；上下文直接由"历史回放"构建——当前 USER 消息已落库，
 *    回放（USER + 最终 ASSISTANT 各最近 10 条）天然把它带进上下文，
 *    不能再显式追加一次，否则模型收到重复消息；
 * 2. 工具循环最多 max-tool-rounds 轮，超限后做一次"无工具"调用逼出文本回答；
 * 3. SSE 断连：onCompletion/onError/onTimeout 回调 → handle.cancel() 中断上游 OkHttp 读取（难点一）；
 * 4. 心跳：每 15s 发 ": ping" 注释行，覆盖首 token 等待与工具轮次静默期（契约 v2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConversationMapper conversationMapper;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;
    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    @Qualifier("llmExecutor")
    private final ThreadPoolTaskExecutor llmExecutor;
    /** Boot 自动配置的调度器（pool=4）：驱动对话流 15s 心跳；勿再自定义单线程 TaskScheduler Bean */
    private final org.springframework.scheduling.TaskScheduler taskScheduler;

    @Value("${llm.max-tool-rounds:5}")
    private int maxToolRounds;

    @Value("${chat.max-conversations:10}")
    private int maxConversations;

    @Value("${chat.max-rounds-per-conversation:20}")
    private int maxRoundsPerConversation;

    @Value("${llm.daily-limit:50}")
    private int dailyLimit;

    private String systemPromptTemplate;

    @PostConstruct
    void initPrompt() {
        // 当前时间注入是"相对时间换算"的必要条件（冒烟验证结论：不给时间模型不会换算"明天"）
        // v2.9（契约 §3.1）：固化回答范围（防滥用）与系统能力告知（让助手能答系统基本事实）。
        // 容量/配额数字由配置注入而非硬编码（评审 P2-6）：改配置提示词自动跟随，不会教用户旧数字
        systemPromptTemplate = "你是「AI 旅行管家」的旅行助手\"小旅\"，擅长制定行程、解答旅行问题，"
                + "也可以通过工具帮用户管理日程（创建/删除/查询）和推送偏好（开关邮件/微信推送、查询开关与绑定状态）。\n"
                + "当前时间：{now}（时区 Asia/Shanghai）。\n"
                + "规则：\n"
                + "1. 用户提到的相对时间（明天、周六、下周一）必须先换算为绝对时间再传给工具；\n"
                + "2. 工具参数 start_time 格式为 yyyy-MM-dd HH:mm:ss；\n"
                + "3. 删除日程前必须先 query_schedules 查到确切 id，不得凭空猜测；\n"
                + "4. 工具执行结果会以 JSON 回给你，请根据 ok 字段向用户确认或解释；\n"
                + "5. 用简体中文回答；可以用简单 Markdown 排版（加粗、列表、小标题），但不要输出代码块（```）；\n"
                + "6. 回答范围：以旅行/出行场景为核心的请求（行程规划、日程操作、提醒，以及旅行衍生的"
                + "文案与介绍协助）都应协助；仅与旅行及本系统功能均无关的话题（写代码、时事闲聊、"
                + "医疗/法律/投资建议、角色扮演等）礼貌说明你是旅行管家助手、只懂旅行相关业务，"
                + "并引导用户回到旅行话题；不要尝试编造回答；\n"
                + "7. 关于本系统你可以如实告知（不要编造超出以下内容的功能）：\n"
                + "   - 提醒渠道有三件：页面在线实时提示（免绑定免设置、打开页面就能收、始终发送）、"
                + "邮件推送、微信推送；后两者需在「设置」页绑定并开启偏好开关，用户也可以让你代为开关"
                + "（set_push_preference，工具结果会带回绑定状态）或查询开关与绑定状态（get_push_preference）；"
                + "渠道未绑定时即使开关开启也收不到推送，应引导用户先去「设置」页绑定；\n"
                + "   - 行程推荐在「行程」页：填目的地/天数/预算（可加备注，如人数、忌口、不想玩什么）生成，"
                + "可一键保存为行程并自动生成每日日程与提醒；\n"
                + "   - 日程的提前提醒量可选准点或提前 10 分钟~1 天，默认提前 30 分钟；\n"
                + "   - 容量与配额：最多保留 " + maxConversations + " 个会话（超出自动删除最旧会话），"
                + "单会话最多 " + maxRoundsPerConversation + " 轮对话；每日 AI 配额 " + dailyLimit
                + " 次（对话与行程推荐共用，用行程推荐也会消耗），用尽当日不可再发起，余量可在「设置」页查看。";
    }

    /** 对话入口：校验会话归属与轮数上限（404/400）→ 落库 USER 消息 → 交给线程池异步执行 */
    public SseEmitter chat(UserContext.Principal principal, ChatRequestDTO dto) {
        long uid = principal.getUid();
        Conversation conversation = resolveConversation(uid, dto.getConversationId(), dto.getMessage());
        // 轮数上限必须在 USER 消息落库之前判定（契约 §3.1）；归属校验已在 resolveConversation 完成
        long rounds = messageService.countUserRounds(conversation.getId());
        if (rounds >= maxRoundsPerConversation) {
            // 限流拦截器已先行扣减今日配额（契约 §3.0 不退还），文案里如实提示
            throw BusinessException.badRequest("本会话已达 " + maxRoundsPerConversation
                    + " 轮对话上限，请新建会话继续（本次调用配额已消耗）");
        }
        long cleaned = trimOldestConversations(uid);

        // USER 消息在调 LLM 之前落库；即使流中途失败也保留（契约 §3.0）
        messageService.save(conversation.getId(), "USER", dto.getMessage(), null);
        // 刷新会话活跃时间：会话列表按 updatedAt 倒序，没有这一步最近聊的会话排不到前面
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, conversation.getId())
                .set(Conversation::getUpdatedAt, LocalDateTime.now()));

        SseEmitter emitter = new SseEmitter(SseConstants.TIMEOUT_MS);
        LLMClient.Handle handle = new LLMClient.Handle();
        // 断连即取消上游：三个回调兜住所有关闭路径
        emitter.onCompletion(() -> handle.cancel());
        emitter.onTimeout(() -> handle.cancel());
        emitter.onError(t -> handle.cancel());

        sendEvent(emitter, handle, Map.of("type", "start", "conversationId", conversation.getId()));
        if (cleaned > 0) {
            // notice 至多一条、位于 start 之后（契约 §1.6/§3.1）
            sendEvent(emitter, handle, Map.of("type", "notice",
                    "message", "会话数已达上限，已自动清理最旧的 " + cleaned + " 个会话"));
        }
        try {
            llmExecutor.execute(() -> {
                ScheduledFuture<?> heartbeat = null;
                try {
                    // 15s 心跳：覆盖首 token 等待与工具轮次静默期，防代理掐断空闲连接
                    heartbeat = taskScheduler.scheduleAtFixedRate(
                            () -> sendComment(emitter, handle, " ping"), java.time.Duration.ofSeconds(15));
                    executeChat(uid, conversation.getId(), emitter, handle);
                } catch (Exception e) {
                    log.error("对话执行异常 uid={} conv={}", uid, conversation.getId(), e);
                    sendEvent(emitter, handle, Map.of("type", "error", "message", "对话失败，请重试"));
                    completeQuietly(emitter);
                } finally {
                    if (heartbeat != null) {
                        heartbeat.cancel(false);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // catch 父类 REE：ThreadPoolTaskExecutor 真实场景抛 TaskRejectedException（REE 子类），
            // 父类一网打尽
            // 线程池 AbortPolicy 拒绝（队列满）：此前 start 已发、USER 已落库、配额已扣，
            // 若不收口 emitter 会悬挂到 30 分钟超时（稳健性轮 P0-1）。USER 消息保留不回滚
            // ——与"失败不回退"既有取舍一致，重试时上下文仍在
            log.warn("对话线程池已满，快速失败 uid={} conv={}", uid, conversation.getId());
            sendEvent(emitter, handle, Map.of("type", "error", "message", "当前对话人数较多，请稍后重试"));
            completeQuietly(emitter);
        }
        return emitter;
    }

    /**
     * 幂等收口：complete 对已完成/已超时的 emitter 重复调用会抛 IllegalStateException，
     * 取消分支与异常分支都可能踩到容器已终结的情况——统一吞掉（稳健性轮 P0-2）
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 容器已终结本连接，忽略
        }
    }

    /** 工具循环主体：返回最终回答文本（包级可见以便单元测试直接驱动） */
    String executeChat(long uid, long conversationId, SseEmitter emitter, LLMClient.Handle handle)
            throws Exception {
        List<LLMClient.Msg> messages = buildMessages(conversationId);
        List<LLMClient.ToolDef> tools = toolRegistry.toolDefs();
        String finalAnswer = null;

        for (int round = 1; round <= maxToolRounds; round++) {
            if (handle.isCancelled()) {
                // 客户端已断开（cancel 由容器 onCompletion/onTimeout/onError 回调置位）：
                // 统一幂等收口，防止理论上的回调/循环竞态把连接滞留到超时（稳健性轮 P0-2）
                log.info("客户端已断开，中止对话循环 uid={} conv={}", uid, conversationId);
                completeQuietly(emitter);
                return null;
            }
            LLMClient.StreamResult result = llmClient.chatStream(messages, tools,
                    chunk -> sendEvent(emitter, handle, Map.of("type", "chunk", "content", chunk)), handle);
            if (handle.isCancelled()) {
                completeQuietly(emitter);
                return null;
            }

            if (result.toolCalls().isEmpty()) {
                finalAnswer = result.content();
                break;
            }

            // 工具轮：执行工具，"assistant 轨迹 + tool 结果"在同一事务里落库
            List<Message> toolMessages = new ArrayList<>();
            List<LLMClient.Msg> toolResultMsgs = new ArrayList<>();
            for (LLMClient.ToolCallReq tc : result.toolCalls()) {
                // 进度时间线事件（契约 §1.6/§3.1）：每个工具执行前发一条，前端据此推进"调用工具"步骤
                sendEvent(emitter, handle, Map.of("type", "stage", "name", "tool", "tool", tc.name()));
                String resultJson = toolRegistry.execute(uid, tc.name(), tc.argumentsJson());
                log.info("工具执行 round={} tool={} result={}", round, tc.name(), resultJson);
                Message tm = new Message();
                tm.setConversationId(conversationId);
                tm.setRole("TOOL");
                tm.setContent(resultJson);
                toolMessages.add(tm);
                toolResultMsgs.add(LLMClient.Msg.toolResult(tc.id(), resultJson));
            }
            messageService.saveToolRound(conversationId, result.content(),
                    objectMapper.writeValueAsString(result.toolCalls()), toolMessages);
            messages.add(LLMClient.Msg.assistantWithToolCalls(result.content(), result.toolCalls()));
            messages.addAll(toolResultMsgs);
        }

        // 轮次耗尽仍要求调用工具：做一次无工具调用，逼模型给出文本回答，避免死循环
        if (finalAnswer == null) {
            log.warn("工具调用轮次超限，降级为无工具回答 uid={} conv={}", uid, conversationId);
            LLMClient.StreamResult fallback = llmClient.chatStream(messages, null,
                    chunk -> sendEvent(emitter, handle, Map.of("type", "chunk", "content", chunk)), handle);
            finalAnswer = fallback.content();
        }

        if (!handle.isCancelled()) {
            messageService.save(conversationId, "ASSISTANT", finalAnswer, null);
            sendEvent(emitter, handle, Map.of("type", "done", "conversationId", conversationId));
            completeQuietly(emitter);
        }
        return finalAnswer;
    }

    /**
     * 组装本次请求的消息序列：system（含当前时间）+ 历史回放（≤10 条）。
     * 注意：当前 USER 消息已经落库，回放会把它作为最后一条带进来，
     * 这里绝不能再显式追加 userMessage——否则模型收到同一句话两遍。
     */
    private List<LLMClient.Msg> buildMessages(long conversationId) {
        List<LLMClient.Msg> messages = new ArrayList<>();
        messages.add(LLMClient.Msg.of("system", systemPromptTemplate
                .replace("{now}", LocalDateTime.now().format(DATETIME))));
        for (Message m : replayHistory(conversationId, 10)) {
            messages.add(LLMClient.Msg.of(m.getRole(), m.getContent()));
        }
        return messages;
    }

    /**
     * 历史回放：只取 USER 与最终 ASSISTANT（tool_calls_json 为空），最近 N 条。
     * 为什么不回放 TOOL/中间 ASSISTANT：OpenAI 协议要求 tool_calls 与 tool 消息严格配对，
     * 窗口截断一旦切在配对中间，上游直接 400——这是间歇性、极难排查的坑。
     */
    List<Message> replayHistory(long conversationId, int limit) {
        List<Message> list = messageService.listReplayable(conversationId, limit);
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * 会话数上限自动清理（契约 §3.1）：超出 max-conversations 的部分，从最旧开始
     * 连同消息一起物理删除。用"删后重查"的循环而不是一次算好删多少——
     * 并发新建会话时两个请求可能同时到达，循环每次都重查当前数量，最终必然收敛到上限内。
     *
     * @return 实际清理的会话数（0 = 未触发清理），用于给用户发 notice 提示
     */
    long trimOldestConversations(long uid) {
        long cleaned = 0;
        while (true) {
            long count = conversationMapper.selectCount(
                    Wrappers.<Conversation>lambdaQuery().eq(Conversation::getUserId, uid));
            if (count <= maxConversations) {
                return cleaned;
            }
            List<Conversation> oldest = conversationMapper.selectList(
                    Wrappers.<Conversation>lambdaQuery()
                            .eq(Conversation::getUserId, uid)
                            .orderByAsc(Conversation::getId)
                            .last("LIMIT 1"));
            if (oldest.isEmpty()) {
                return cleaned;
            }
            long oldestId = oldest.get(0).getId();
            messageService.deleteConversationWithMessages(oldestId);
            cleaned++;
        }
    }

    /** 会话归属校验：给定的 id 不属于当前用户 → 404；否则新建（标题取消息前 20 字） */
    private Conversation resolveConversation(long uid, Long conversationId, String firstMessage) {
        if (conversationId != null) {
            Conversation conv = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                    .eq(Conversation::getId, conversationId)
                    .eq(Conversation::getUserId, uid));
            if (conv == null) {
                throw BusinessException.notFound();
            }
            return conv;
        }
        Conversation conv = new Conversation();
        conv.setUserId(uid);
        String title = firstMessage.length() > 20 ? firstMessage.substring(0, 20) : firstMessage;
        conv.setTitle(title);
        conversationMapper.insert(conv);
        return conv;
    }

    /** 发送 data 事件；客户端断开时 send 抛异常 → 立即 cancel 上游，让循环尽快停（不白烧 token） */
    private void sendEvent(SseEmitter emitter, LLMClient.Handle handle, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("SSE 事件发送失败（客户端已断开），取消上游 LLM 调用：{}", e.getMessage());
            handle.cancel();
        }
    }

    private void sendComment(SseEmitter emitter, LLMClient.Handle handle, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception e) {
            // 心跳写失败 = 客户端已断开：主动取消上游（contract §1.6 断连即取消；
            // 此前只吞异常，取消要等下一个 data 事件才触发，工具轮静默期最长滞后 15s+）
            log.debug("SSE 心跳发送失败（客户端已断开），取消上游调用");
            handle.cancel();
        }
    }
}
