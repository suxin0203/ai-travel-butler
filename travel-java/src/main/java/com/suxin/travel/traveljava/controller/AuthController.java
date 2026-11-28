package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.EmailBindDTO;
import com.suxin.travel.traveljava.dto.EmailDTO;
import com.suxin.travel.traveljava.dto.LoginDTO;
import com.suxin.travel.traveljava.dto.NotifyChannelsDTO;
import com.suxin.travel.traveljava.dto.RegisterDTO;
import com.suxin.travel.traveljava.service.AuthService;
import com.suxin.travel.traveljava.service.EmailCodeService;
import com.suxin.travel.traveljava.vo.LoginVO;
import com.suxin.travel.traveljava.vo.RegisterVO;
import com.suxin.travel.traveljava.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 认证接口：注册/登录/登出/当前用户/改邮箱，契约见 docs/API契约.md §2 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailCodeService emailCodeService;

    @PostMapping("/register")
    public Result<RegisterVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(authService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me(UserContext.requireUid()));
    }

    @PutMapping("/me")
    public Result<java.util.Map<String, String>> updateEmail(@RequestBody EmailBindDTO dto) {
        // 契约 §2.5：data 为 {"email": "..."}；email 可选、变更时需 emailCode，校验在 service 按序执行。
        // singletonMap 允许 null 值：从未绑定邮箱的用户走「缺省=不修改」路径时 email 为 null（Map.of 会 NPE → 500）
        String email = authService.updateEmail(UserContext.requireUid(), dto.getEmail(), dto.getEmailCode());
        return Result.ok(java.util.Collections.singletonMap("email", email));
    }

    /** 契约 §2.5a：发送邮箱绑定验证码（防轰炸频控在 service 内） */
    @PostMapping("/email/code")
    public Result<java.util.Map<String, Long>> sendEmailCode(@Valid @RequestBody EmailDTO dto) {
        long expireSeconds = emailCodeService.sendBindCode(UserContext.requireUid(), dto.getEmail());
        return Result.ok(java.util.Map.of("expireSeconds", expireSeconds));
    }

    /** 契约 §2.5b：设置推送渠道偏好（双布尔全量，最后写入胜出） */
    @PutMapping("/notify-channels")
    public Result<UserVO.Notify> updateNotifyChannels(@Valid @RequestBody NotifyChannelsDTO dto) {
        return Result.ok(authService.updateNotifyChannels(
                UserContext.requireUid(), dto.getMail(), dto.getWechat()));
    }
}
