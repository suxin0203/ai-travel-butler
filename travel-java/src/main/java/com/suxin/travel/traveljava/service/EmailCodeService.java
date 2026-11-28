package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.common.Emails;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.common.RedisScripts;
import com.suxin.travel.traveljava.notify.MailTexts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 邮箱绑定验证码（契约 §2.5/§2.5a）：发送三道频控防轰炸 + 比对失败计数防暴力猜码。
 *
 * 安全设计（契约评审后定稿）：
 * 1. 发送环节不查「邮箱是否已被他人绑定」——避免被用来枚举注册账号，唯一性在绑定环节校验并作废验证码；
 * 2. 频控按序执行：①同邮箱 60s 冷却（SET NX）→ ②同用户每日 → ③同邮箱每日（②排前：攻击者自身
 *    配额耗尽时不再消耗目标邮箱当日额度）；②③计数在 429 时不回滚（失败尝试也计入，与限流器同语义）；
 * 3. 验证码失败计数与验证码同生命周期（email:bindcode:fail:{email}，TTL 600s）——键不存在不计次，
 *    累计错 5 次作废两键；刻意不做发送前锁定预检（预检会让攻击者故意错猜 5 次阻断受害者本人绑定）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    // INCR+EXPIRE 原子 Lua 已抽公共 RedisScripts.INCR_EXPIRE（v2.10 与限流拦截器合并；
    // 此处顺带获得 TTL<0 自愈分支，历史泄漏 key 不再永久占用）

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration CODE_TTL = Duration.ofSeconds(600);
    private static final int MAX_FAILS = 5;
    private static final int DAILY_LIMIT = 10;

    private final StringRedisTemplate redis;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${channels.mail:false}")
    private boolean mailEnabled;
    @Value("${spring.mail.host:}")
    private String mailHost;
    @Value("${spring.mail.username:}")
    private String mailFrom;

    /** 发送绑定验证码。返回有效秒数（前端倒计时用） */
    public long sendBindCode(long uid, String rawEmail) {
        String email = Emails.normalize(rawEmail);
        if (!Emails.isValid(email)) {
            throw BusinessException.badRequest("邮箱格式不正确");
        }
        if (!mailEnabled || mailHost.isBlank() || mailFrom.isBlank()) {
            throw BusinessException.badRequest("邮件服务未启用");
        }
        // ① 同邮箱 60s 冷却（SET NX 原子，存在即拒绝）
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(RedisKeys.emailCodeCooldown(email), "1", Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(acquired)) {
            throw BusinessException.tooManyRequests("发送太频繁，请 60 秒后再试");
        }
        // ② 同用户每日 ≤10（排前面：自身配额耗尽时不再消耗目标邮箱的当日额度）
        if (!incrWithinDaily(RedisKeys.emailCodeUserCount(uid, LocalDate.now()))) {
            throw BusinessException.tooManyRequests("今日发送次数已达上限，请明天再试");
        }
        // ③ 同邮箱每日 ≤10
        if (!incrWithinDaily(RedisKeys.emailCodeDayCount(email, LocalDate.now()))) {
            throw BusinessException.tooManyRequests("该邮箱今日发送次数已达上限，请明天再试");
        }
        // ④ 生成 6 位码：SecureRandom——验证码会被暴力猜，不能用可预测的 Random
        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        // 重发覆盖旧码：旧码与其失败计数一并立即失效（契约 §2.5a）
        redis.delete(RedisKeys.emailBindCodeFail(email));
        redis.opsForValue().set(RedisKeys.emailBindCode(email), code, CODE_TTL);
        sendMail(email, code);
        log.info("邮箱绑定验证码已发送 uid={} email={}", uid, email);
        return CODE_TTL.toSeconds();
    }

    /** §2.5 步骤 2：比对验证码。通过则静默返回；失败按契约报错并维护失败计数 */
    public void verifyCode(String email, String code) {
        String codeKey = RedisKeys.emailBindCode(email);
        String saved = redis.opsForValue().get(codeKey);
        if (saved == null) {
            // 键不存在（未获取/已过期/已作废）不计失败次数——过期后继续计数等于给未来新码预埋障碍
            throw BusinessException.badRequest("验证码错误或已过期");
        }
        if (!saved.equals(code)) {
            String failKey = RedisKeys.emailBindCodeFail(email);
            // INCR+首次 EXPIRE 原子 Lua：计数与验证码同生命周期（600s），不因后续失败刷新 TTL
            Long fails = redis.execute(RedisScripts.INCR_EXPIRE, List.of(failKey), String.valueOf(CODE_TTL.toSeconds()));
            if (fails != null && fails >= MAX_FAILS) {
                invalidate(email);
                throw BusinessException.badRequest("验证码错误次数过多，已失效，请重新获取");
            }
            throw BusinessException.badRequest("验证码错误或已过期");
        }
    }

    /** 唯一性冲突时作废验证码（防拿到一个验证码去试绑多个目标） */
    public void invalidate(String email) {
        redis.delete(RedisKeys.emailBindCode(email));
        redis.delete(RedisKeys.emailBindCodeFail(email));
    }

    /** 绑定成功后清场 */
    public void consume(String email) {
        invalidate(email);
    }

    /** 每日计数闸：INCR 后 ≤ 上限放行；EXPIRE 只在首次设置（Lua 原子） */
    private boolean incrWithinDaily(String key) {
        Long count = redis.execute(RedisScripts.INCR_EXPIRE, List.of(key), String.valueOf(Duration.ofDays(1).toSeconds()));
        return count != null && count <= DAILY_LIMIT;
    }

    private void sendMail(String to, String code) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            // 配置齐全性已在入口校验；此处兜底转 500（契约 §2.5a：SMTP 异常 500，冷却/计数不回滚）
            throw BusinessException.internal("邮件服务异常");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(to);
        message.setSubject(MailTexts.bindCodeSubject());
        message.setText(MailTexts.bindCodeBody(code));
        sender.send(message);
    }
}
