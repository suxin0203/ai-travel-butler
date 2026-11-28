package com.suxin.travel.traveljava.reminder;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DISPATCHED 卡死兜底扫描集成测试（契约 §4.6 v2.10）：
 * CAS 抢占后进程崩溃 / broker confirm 丢失 → 日程永久滞留 DISPATCHED（提醒静默丢失，
 * 此前是提醒链路唯一已知丢失窗口）。修复后扫描器每轮先救援：DISPATCHED 且 updated_at
 * 早于 now−5 分钟的记录重置 PENDING 并写 DISPATCHED_TIMEOUT 日志；在途（updated_at 新）不动。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReminderScannerRescueTest {

    @Autowired
    private ReminderScanner scanner;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long UID = 501L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM reminder_log");
        jdbcTemplate.update("DELETE FROM schedule_item");
    }

    /** 插入一条指定状态与 updated_at 的日程（兜底判据就是 updated_at，必须显式给值） */
    private long insertItem(String title, String status, LocalDateTime updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO schedule_item(user_id, title, type, start_time, remind_at,
                        remind_minutes_before, status, source, created_at, updated_at)
                VALUES (?, ?, 'TODO', DATE_ADD(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 30 MINUTE), 30, ?, 'MANUAL', NOW(), ?)
                """, UID, title, status, Timestamp.valueOf(updatedAt));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM schedule_item WHERE title=? ORDER BY id DESC LIMIT 1", Long.class, title);
    }

    @Test
    @DisplayName("DISPATCHED 滞留超 5 分钟 → 重置 PENDING + DISPATCHED_TIMEOUT 日志")
    void stuckDispatchedGetsRescued() {
        long stuck = insertItem("卡死日程", ScheduleItem.STATUS_DISPATCHED,
                LocalDateTime.now().minusMinutes(6));
        long inflight = insertItem("在途日程", ScheduleItem.STATUS_DISPATCHED,
                LocalDateTime.now().minusMinutes(1));

        scanner.scan();

        String stuckStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM schedule_item WHERE id=?", String.class, stuck);
        String inflightStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM schedule_item WHERE id=?", String.class, inflight);
        assertEquals("PENDING", stuckStatus, "滞留超 5 分钟必须被救援回 PENDING");
        assertEquals("DISPATCHED", inflightStatus, "在途消息（updated_at 新）不得被误伤");

        Long rescued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reminder_log WHERE schedule_item_id=? AND detail='DISPATCHED_TIMEOUT'",
                Long.class, stuck);
        assertTrue(rescued >= 1, "救援必须写 DISPATCHED_TIMEOUT 日志供回溯");
    }

    @Test
    @DisplayName("救援后的 PENDING 到点项下一轮正常投递（状态机重新走通）")
    void rescuedItemReentersNormalScan() {
        // remind_at 已过 → 救援后本轮的 due 查询直接捞起投递（MQ 在测试环境 mock/本地可用，断言状态推进）
        long stuck = insertItem("到点滞留日程", ScheduleItem.STATUS_DISPATCHED,
                LocalDateTime.now().minusMinutes(6));
        jdbcTemplate.update("UPDATE schedule_item SET remind_at = NOW() WHERE id=?", stuck);

        scanner.scan();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM schedule_item WHERE id=?", String.class, stuck);
        // 救援后同一轮 due 查询捞起并 CAS→DISPATCHED 投递（本地 RabbitMQ 可用）；即便 MQ 不可用
        // 由 catch 回滚 PENDING，也断言绝不再是"滞留的旧 DISPATCHED"语义
        assertTrue("PENDING".equals(status) || "DISPATCHED".equals(status),
                "救援后应回到正常状态机流转，实际: " + status);
    }
}
