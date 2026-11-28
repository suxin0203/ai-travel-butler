package com.suxin.travel.traveljava.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis key 命名集中管理：避免各处手拼 key 造成格式漂移。
 * 当前库位是共用实例上的 db3，key 前缀仍带业务名以便辨识。
 */
public class RedisKeys {
    /** 登录态白名单：值恒为 "1"，删除即强制下线 */
    public static String loginToken(long uid, String jti) {
        return "login:token:" + uid + ":" + jti;
    }

    /** 每用户每日 LLM 调用计数 */
    public static String llmRate(long uid, LocalDate date) {
        return "rate:llm:" + uid + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 今天的限流 key（业务侧最常用） */
    public static String llmRateToday(long uid) {
        return llmRate(uid, LocalDate.now());
    }

    /**
     * 微信绑定码（v2.10 改造为 code→uid 方向）：值 = 用户 id，TTL 同码 10 分钟。
     * 消息里的两种格式（「username+code」/ 纯 code）都先按码反查用户、再校验归属，
     * 彻底废除原先 uid→code 存储下纯码格式被迫做的 redis KEYS 全库扫描（O(N) 阻塞命令）。
     * 重新生成码时由服务层先删旧码键，保证任意时刻一个用户至多一个有效码键。
     */
    public static String wxBindCode(String code) {
        return WX_BINDCODE_PREFIX + code;
    }

    /** 微信绑定码键前缀（键名形如 wx:bindcode:482913，值 = 用户 id） */
    public static final String WX_BINDCODE_PREFIX = "wx:bindcode:";

    /** 绑定码索引：uid → 其当前在途码（仅用于重新生成时作废旧码键），TTL 同码 10 分钟 */
    public static String wxBindIdxKey(long uid) {
        return "wx:bindidx:" + uid;
    }

    /** 登录防爆破失败计数（契约 §2.2 v2.10）：键含 IP 与用户名，10 分钟滚动窗口，值 = 失败次数 */
    public static String loginFail(String ip, String username) {
        return "login:fail:" + ip + ":" + esc(username);
    }

    // ---------- 邮箱绑定验证码（契约 §2.5/§2.5a，全部键用归一 email，见 Emails.normalize） ----------

    /**
     * email 内的冒号转义为 %3A 后再拼键：本地部分合法字符含冒号，
     * 不转义时 emailBindCode("fail:x@d.com") 会与 emailBindCodeFail("x@d.com") 生成同一键造成碰撞
     */
    private static String esc(String email) {
        return email.replace(":", "%3A");
    }

    /** 绑定验证码：10 分钟有效，值 = 6 位数字 */
    public static String emailBindCode(String email) {
        return "email:bindcode:" + esc(email);
    }

    /** 验证码失败计数：与该验证码同生命周期（重新获取/绑定成功/作废时一并删除） */
    public static String emailBindCodeFail(String email) {
        return "email:bindcode:fail:" + esc(email);
    }

    /** 发码 60s 冷却（SET NX） */
    public static String emailCodeCooldown(String email) {
        return "email:code:cd:" + esc(email);
    }

    /** 同邮箱每日发码计数（yyyyMMdd 后缀跨天兜底） */
    public static String emailCodeDayCount(String email, LocalDate date) {
        return "email:code:cnt:" + esc(email) + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 同用户每日发码计数 */
    public static String emailCodeUserCount(long uid, LocalDate date) {
        return "email:code:ucnt:" + uid + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
