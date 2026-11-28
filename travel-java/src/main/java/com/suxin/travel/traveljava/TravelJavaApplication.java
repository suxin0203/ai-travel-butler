package com.suxin.travel.traveljava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * @EnableScheduling：提醒扫描器（每分钟）与 SSE 心跳（每 30s）都靠它驱动
 */
@SpringBootApplication
@EnableScheduling
public class TravelJavaApplication {

    public static void main(String[] args) {
        // 强制东八区：提醒时间、限流的"今日"边界、JSON 时间序列化都依赖 JVM 默认时区，
        // 不能依赖部署机器恰好是北京时间（容器/服务器可能是 UTC）
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(TravelJavaApplication.class, args);
    }
}
