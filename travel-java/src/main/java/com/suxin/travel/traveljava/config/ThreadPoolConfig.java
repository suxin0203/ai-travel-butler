package com.suxin.travel.traveljava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * LLM 调用专用线程池。
 * 为什么不用裸 new Thread：SSE 并发一高线程数失控；统一线程池才能做
 * 排队上限（queue=100）、拒绝策略（直接抛异常提示"稍后再试"而不是无限堆积）。
 * 参数按单机小项目的量级配置，可以讲清每个数字的取舍。
 */
@Configuration
public class ThreadPoolConfig {

    @Bean("llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("llm-");
        // 队列满且线程满时快速失败，SSE 场景宁可让用户重试也不能无限占资源
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    // 说明：不再自定义 TaskScheduler Bean。
    // 曾用单线程池跑 SSE 心跳，导致它和提醒扫描器（@Scheduled）互相排队——
    // 一个僵死客户端的阻塞写能把整分扫描拖死。改用 Boot 自动配置的 taskScheduler，
    // 线程数由 spring.task.scheduling.pool.size=4 控制，扫描与心跳互不拖累。
}
