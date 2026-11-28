package com.suxin.travel.traveljava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.entity.User;
import com.suxin.travel.traveljava.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信测试号回调 + 绑定码业务（契约 §4.5）。自稳健性轮 v2.10 起自 controller 下沉：
 * controller 只留参数接与结果返回，验签/绑定/防爆破全部业务在这里（分层归位）。
 *
 * 安全设计（审查修复后）：
 * 1. GET/POST 都验签——签名是"请求确实来自微信服务器"的唯一凭证，伪造请求一律按 success 吞掉；
 * 2. 绑定必须凭本人设置页生成的 10 分钟绑定码，否则攻击者可把自己微信绑到别人账号收他人提醒；
 * 3. 绑定失败按 openid 计数（Redis INCR+TTL），超 5 次锁定至码过期，防暴力猜 6 位码；
 * 4. 失败文案统一"用户名或绑定码不对"，不提供用户名枚举预言机。
 *
 * 绑定码存储（v2.10 改造）：主键 wx:bindcode:{code} → uid（按码反查归属，消息到达直接 GET），
 * 索引 wx:bindidx:{uid} → code（仅用于重新生成时作废旧码——旧码若不删，泄露的码在剩余 TTL
 * 内仍可绑定，「重新生成即止血」的语义就破了）。此前 uid→code 单键存储下，纯码格式被迫
 * redis KEYS 全库扫描（O(N) 阻塞命令），是稳健性轮修掉的阻塞点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatService {

    private static final Pattern BIND_PATTERN = Pattern.compile("^绑定\\s*(\\S+)\\s*(\\S+)$");
    /** v2.7 主格式：直接发送 6 位验证码，无分隔符（手输/复制都最简） */
    private static final Pattern CODE_ONLY_PATTERN = Pattern.compile("^\\d{6}$");
    private static final int MAX_BIND_ATTEMPTS = 5;

    @Value("${wechat.token:}")
    private String wxToken;

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final SecureRandom secureRandom = new SecureRandom();

    // ---------- 绑定码（需登录，设置页调用） ----------

    public Map<String, Object> bindCode(long uid) {
        // 生成前先作废旧码（重新生成即止血）；键冲突（6 位码撞上在途码）换一个再试
        String oldCode = redis.opsForValue().get(RedisKeys.wxBindIdxKey(uid));
        if (oldCode != null) {
            redis.delete(RedisKeys.wxBindCode(oldCode));
        }
        String code = null;
        for (int i = 0; i < 5; i++) {
            String candidate = String.format("%06d", secureRandom.nextInt(1_000_000));
            if (Boolean.FALSE.equals(redis.hasKey(RedisKeys.wxBindCode(candidate)))) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            // 5 次全碰撞（概率 10^-30 量级）也兜住：直接覆盖写，最坏情况占用一个在途码键
            code = String.format("%06d", secureRandom.nextInt(1_000_000));
        }
        // SecureRandom：6 位码会被暴力猜，不能用可预测的 Random
        redis.opsForValue().set(RedisKeys.wxBindCode(code), String.valueOf(uid), Duration.ofMinutes(10));
        redis.opsForValue().set(RedisKeys.wxBindIdxKey(uid), code, Duration.ofMinutes(10));
        return Map.of("code", code, "expireMinutes", 10);
    }

    /** 解绑微信（契约 §4.5 v2.8.1）：清 openid 恢复未绑定态，之后可重新生成绑定码绑定任意微信 */
    public Map<String, Boolean> unbind(long uid) {
        // openid 唯一键列清空必须用 UpdateWrapper 显式 set null（实体回写策略不会更新 null 字段）
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, uid)
                .set(User::getWechatOpenid, null));
        log.info("微信已解绑 uid={}", uid);
        return Map.of("unbound", true);
    }

    // ---------- 微信服务器回调（免登录，GET/POST 均验签） ----------

    public String verify(String signature, String timestamp, String nonce, String echostr) {
        if (!enabled()) {
            return "channel disabled";
        }
        return signatureValid(signature, timestamp, nonce) ? echostr : "signature invalid";
    }

    public String onMessage(String signature, String timestamp, String nonce, String xml) {
        if (!enabled()) {
            return "channel disabled";
        }
        // 验签不过 = 伪造请求：按 success 静默吞掉，不暴露任何信息
        if (!signatureValid(signature, timestamp, nonce)) {
            log.warn("微信回调验签失败，丢弃伪造请求");
            return "success";
        }
        try {
            String fromUser = xmlTag(xml, "FromUserName");
            String msgType = xmlTag(xml, "MsgType");
            // 被动回复的 FromUserName 必须是收到的 ToUserName（测试号账号）——
            // 此前硬编码成应用名导致微信校验不过、回复被静默丢弃（用户收不到「绑定成功」）
            String appAccount = xmlTag(xml, "ToUserName");
            if ("event".equals(msgType) && "subscribe".equals(xmlTag(xml, "Event"))) {
                return replyText(fromUser, appAccount, "欢迎使用 AI 旅行管家！请先在网页「设置」页生成绑定码，"
                        + "然后直接给我发送 6 位数字验证码即可完成绑定");
            }
            if ("text".equals(msgType)) {
                String content = xmlTag(xml, "Content");
                // 绑定消息两种形态：v2.7 主格式（纯 6 位码）与兼容格式（绑定 用户名 码）
                if (content != null && (content.startsWith("绑定")
                        || CODE_ONLY_PATTERN.matcher(content.trim()).matches())) {
                    return replyText(fromUser, appAccount, handleBind(content, fromUser));
                }
            }
            // 微信 5s 超时会重试 3 次，无业务要处理时必须回 success 终止重试
            return "success";
        } catch (Exception e) {
            log.error("微信回调处理失败", e);
            return "success";
        }
    }

    /**
     * 绑定消息处理（契约 §4.5 v2.7/v2.10）：主格式 = 直接发送 6 位验证码；兼容「绑定 用户名 码」
     * 历史格式。两种格式统一按码反查归属（GET wx:bindcode:{code}），兼容格式额外校验
     * username 与码归属一致；防暴力猜码按 openid 记失败次数。
     */
    private String handleBind(String content, String openid) {
        String trimmed = content.trim();
        String code;
        String username = null;
        if (CODE_ONLY_PATTERN.matcher(trimmed).matches()) {
            code = trimmed;
        } else {
            Matcher m = BIND_PATTERN.matcher(trimmed);
            if (!m.matches()) {
                return "格式不对哦～请在网页「设置」页生成绑定码后，直接发送 6 位数字验证码即可";
            }
            username = m.group(1);
            code = m.group(2);
        }
        // 防暴力猜码：按 openid 记失败次数，超阈值锁定至码过期
        String failKey = "wx:bindfail:" + openid;
        String fails = redis.opsForValue().get(failKey);
        if (fails != null && Integer.parseInt(fails) >= MAX_BIND_ATTEMPTS) {
            return "尝试次数过多，请 10 分钟后再试";
        }

        // 按码反查归属（O(1) GET，替代旧 KEYS 全库扫描）；兼容格式要求用户名与码归属一致
        String uidStr = redis.opsForValue().get(RedisKeys.wxBindCode(code));
        User user = null;
        if (uidStr != null) {
            User cand = userMapper.selectById(Long.parseLong(uidStr));
            if (cand != null && (username == null || username.equals(cand.getUsername()))) {
                user = cand;
            }
        }
        // 用户不存在/码无效/归属不符统一 bindFail，不给用户名枚举机会
        if (user == null) {
            return bindFail(openid, failKey);
        }
        return doBind(user, openid, failKey);
    }

    /** 绑定失败统一收口：失败计数 +1（文案统一防用户名枚举） */
    private String bindFail(String openid, String failKey) {
        // INCR+EXPIRE 原子 Lua（公共 RedisScripts）：两步写法在崩溃窗口会泄漏无 TTL 键
        Long newFails = redis.execute(com.suxin.travel.traveljava.common.RedisScripts.INCR_EXPIRE,
                java.util.List.of(failKey), String.valueOf(Duration.ofMinutes(10).toSeconds()));
        log.info("微信绑定失败 openid={} fails={}", openid, newFails == null ? 0 : newFails);
        return "用户名或验证码不对，请在网页「设置」页重新生成（10 分钟内有效，剩 "
                + Math.max(0, MAX_BIND_ATTEMPTS - (newFails - 1)) + " 次机会）";
    }

    /**
     * 绑定成功收口：重复绑定 = 覆盖旧 openid（契约 §4.5），清码（反查键+索引键）与失败计数。
     * 换绑抢占：该 openid 已绑在另一账号时（uk_openid 唯一键冲突），先解除旧账号持有
     * 再写入本账号——「一个微信只绑一个账号，后绑覆盖先绑」。残留冲突（并发抢绑）由
     * 外层 catch 兜底回 success，用户重试即可。
     */
    private String doBind(User user, String openid, String failKey) {
        user.setWechatOpenid(openid);
        try {
            userMapper.updateById(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getWechatOpenid, openid).ne(User::getId, user.getId())
                    .set(User::getWechatOpenid, null));
            userMapper.updateById(user);
            log.info("微信换绑：已解除旧账号持有 openid={} 新uid={}", openid, user.getId());
        }
        String usedCode = redis.opsForValue().get(RedisKeys.wxBindIdxKey(user.getId()));
        if (usedCode != null) {
            redis.delete(RedisKeys.wxBindCode(usedCode));
        }
        redis.delete(RedisKeys.wxBindIdxKey(user.getId()));
        redis.delete(failKey);
        log.info("微信绑定成功 uid={}", user.getId());
        return "绑定成功！之后日程提醒会发到这里，祝你旅途愉快～";
    }

    // ---------- 签名与工具 ----------

    private boolean enabled() {
        return wxToken != null && !wxToken.isBlank();
    }

    /** token/timestamp/nonce 字典序排序拼接 → sha1（契约 §4.5） */
    private boolean signatureValid(String signature, String timestamp, String nonce) {
        List<String> list = new ArrayList<>(List.of(wxToken, timestamp, nonce));
        Collections.sort(list);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(String.join("", list).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 极简 XML 取值：微信回调报文结构固定，无需完整 XML 解析器（避免外部依赖） */
    private String xmlTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + tag + ">",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** 组装被动回复的文本消息 XML：ToUserName=用户 openid，FromUserName=测试号账号；CDATA 段转义防提前闭合 */
    private String replyText(String toUser, String fromApp, String content) {
        String safe = content.replace("]]>", "]]]]><![CDATA[>");
        return "<xml><ToUserName><![CDATA[" + toUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + fromApp + "]]></FromUserName>"
                + "<CreateTime>" + System.currentTimeMillis() / 1000 + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + safe + "]]></Content></xml>";
    }
}
