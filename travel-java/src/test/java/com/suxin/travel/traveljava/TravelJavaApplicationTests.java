package com.suxin.travel.traveljava;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 上下文冒烟：验证整个应用能在测试配置下启动（连 ai_travel_test 库 + Redis db4） */
@SpringBootTest
@ActiveProfiles("test")
class TravelJavaApplicationTests {

    @Test
    void contextLoads() {
    }
}
