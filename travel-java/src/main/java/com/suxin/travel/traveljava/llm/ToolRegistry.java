package com.suxin.travel.traveljava.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.dto.ScheduleCreateDTO;
import com.suxin.travel.traveljava.service.AuthService;
import com.suxin.travel.traveljava.service.ScheduleService;
import com.suxin.travel.traveljava.vo.ScheduleVO;
import com.suxin.travel.traveljava.vo.UserVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具注册器：Function Calling 的"工具表"。
 * 模型只看到工具的 JSON Schema；发起调用后由这里把 arguments JSON 解析成
 * 真实的业务操作（调 ScheduleService），再把执行结果序列化回给模型。
 *
 * 设计要点：工具执行结果永远是合法 JSON 字符串（ok/error 结构），
 * 即使业务报错也以 {"ok":false,"error":...} 回给模型让它自行纠偏重试，
 * 而不是抛异常打断对话循环——这是工具循环健壮性的关键。
 * 所有结果一律用 ObjectMapper 序列化：手拼 JSON 会漏掉标题里的控制字符，
 * 严格解析的模型端会直接读不了结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScheduleService scheduleService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    /** 工具名 → 执行器（入参：uid + arguments JSON 节点；出参：回给模型的 JSON 字符串） */
    private final Map<String, Function<ToolInvocation, String>> handlers = new LinkedHashMap<>();

    /** 注册给模型的工具定义（schema 见 handlers 注册处） */
    public List<LLMClient.ToolDef> toolDefs() {
        return List.of(
                new LLMClient.ToolDef("create_schedule", "为当前用户创建一条日程提醒",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "title", Map.of("type", "string", "description", "日程标题"),
                                        "start_time", Map.of("type", "string",
                                                "description", "开始时间，格式 yyyy-MM-dd HH:mm:ss"),
                                        "remind_minutes_before", Map.of("type", "integer",
                                                "description", "提前提醒的分钟数，默认30"),
                                        "note", Map.of("type", "string", "description", "备注，可选")),
                                "required", List.of("title", "start_time"))),
                new LLMClient.ToolDef("delete_schedule", "删除当前用户的一条日程（按 id）",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "schedule_id", Map.of("type", "integer",
                                                "description", "要删除的日程 id，先调用 query_schedules 查到 id 再删")),
                                "required", List.of("schedule_id"))),
                new LLMClient.ToolDef("query_schedules", "查询当前用户的日程列表",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "date", Map.of("type", "string",
                                                "description", "可选，查询某一天的日程，格式 yyyy-MM-dd"),
                                        "keyword", Map.of("type", "string", "description", "可选，按标题关键词模糊匹配")))),
                new LLMClient.ToolDef("set_push_preference", "设置当前用户的推送偏好开关（v2.9）。"
                        + "用户说\"帮我关掉邮件推送/打开微信推送\"时调用；一次只设置一个渠道，另一渠道保持不变",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "channel", Map.of("type", "string", "enum", List.of("mail", "wechat"),
                                                "description", "要设置的渠道：mail=邮件推送，wechat=微信推送"),
                                        "enabled", Map.of("type", "boolean",
                                                "description", "true=开启该渠道，false=关闭该渠道")),
                                "required", List.of("channel", "enabled"))),
                new LLMClient.ToolDef("get_push_preference", "查询当前用户的推送偏好与绑定状态"
                        + "（邮件/微信是否开启、是否已绑定，用于回答\"为什么收不到推送\"类问题）",
                        Map.of("type", "object", "properties", Map.of())));
    }

    @PostConstruct
    void register() {
        handlers.put("create_schedule", this::createSchedule);
        handlers.put("delete_schedule", this::deleteSchedule);
        handlers.put("query_schedules", this::querySchedules);
        handlers.put("set_push_preference", this::setPushPreference);
        handlers.put("get_push_preference", this::getPushPreference);
    }

    /**
     * 执行工具调用。任何异常都转成 {"ok":false,"error":...} 回给模型，
     * 不打断对话循环（模型可以据此向用户解释或修正参数重试）。
     */
    public String execute(long uid, String name, String argumentsJson) {
        Function<ToolInvocation, String> handler = handlers.get(name);
        if (handler == null) {
            return json(Map.of("ok", false, "error", "未知工具：" + name));
        }
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(argumentsJson);
            return handler.apply(new ToolInvocation(uid, args));
        } catch (Exception e) {
            log.warn("工具执行失败 name={} uid={} args={}", name, uid, argumentsJson, e);
            return json(Map.of("ok", false, "error",
                    e.getMessage() == null ? "执行失败" : e.getMessage()));
        }
    }

    // ---------- 三个工具的实现 ----------

    private String createSchedule(ToolInvocation inv) {
        ScheduleCreateDTO dto = new ScheduleCreateDTO();
        dto.setTitle(inv.args().path("title").asText(""));
        if (inv.args().hasNonNull("remind_minutes_before")) {
            dto.setRemindMinutesBefore(inv.args().path("remind_minutes_before").asInt());
        }
        if (inv.args().hasNonNull("note")) {
            dto.setNote(inv.args().path("note").asText());
        }
        try {
            dto.setStartTime(LocalDateTime.parse(inv.args().path("start_time").asText(""), DATETIME));
        } catch (DateTimeParseException e) {
            return json(Map.of("ok", false, "error", "start_time 格式必须是 yyyy-MM-dd HH:mm:ss"));
        }
        // 字段校验（长度/范围/时间合法性）统一在 ScheduleService 里做，
        // 工具路径与页面路径共用一套规则；业务异常在 execute() 统一转 ok:false
        ScheduleVO vo = scheduleService.create(inv.uid(), dto, "AI");
        return json(Map.of("ok", true,
                "message", "已创建日程「" + vo.getTitle() + "」，开始时间 " + vo.getStartTime()
                        + "，提前 " + vo.getRemindMinutesBefore() + " 分钟提醒",
                "id", vo.getId()));
    }

    private String deleteSchedule(ToolInvocation inv) {
        JsonNode idNode = inv.args().path("schedule_id");
        if (!idNode.canConvertToLong()) {
            return json(Map.of("ok", false, "error", "需要提供数字类型的 schedule_id"));
        }
        scheduleService.delete(inv.uid(), idNode.asLong());
        return json(Map.of("ok", true, "message", "日程已删除"));
    }

    private String querySchedules(ToolInvocation inv) {
        LocalDate date = null;
        String dateStr = inv.args().path("date").asText(null);
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                date = LocalDate.parse(dateStr);
            } catch (DateTimeParseException e) {
                return json(Map.of("ok", false, "error", "date 格式必须是 yyyy-MM-dd"));
            }
        }
        String keyword = inv.args().path("keyword").asText(null);
        List<ScheduleVO> items = scheduleService.queryForTool(inv.uid(), date, keyword);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ScheduleVO it : items) {
            summaries.add(Map.of("id", it.getId(), "title", it.getTitle(),
                    "startTime", it.getStartTime().toString(), "status", it.getStatus()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("count", items.size());
        result.put("items", summaries);
        return json(result);
    }

    // ---------- 推送偏好两个工具的实现（v2.9） ----------

    /**
     * 语义 = 设置页单开关（契约 §2.5b/§3.1）：后端先读当前偏好合并写入，另一渠道不变；
     * 与 PUT /api/auth/notify-channels 走同一个 AuthService.updateNotifyChannels，
     * 工具路径与页面路径保证同一套规则。不校验绑定状态（§2.5b：未绑定时开启仍按「未绑定」不可用）。
     */
    private String setPushPreference(ToolInvocation inv) {
        String channel = inv.args().path("channel").asText("");
        if (!"mail".equals(channel) && !"wechat".equals(channel)) {
            return json(Map.of("ok", false, "error", "channel 只能是 mail（邮件）或 wechat（微信）"));
        }
        JsonNode enabledNode = inv.args().path("enabled");
        if (!enabledNode.isBoolean()) {
            return json(Map.of("ok", false, "error", "enabled 必须是布尔值 true 或 false"));
        }
        boolean enabled = enabledNode.asBoolean();
        // 当前偏好可能为 null（新用户未设置过，§2.5b 默认双开）：null 视为 true 再合并
        UserVO me = authService.me(inv.uid());
        UserVO.Notify cur = me.getNotify();
        boolean curMail = cur == null || cur.getMail() == null || cur.getMail();
        boolean curWechat = cur == null || cur.getWechat() == null || cur.getWechat();
        boolean mail = "mail".equals(channel) ? enabled : curMail;
        boolean wechat = "wechat".equals(channel) ? enabled : curWechat;
        authService.updateNotifyChannels(inv.uid(), mail, wechat);
        // 绑定状态随结果回传（契约评审 P1-1）：开启未绑定渠道 = 开关已开但收不到的静默失败，
        // 对话场景没有设置页灰显文案承担告知，把绑定事实交给模型当场转达
        boolean mailBound = me.getEmail() != null && !me.getEmail().isBlank();
        boolean wechatBound = Boolean.TRUE.equals(me.getWechatBound());
        String channelName = "mail".equals(channel) ? "邮件推送" : "微信推送";
        String boundHint = "";
        if (enabled && ("mail".equals(channel) ? !mailBound : !wechatBound)) {
            boundHint = "。注意：该渠道还未绑定，开了开关也收不到推送，请引导用户先到「设置」页绑定";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("message", channelName + (enabled ? "已开启" : "已关闭") + boundHint);
        result.put("mail", mail);
        result.put("wechat", wechat);
        result.put("mailBound", mailBound);
        result.put("wechatBound", wechatBound);
        return json(result);
    }

    private String getPushPreference(ToolInvocation inv) {
        UserVO vo = authService.me(inv.uid());
        UserVO.Notify notify = vo.getNotify();
        // null 视为默认双开（§2.5b），与设置页展示口径一致
        boolean mail = notify == null || notify.getMail() == null || notify.getMail();
        boolean wechat = notify == null || notify.getWechat() == null || notify.getWechat();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("mail", mail);
        result.put("wechat", wechat);
        result.put("mailBound", vo.getEmail() != null && !vo.getEmail().isBlank());
        result.put("wechatBound", Boolean.TRUE.equals(vo.getWechatBound()));
        return json(result);
    }

    /** 统一的结果序列化入口：杜绝手拼 JSON 的转义漏洞 */
    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
        }
    }

    record ToolInvocation(long uid, JsonNode args) {
    }
}
