package com.suxin.travel.traveljava.reminder;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import com.suxin.travel.traveljava.notify.NotifyResult;
import com.suxin.travel.traveljava.notify.NotifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 提醒消费端单元测试（难点三的幂等与失败收口逻辑）。
 * NotifyService 用 @MockBean 替身，专测状态机：
 * 非 DISPATCHED 跳过 / 全渠道不可用 FAILED+NO_CHANNEL / 全失败抛异常 / 成功→SENT。
 * MQ 拓扑与 DLQ 走 M3-5 真实验证（RabbitMQ 控制台可见）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReminderConsumerTest {

    @Autowired
    private ReminderConsumer consumer;
    @Autowired
    private ScheduleItemMapper scheduleItemMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private NotifyService notifyService;

    private static final long UID = 501L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM reminder_log");
        jdbcTemplate.update("DELETE FROM schedule_item");
    }

    private long insertItem(String status) {
        ScheduleItem item = new ScheduleItem();
        item.setUserId(UID);
        item.setTitle("测试日程");
        item.setType("TODO");
        item.setStartTime(LocalDateTime.now().plusHours(1));
        item.setRemindMinutesBefore(30);
        item.setRemindAt(LocalDateTime.now());
        item.setStatus(status);
        item.setSource("MANUAL");
        scheduleItemMapper.insert(item);
        return item.getId();
    }

    @Test
    @DisplayName("幂等：状态非 DISPATCHED（如已取消）直接跳过，不发不写日志")
    void skipWhenNotDispatched() {
        long id = insertItem(ScheduleItem.STATUS_CANCELLED);
        consumer.process(id);
        assertEquals(ScheduleItem.STATUS_CANCELLED, scheduleItemMapper.selectById(id).getStatus());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reminder_log", Integer.class));
    }

    @Test
    @DisplayName("全部渠道不可用：FAILED + NO_CHANNEL 日志，不抛异常不重试")
    void noChannelFails() {
        long id = insertItem(ScheduleItem.STATUS_DISPATCHED);
        when(notifyService.sendAll(any())).thenReturn(new NotifyResult(0, 0));
        consumer.process(id);
        assertEquals(ScheduleItem.STATUS_FAILED, scheduleItemMapper.selectById(id).getStatus());
        // NotifyService 是 @MockBean（真实写日志的逻辑被替换），改为验证交互契约
        org.mockito.Mockito.verify(notifyService).logNoChannel(id);
    }

    @Test
    @DisplayName("任一渠道成功 → SENT")
    void successSent() {
        long id = insertItem(ScheduleItem.STATUS_DISPATCHED);
        when(notifyService.sendAll(any())).thenReturn(new NotifyResult(2, 1));
        consumer.process(id);
        assertEquals(ScheduleItem.STATUS_SENT, scheduleItemMapper.selectById(id).getStatus());
    }

    @Test
    @DisplayName("有渠道可用但全失败 → 显式重试 3 次后抛异常（外层 FAILED+DLQ）")
    void allFailThrows() {
        long id = insertItem(ScheduleItem.STATUS_DISPATCHED);
        when(notifyService.sendAll(any())).thenReturn(new NotifyResult(1, 0));
        try {
            consumer.process(id);
            org.junit.jupiter.api.Assertions.fail("应当抛出异常");
        } catch (IllegalStateException expected) {
            // 预期路径：外层会写 FAILED 并 nack → DLQ
        }
        // 显式重试必须真实发生 3 次（审查发现的 P0：容器级 retry 被内部 catch 吞掉从未生效）
        org.mockito.Mockito.verify(notifyService, org.mockito.Mockito.times(3)).sendAll(any());
    }
}
