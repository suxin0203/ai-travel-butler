package com.suxin.travel.traveljava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.suxin.travel.traveljava.auth.LoginGuard;
import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.common.Emails;
import com.suxin.travel.traveljava.common.JwtUtil;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.LoginDTO;
import com.suxin.travel.traveljava.dto.RegisterDTO;
import com.suxin.travel.traveljava.entity.User;
import com.suxin.travel.traveljava.mapper.UserMapper;
import com.suxin.travel.traveljava.vo.LoginVO;
import com.suxin.travel.traveljava.vo.RegisterVO;
import com.suxin.travel.traveljava.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 认证服务：注册 / 登录 / 登出 / 用户信息。
 * 登录态模型：JWT（自带 uid/username/jti）+ Redis 白名单 login:token:{uid}:{jti}。
 * 删白名单 key 即强制下线，这是本项目"JWT 无状态 + 业务可控下线"的核心实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final LoginGuard loginGuard;
    private final EmailCodeService emailCodeService;

    /** BCrypt 自带随机盐，同一密码每次哈希结果不同，比对用 matches */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${llm.daily-limit:50}")
    private int dailyLimit;

    public RegisterVO register(RegisterDTO dto) {
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, dto.getUsername()));
        if (exists > 0) {
            throw BusinessException.badRequest("用户名已存在");
        }
        // email 归一（契约 §2.5）：trim+小写后校验格式与唯一性，存归一值
        String email = Emails.normalize(dto.getEmail());
        if (email != null) {
            if (!Emails.isValid(email)) {
                throw BusinessException.badRequest("邮箱格式不正确");
            }
            Long emailUsed = userMapper.selectCount(
                    Wrappers.<User>lambdaQuery().eq(User::getEmail, email));
            if (emailUsed > 0) {
                throw BusinessException.badRequest("该邮箱已被其他账号绑定");
            }
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(encoder.encode(dto.getPassword()));
        user.setEmail(email);
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发注册撞唯一键：预检查通过但插库时被并发请求抢先，回查区分文案
            throw BusinessException.badRequest(userMapper.selectCount(
                    Wrappers.<User>lambdaQuery().eq(User::getUsername, dto.getUsername())) > 0
                    ? "用户名已存在" : "该邮箱已被其他账号绑定");
        }
        return new RegisterVO(user.getId(), user.getUsername());
    }

    public LoginVO login(LoginDTO dto) {
        // 登录防爆破（契约 §2.2 v2.10）：IP+用户名 维度 10 分钟窗口 5 次失败锁 429
        String ip = LoginGuard.clientIp();
        loginGuard.checkLocked(ip, dto.getUsername());
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, dto.getUsername()));
        // 用户不存在与密码错误统一文案，避免给攻击者提供"这个用户名存在"的信号
        if (user == null || !encoder.matches(dto.getPassword(), user.getPasswordHash())) {
            loginGuard.recordFailure(ip, dto.getUsername());
            throw BusinessException.badRequest("用户名或密码错误");
        }
        loginGuard.reset(ip, dto.getUsername());
        JwtUtil.TokenInfo info = jwtUtil.create(user.getId(), user.getUsername());
        long ttlSeconds = info.expiresAtEpochSecond() - System.currentTimeMillis() / 1000;
        redis.opsForValue().set(RedisKeys.loginToken(user.getId(), info.jti()), "1",
                Duration.ofSeconds(ttlSeconds));
        log.info("用户登录成功 uid={} ttl={}s", user.getId(), ttlSeconds);
        return new LoginVO(info.token(), jwtUtil.expireHours());
    }

    public void logout() {
        UserContext.Principal principal = UserContext.get();
        redis.delete(RedisKeys.loginToken(principal.getUid(), principal.getJti()));
    }

    public UserVO me(long uid) {
        User user = userMapper.selectById(uid);
        if (user == null) {
            throw BusinessException.notFound();
        }
        String usedStr = redis.opsForValue().get(RedisKeys.llmRateToday(uid));
        int used = usedStr == null ? 0 : Integer.parseInt(usedStr);
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(),
                user.getWechatOpenid() != null,
                notifyOf(user),
                new UserVO.Quota(used, dailyLimit));
    }

    /** 推送渠道偏好（契约 §2.5b）：列迁移前的历史 NULL 视为开（双开是默认语义） */
    private UserVO.Notify notifyOf(User user) {
        return new UserVO.Notify(
                !Boolean.FALSE.equals(user.getNotifyMail()),
                !Boolean.FALSE.equals(user.getNotifyWechat()));
    }

    /** 设置推送渠道偏好（契约 §2.5b）：uid 取自登录态，双布尔全量写入，最后写入胜出 */
    public UserVO.Notify updateNotifyChannels(long uid, boolean mail, boolean wechat) {
        // 局部实体只更新偏好两列：全实体回写会把读取时的 email/openid 旧值一并 SET，
        // 与改邮箱/微信绑定并发时有毫秒级覆盖窗口（review P3）
        User patch = new User();
        patch.setId(uid);
        patch.setNotifyMail(mail);
        patch.setNotifyWechat(wechat);
        userMapper.updateById(patch);
        log.info("推送渠道偏好已更新 uid={} mail={} wechat={}", uid, mail, wechat);
        return new UserVO.Notify(mail, wechat);
    }

    /**
     * 修改邮箱（契约 §2.5，v2.4 起需验证码）。校验顺序固定：格式 → 验证码 → 唯一性 → 绑定。
     * email 缺省/空 = 不修改；归一后与当前相同 = 幂等成功（不要求验证码，emailCode 携带即忽略）。
     */
    public String updateEmail(long uid, String email, String emailCode) {
        User user = userMapper.selectById(uid);
        if (user == null) {
            throw BusinessException.notFound();
        }
        if (email == null || email.isBlank()) {
            return user.getEmail();
        }
        String normalized = Emails.normalize(email);
        if (!Emails.isValid(normalized)) {
            throw BusinessException.badRequest("邮箱格式不正确");
        }
        String current = user.getEmail() == null ? "" : Emails.normalize(user.getEmail());
        if (normalized.equals(current)) {
            return user.getEmail();  // 未变更：免验证码幂等
        }
        if (emailCode == null || emailCode.isBlank()) {
            throw BusinessException.badRequest("请先获取邮箱验证码");
        }
        // 步骤 2：验证码比对（失败计数在 EmailCodeService 内维护）
        emailCodeService.verifyCode(normalized, emailCode);
        // 步骤 3：全库唯一（忽略大小写、忽略自身）；冲突同时作废验证码，防跨目标重放
        Long occupied = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, normalized).ne(User::getId, uid));
        if (occupied > 0) {
            emailCodeService.invalidate(normalized);
            throw BusinessException.badRequest("该邮箱已被其他账号绑定");
        }
        user.setEmail(normalized);
        try {
            userMapper.updateById(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 预检查与更新之间被并发请求抢占同名邮箱：同样作废验证码
            emailCodeService.invalidate(normalized);
            throw BusinessException.badRequest("该邮箱已被其他账号绑定");
        }
        // 步骤 4：绑定成功清场（验证码 + 失败计数）
        emailCodeService.consume(normalized);
        log.info("邮箱绑定成功 uid={} email={}", uid, normalized);
        return user.getEmail();
    }
}
