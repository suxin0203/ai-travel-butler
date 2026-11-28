package com.suxin.travel.traveljava.config;

import com.suxin.travel.traveljava.auth.AuthInterceptor;
import com.suxin.travel.traveljava.auth.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置：注册两级拦截器。
 * 第一级 AuthInterceptor：除白名单外全部要求登录；
 * 第二级 RateLimitInterceptor：只挂在会消耗 LLM 额度的接口上。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        // 微信服务器回调由微信签名校验，不走 JWT
                        "/api/wechat/callback"
                )
                .order(1);
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/chat", "/api/travel/**")
                .order(2);
    }
}
