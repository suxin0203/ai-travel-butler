package com.suxin.travel.traveljava.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.User;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信模板消息渠道（公众号测试号）。
 * access_token 全局唯一且 7200s 有效、重复获取会使旧 token 失效——
 * 必须缓存（Redis TTL 7000s 留安全余量），收到 40001/42001 时强制刷新重试一次。
 */
@Slf4j
@Component
public class WeChatNotifyChannel implements NotifyChannel {

    private static final DateTimeFormatter WX_TIME = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
    private static final String SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=%s";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String appid;
    private final String secret;
    private final String templateId;
    private final boolean enabled;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build();

    public WeChatNotifyChannel(@Value("${wechat.appid:}") String appid,
                               @Value("${wechat.secret:}") String secret,
                               @Value("${wechat.template-id:}") String templateId,
                               @Value("${channels.wechat:false}") boolean enabled,
                               StringRedisTemplate redis,
                               ObjectMapper objectMapper) {
        this.appid = appid;
        this.secret = secret;
        this.templateId = templateId;
        this.enabled = enabled;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "WECHAT";
    }

    @Override
    public boolean enabledFor(User user) {
        // 渠道参数齐全 + 用户已通过"绑定码"流程绑定了 openid + 用户未关闭微信偏好（契约 §2.5b）
        return enabled
                && notBlank(appid) && notBlank(secret) && notBlank(templateId)
                && notBlank(user.getWechatOpenid())
                && !Boolean.FALSE.equals(user.getNotifyWechat());
    }

    @Override
    public boolean send(User user, ScheduleItem item) {
        // 时间格式化输出（与邮件渠道同规则，禁止 LocalDateTime.toString() 的 T 形式）
        String time = item.getStartTime().format(WX_TIME);
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = getAccessToken(attempt > 0);
            if (token == null) {
                return false;
            }
            int errcode = postTemplate(user.getWechatOpenid(), token, item, time);
            if (errcode == 0) {
                log.info("微信模板消息已发送 uid={} itemId={}", user.getId(), item.getId());
                return true;
            }
            // token 失效：强制刷新后重试一次
            if ((errcode == 40001 || errcode == 42001) && attempt == 0) {
                log.warn("微信 access_token 失效（{}），强制刷新重试", errcode);
                continue;
            }
            log.warn("微信模板消息发送失败 errcode={} itemId={}", errcode, item.getId());
            return false;
        }
        return false;
    }

    /** 取 access_token：Redis 缓存优先；force=true 时删缓存强制刷新 */
    private String getAccessToken(boolean force) {
        String key = "wx:access_token";
        if (!force) {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        } else {
            redis.delete(key);
        }
        // try-with-resources（与 LLMClient 同模式）：保证连接归还连接池，body 异常也不泄漏
        try (Response response = client.newCall(new Request.Builder()
                .url(String.format(TOKEN_URL, appid, secret)).get().build()).execute()) {
            JsonNode root = objectMapper.readTree(response.body().string());
            String token = root.path("access_token").asText("");
            if (token.isEmpty()) {
                log.error("获取微信 access_token 失败：{}", root);
                return null;
            }
            redis.opsForValue().set(key, token, Duration.ofSeconds(7000));
            return token;
        } catch (Exception e) {
            log.error("获取微信 access_token 异常", e);
            return null;
        }
    }

    /** 调模板消息接口，返回微信 errcode（0=成功） */
    private int postTemplate(String openid, String token, ScheduleItem item, String time) {
        try {
            // 模板字段用经典 first/keyword1/keyword2/remark 结构；
            // 占位符规范见 docs/API契约.md §4.6a
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("first", Map.of("value", "⏰ 行程提醒"));
            data.put("keyword1", Map.of("value", item.getTitle()));
            data.put("keyword2", Map.of("value", time));
            data.put("remark", Map.of("value", "提前 " + item.getRemindMinutesBefore() + " 分钟提醒 · 来自 AI 旅行管家"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("touser", openid);
            body.put("template_id", templateId);
            body.put("data", data);
            // try-with-resources（与 LLMClient 同模式）：连接确保归还，errcode 解析失败也不泄漏
            try (Response response = client.newCall(new Request.Builder()
                    .url(String.format(SEND_URL, token))
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build()).execute()) {
                return objectMapper.readTree(response.body().string()).path("errcode").asInt(-1);
            }
        } catch (Exception e) {
            log.error("微信模板消息请求异常", e);
            return -1;
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
