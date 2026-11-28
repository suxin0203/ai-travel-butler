package com.suxin.travel.traveljava.auth;

import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.common.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流拦截器测试：测试配置 llm.daily-limit=3。
 * 直接驱动拦截器（不真调 LLM 接口），验证 INCR 计数与第 4 次 429。
 */
@SpringBootTest
@ActiveProfiles("test")
class RateLimitInterceptorTest {

    @Autowired
    private RateLimitInterceptor interceptor;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM user");
        redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    @DisplayName("前 3 次放行，第 4 次 429；计数 key 按用户隔离")
    void limitByUser() throws Exception {
        UserContext.set(new UserContext.Principal(999L, "tester", "jti"));
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()),
                        "第 " + (i + 1) + " 次应放行");
            }
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(new MockHttpServletRequest(), resp, new Object()), "第 4 次应被限流");
            assertEquals(429, resp.getStatus());

            // 其他用户不受影响
            UserContext.set(new UserContext.Principal(1000L, "other", "jti2"));
            assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
        } finally {
            UserContext.clear();
            redis.delete(RedisKeys.llmRateToday(999L));
            redis.delete(RedisKeys.llmRateToday(1000L));
        }
    }

    @Test
    @DisplayName("ASYNC 重派发不计数：SSE 完成时容器重派发，拦截器不得重复 INCR")
    void asyncDispatchNotCounted() throws Exception {
        UserContext.set(new UserContext.Principal(1001L, "async-user", "jti3"));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setDispatcherType(jakarta.servlet.DispatcherType.REQUEST);
            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
            Long afterFirst = Long.valueOf(redis.opsForValue().get(RedisKeys.llmRateToday(1001L)));

            // 模拟 SSE 完成后的 ASYNC 重派发：必须直接放行且不计数
            MockHttpServletRequest asyncRequest = new MockHttpServletRequest();
            asyncRequest.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
            assertTrue(interceptor.preHandle(asyncRequest, new MockHttpServletResponse(), new Object()));
            assertEquals(afterFirst, Long.valueOf(redis.opsForValue().get(RedisKeys.llmRateToday(1001L))),
                    "ASYNC 派发不应再次累加限流计数（否则一次对话消耗两格配额）");
        } finally {
            UserContext.clear();
            redis.delete(RedisKeys.llmRateToday(1001L));
        }
    }
}
