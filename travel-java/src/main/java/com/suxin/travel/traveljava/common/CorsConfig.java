package com.suxin.travel.traveljava.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * 全局跨域配置（Servlet 环境）。
 * 相比旧版"允许所有来源"，这里收敛为配置文件里的显式白名单
 * （allowCredentials=true 时开放通配符等于放弃来源校验，安全上是倒退）。
 * CORS 必须在过滤器链最前端执行，否则预检请求（OPTIONS）会被后续拦截器拦下。
 *
 * 为什么用 originPatterns 而不是 allowedOrigins：
 * 内网手机经 vite 代理（192.168.101.x:5173）访问时，浏览器对 POST 仍会带
 * Origin 头（=本机内网 IP），代理把 Host 改写成 localhost:8080 后，后端判定为
 * 跨域请求并按白名单校验——精确白名单不含内网 IP 就会 403 "Invalid CORS
 * request"（登录等 POST 全挂、页面 GET 正常，正是这个症状）。pattern 既兼容
 * 精确来源，又支持 192.168.101.* 这类网段模式（DHCP 换 IP 不失效）；带
 * allowCredentials 时响应头仍回显具体来源而非 *，安全语义不回退。
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        // 逗号分隔的白名单（支持 * 网段模式）。两个注意点：
        // ① TRAVEL_CORS_ORIGINS 是整体替换不是追加，且这里绝不能配 *——
        //    patterns 路径下配 * 会静默放开全部来源且带 credentials（旧
        //    allowedOrigins 路径至少会抛异常暴露问题）；
        // ② * 匹配任意字符串（含字母和点，如 192.168.101.evil.com:5173 也过），
        //    仅协议与端口锚定；要收紧时配精确来源。安全兜底：本项目无 cookie，
        //    JWT 走 Authorization 头，跨源页拿不到 token，攻击面≈0。
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedHeaders(List.of(
                "Origin", "Content-Type", "Accept", "Authorization",
                "X-Requested-With", "Cache-Control", "Last-Event-ID"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("corsFilter");
        return registration;
    }
}
