package com.suxin.travel.traveljava.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退（前端直连后端 8080 场景）：
 * 非 /api 的页面路由（/login、/chat 等）没有对应静态文件，转发到 index.html
 * 交给前端路由接管；/api/** 由各控制器优先处理，不受影响。
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {"/", "/login", "/chat", "/travel", "/schedule", "/settings"})
    public String forward() {
        return "forward:/index.html";
    }
}
