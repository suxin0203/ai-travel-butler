package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.service.WeChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信测试号回调 + 绑定码接口壳（契约 §4.5）。
 * 业务自 v2.10 起全部下沉 WeChatService（分层归位）——controller 只留参数接与结果返回。
 */
@RestController
@RequestMapping("/api/wechat")
@RequiredArgsConstructor
public class WeChatController {

    private final WeChatService weChatService;

    // ---------- 绑定码（需登录，设置页调用） ----------

    @PostMapping("/bindcode")
    public Result<Map<String, Object>> bindCode() {
        return Result.ok(weChatService.bindCode(UserContext.requireUid()));
    }

    /** 解绑微信（契约 §4.5 v2.8.1） */
    @PostMapping("/unbind")
    public Result<Map<String, Boolean>> unbind() {
        return Result.ok(weChatService.unbind(UserContext.requireUid()));
    }

    // ---------- 微信服务器回调（免登录，GET/POST 均验签） ----------

    @GetMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@RequestParam String signature,
                         @RequestParam String timestamp,
                         @RequestParam String nonce,
                         @RequestParam String echostr) {
        return weChatService.verify(signature, timestamp, nonce, echostr);
    }

    @PostMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String onMessage(@RequestParam String signature,
                            @RequestParam String timestamp,
                            @RequestParam String nonce,
                            @RequestBody String xml) {
        return weChatService.onMessage(signature, timestamp, nonce, xml);
    }
}
