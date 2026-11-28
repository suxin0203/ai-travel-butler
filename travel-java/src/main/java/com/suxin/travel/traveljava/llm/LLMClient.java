package com.suxin.travel.traveljava.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 客户端（OpenAI 兼容协议，硅基流动）。
 * 难点二的基础设施：支持 tools（Function Calling）与流式输出，流式过程中实时组装
 * 增量 tool_calls（OpenAI 流式协议里 tool_calls 是按 index 分片下发的，必须缓存拼装）。
 *
 * 为什么流式增量里也要处理 tool_calls：工具轮次的回答直接决定循环走向，只有拿到
 * 完整的 arguments JSON 才能执行工具；最后一轮（finish_reason=stop）的内容才是
 * 边生成边推给前端的"打字机"输出。
 *
 * 本项目所用模型是推理模型：流式增量里会先输出 reasoning_content（思考过程），
 * 业务上不把思考过程推给用户，因此只透传 delta.content。
 */
@Slf4j
@Component
public class LLMClient {

    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Value("${llm.temperature:0.7}")
    private double temperature;

    private final OkHttpClient client;

    public LLMClient(@Value("${llm.model}") String model,
                     @Value("${llm.api-key}") String apiKey,
                     @Value("${llm.base-url}") String baseUrl,
                     ObjectMapper objectMapper) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                // 整体上限 5 分钟：防止上游"细水长流"式卡住占死线程池
                .callTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    // ---------- 消息/工具的数据结构（序列化为 OpenAI 协议格式） ----------

    /** 一条对话消息；toolCallId 与 toolCallsJson 用于 tool / assistant 工具消息 */
    public record Msg(String role, String content, String toolCallId, List<ToolCallReq> toolCalls) {
        public static Msg of(String role, String content) {
            return new Msg(role, content, null, null);
        }

        public static Msg assistantWithToolCalls(String content, List<ToolCallReq> toolCalls) {
            return new Msg("assistant", content == null ? "" : content, null, toolCalls);
        }

        public static Msg toolResult(String toolCallId, String content) {
            return new Msg("tool", content, toolCallId, null);
        }
    }

    /** 模型发起的工具调用请求（从响应中解析出来） */
    public record ToolCallReq(String id, String name, String argumentsJson) {
    }

    /** 注册给模型的工具定义（parameters 直接用 Map 表达 JSON Schema） */
    public record ToolDef(String name, String description, Map<String, Object> parameters) {
        /** 转成 OpenAI 协议的 tools 数组元素 */
        public Map<String, Object> toProtocol() {
            return Map.of("type", "function",
                    "function", Map.of("name", name, "description", description, "parameters", parameters));
        }
    }

    /** 一次流式调用的最终结果 */
    public record StreamResult(String content, String finishReason, List<ToolCallReq> toolCalls) {
    }

    /** 取消句柄：SSE 断连时由回调线程调用 cancel()，中断阻塞中的上游读取（静态嵌套类，无需外部实例） */
    public static class Handle {
        private volatile Call call;
        private volatile boolean cancelled;

        void bind(Call call) {
            this.call = call;
        }

        public void cancel() {
            cancelled = true;
            if (call != null) {
                call.cancel();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    // ---------- 同步调用（无流式，行程推荐用） ----------

    public String chatSync(List<Msg> messages, List<ToolDef> tools) {
        Map<String, Object> body = buildBody(messages, tools, false);
        Request request = newRequest(body);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM 调用异常 " + response.code() + "：" + safeBody(response));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            log.error("LLM 同步调用失败 model={}", model, e);
            throw new RuntimeException("LLM 调用失败", e);
        }
    }

    // ---------- 流式调用（对话用） ----------

    /**
     * 流式调用：每收到一段增量文本就回调 onDelta；结束时返回完整结果（含流式组装的 tool_calls）。
     * 阻塞在调用方线程上执行；SSE 断连时 handle.cancel() 会让阻塞读取立即抛出
     * "canceled" IOException，从而停止白烧 token。
     */
    public StreamResult chatStream(List<Msg> messages, List<ToolDef> tools,
                                   Consumer<String> onDelta, Handle handle) throws IOException {
        Map<String, Object> body = buildBody(messages, tools, true);
        Call call = client.newCall(newRequest(body));
        handle.bind(call);

        StringBuilder content = new StringBuilder();
        // 流式 tool_calls 按 index 分片：缓存每个分片的 id/name/arguments 增量
        Map<Integer, StringBuilder> argBuf = new LinkedHashMap<>();
        Map<Integer, String> idBuf = new LinkedHashMap<>();
        Map<Integer, String> nameBuf = new LinkedHashMap<>();
        String[] finishReason = {null};

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM 流式调用异常 " + response.code() + "：" + safeBody(response));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (handle.isCancelled()) {
                        break;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choice = chunk.path("choices").path(0);
                    if (choice.has("finish_reason") && !choice.path("finish_reason").isNull()) {
                        finishReason[0] = choice.path("finish_reason").asText();
                    }
                    JsonNode delta = choice.path("delta");
                    // 只透传正文增量；reasoning_content（思考过程）不推给用户
                    String deltaContent = delta.path("content").asText("");
                    if (!deltaContent.isEmpty()) {
                        content.append(deltaContent);
                        onDelta.accept(deltaContent);
                    }
                    for (JsonNode tc : delta.path("tool_calls")) {
                        // 调试日志：不同模型流式 tool_calls 分片格式有差异，留档便于排查
                        log.debug("tool_call 增量分片：{}", tc);
                        int index = tc.path("index").asInt(0);
                        String id = tc.path("id").asText("");
                        if (!id.isEmpty()) {
                            idBuf.put(index, id);
                        }
                        JsonNode fn = tc.path("function");
                        // 关键：DeepSeek 后续分片会带 name:""（空串非 null），
                        // hasNonNull 判断拦不住它，会把首个分片的真名覆盖成空串。
                        // 所以必须"非空才写入"，而不是"非 null 才写入"。
                        String name = fn.path("name").asText("");
                        if (!name.isEmpty()) {
                            nameBuf.put(index, name);
                        }
                        if (fn.hasNonNull("arguments")) {
                            argBuf.computeIfAbsent(index, k -> new StringBuilder())
                                    .append(fn.path("arguments").asText());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (handle.isCancelled()) {
                // 主动取消：调用方已不关心结果，静默返回当前已有的部分内容
                log.info("LLM 流式读取被取消（客户端已断开）");
                return new StreamResult(content.toString(), "cancelled", List.of());
            }
            throw e;
        }

        List<ToolCallReq> toolCalls = new ArrayList<>();
        for (Integer idx : idBuf.keySet()) {
            toolCalls.add(new ToolCallReq(idBuf.get(idx),
                    nameBuf.getOrDefault(idx, ""), argBuf.get(idx) == null ? "{}" : argBuf.get(idx).toString()));
        }
        log.debug("tool_calls 组装结果：idBuf={} nameBuf={} argBuf大小={}", idBuf, nameBuf, argBuf.size());
        log.info("LLM 流式结束 finish={} contentLen={} toolCalls={}", finishReason[0], content.length(), toolCalls.size());
        return new StreamResult(content.toString(), finishReason[0], toolCalls);
    }

    // ---------- 内部 ----------

    private Map<String, Object> buildBody(List<Msg> messages, List<ToolDef> tools, boolean stream) {
        List<Map<String, Object>> protocolMessages = new ArrayList<>();
        for (Msg m : messages) {
            Map<String, Object> pm = new LinkedHashMap<>();
            // 协议要求小写 role（user/assistant/system/tool）；
            // 历史回放的消息来自数据库（大写枚举值），必须在这里统一归一化
            pm.put("role", m.role() == null ? null : m.role().toLowerCase(java.util.Locale.ROOT));
            if (m.content() != null) {
                pm.put("content", m.content());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (ToolCallReq tc : m.toolCalls()) {
                    tcs.add(Map.of("id", tc.id(), "type", "function",
                            "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson())));
                }
                pm.put("tool_calls", tcs);
            }
            if (m.toolCallId() != null) {
                pm.put("tool_call_id", m.toolCallId());
            }
            protocolMessages.add(pm);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", protocolMessages);
        body.put("stream", stream);
        body.put("temperature", temperature);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(ToolDef::toProtocol).toList());
        }
        return body;
    }

    private Request newRequest(Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 请求体序列化失败", e);
        }
        return new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")))
                .build();
    }

    private String safeBody(Response response) {
        try {
            String body = response.body() != null ? response.body().string() : "";
            return body.length() > 500 ? body.substring(0, 500) : body;
        } catch (Exception e) {
            return "(body 不可读)";
        }
    }
}
