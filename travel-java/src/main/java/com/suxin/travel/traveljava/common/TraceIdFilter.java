package com.suxin.travel.traveljava.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 每个请求生成短 traceId 放入 MDC，日志模式里输出它。
 * 一次请求跨 Controller/Service/MQ 的所有日志都能用同一个 traceId 串联，
 * 排查"SSE 为什么断了""提醒为什么没发"时不用再翻半天的日志。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        org.slf4j.MDC.put("traceId", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // ThreadLocal/MDC 必须清理，原因同 UserContext
            org.slf4j.MDC.remove("traceId");
        }
    }
}
