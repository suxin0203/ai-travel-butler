package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.dto.ScheduleCreateDTO;
import com.suxin.travel.traveljava.dto.ScheduleUpdateDTO;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import com.suxin.travel.traveljava.vo.ScheduleVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 日程服务集成测试：契约 §3.4~§3.8 的全部规则。
 * 重点覆盖：remind_at 派生与重置、越权 404、终态 400、删除幂等、部分更新空串清空语义。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduleServiceIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ScheduleItemMapper scheduleItemMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long UID = 101L;
    private static final long OTHER = 202L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM schedule_item");
    }

    private ScheduleCreateDTO createDTO(String title, String start) {
        ScheduleCreateDTO dto = new ScheduleCreateDTO();
        dto.setTitle(title);
        dto.setStartTime(LocalDateTime.parse(start,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return dto;
    }

    @Test
    @DisplayName("创建：remind_at 派生正确，默认 TODO/30 分钟/PENDING/MANUAL")
    void createDefaults() {
        ScheduleCreateDTO dto = createDTO("去故宫", "2026-11-26 09:00:00");
        dto.setRemindMinutesBefore(60);
        ScheduleVO vo = scheduleService.create(UID, dto, "MANUAL");
        assertEquals("2026-11-26 08:00:00", vo.getRemindAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertEquals("TODO", vo.getType());
        assertEquals("PENDING", vo.getStatus());
        assertEquals("MANUAL", vo.getSource());
    }

    @Test
    @DisplayName("创建：endTime 早于 startTime 返回 400；非法类型 400")
    void createValidation() {
        ScheduleCreateDTO bad = createDTO("测试", "2026-11-26 09:00:00");
        bad.setEndTime(LocalDateTime.parse("2026-11-26 08:00:00", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertThrows(BusinessException.class, () -> scheduleService.create(UID, bad, "MANUAL"));

        ScheduleCreateDTO badType = createDTO("测试", "2026-11-26 09:00:00");
        badType.setType("MOVIE");
        assertThrows(BusinessException.class, () -> scheduleService.create(UID, badType, "MANUAL"));
    }

    @Test
    @DisplayName("越权：他人 uid 访问返回 404（不区分不存在与非本人）")
    void ownership() {
        ScheduleVO vo = scheduleService.create(UID, createDTO("我的日程", "2026-11-26 09:00:00"), "MANUAL");
        assertThrows(BusinessException.class, () -> scheduleService.update(OTHER, vo.getId(), new ScheduleUpdateDTO()));
        assertThrows(BusinessException.class, () -> scheduleService.delete(OTHER, vo.getId()));
        assertThrows(BusinessException.class, () -> scheduleService.complete(OTHER, vo.getId()));
        assertEquals(0, scheduleService.list(OTHER, null, null, null).size());
    }

    @Test
    @DisplayName("修改：重算 remind_at 且 SENT 重置回 PENDING（契约 §3.6/评审新增规则）")
    void updateResetsAndRecalculates() {
        ScheduleCreateDTO createDto = createDTO("旧标题", "2026-11-26 09:00:00");
        createDto.setRemindMinutesBefore(60);
        ScheduleVO vo = scheduleService.create(UID, createDto, "MANUAL");
        // 模拟提醒已发送
        ScheduleItem item = scheduleItemMapper.selectById(vo.getId());
        item.setStatus(ScheduleItem.STATUS_SENT);
        scheduleItemMapper.updateById(item);

        ScheduleUpdateDTO dto = new ScheduleUpdateDTO();
        dto.setTitle("新标题");
        dto.setStartTime(LocalDateTime.parse("2026-11-27 14:00:00", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        ScheduleVO updated = scheduleService.update(UID, vo.getId(), dto);

        assertEquals("新标题", updated.getTitle());
        assertEquals("2026-11-27 13:00:00", updated.getRemindAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertEquals("PENDING", updated.getStatus(), "SENT 修改后必须重置回 PENDING 以便重新提醒");
    }

    @Test
    @DisplayName("修改：部分更新语义——空串清空 note，缺省保持原值；终态 400")
    void updatePartialSemantics() {
        ScheduleVO vo = scheduleService.create(UID, createDTO("有备注的日程", "2026-11-26 09:00:00"), "MANUAL");
        ScheduleUpdateDTO clearNote = new ScheduleUpdateDTO();
        clearNote.setNote("");
        ScheduleVO updated = scheduleService.update(UID, vo.getId(), clearNote);
        assertNull(updated.getNote(), "空串应清空 note");
        assertEquals("有备注的日程", updated.getTitle(), "未提供的字段保持原值");

        // DONE 后修改 → 400
        ScheduleItem item = scheduleItemMapper.selectById(vo.getId());
        item.setStatus(ScheduleItem.STATUS_DONE);
        scheduleItemMapper.updateById(item);
        assertThrows(BusinessException.class, () -> scheduleService.update(UID, vo.getId(), new ScheduleUpdateDTO()));
    }

    @Test
    @DisplayName("删除：幂等（重复删 200）；DONE 后删 400；查询默认排除已取消")
    void deleteRules() {
        ScheduleVO vo = scheduleService.create(UID, createDTO("待删日程", "2026-11-26 09:00:00"), "MANUAL");
        scheduleService.delete(UID, vo.getId());
        scheduleService.delete(UID, vo.getId()); // 幂等，不抛
        assertEquals(0, scheduleService.list(UID, null, null, null).size(), "默认查询排除 CANCELLED");

        // 契约 v2.8：DONE 也可删除（用户拍板：完成态历史可清理），逻辑删后默认查询同样排除
        ScheduleVO done = scheduleService.create(UID, createDTO("已完成", "2026-11-27 09:00:00"), "MANUAL");
        scheduleService.complete(UID, done.getId());
        scheduleService.delete(UID, done.getId());
        assertEquals(0, scheduleService.list(UID, null, null, null).size(), "DONE 删除后默认查询同样排除");
    }

    @Test
    @DisplayName("完成：非终态可转 DONE；对 CANCELLED 完成 400")
    void completeRules() {
        ScheduleVO vo = scheduleService.create(UID, createDTO("要完成的", "2026-11-26 09:00:00"), "MANUAL");
        scheduleService.complete(UID, vo.getId());
        assertEquals("DONE", scheduleItemMapper.selectById(vo.getId()).getStatus());
        assertThrows(BusinessException.class, () -> scheduleService.complete(UID, vo.getId()));

        ScheduleVO cancelled = scheduleService.create(UID, createDTO("取消的", "2026-11-28 09:00:00"), "MANUAL");
        scheduleService.delete(UID, cancelled.getId());
        assertThrows(BusinessException.class, () -> scheduleService.complete(UID, cancelled.getId()));
    }

    @Test
    @DisplayName("恢复（契约 §3.9）：DONE → PENDING；非 DONE 400；他人 404")
    void reopenRules() {
        ScheduleVO vo = scheduleService.create(UID, createDTO("误点完成的", "2026-11-26 09:00:00"), "MANUAL");
        scheduleService.complete(UID, vo.getId());
        scheduleService.reopen(UID, vo.getId());
        assertEquals(ScheduleItem.STATUS_PENDING, scheduleItemMapper.selectById(vo.getId()).getStatus(),
            "reopen 后应回到 PENDING（未过期的 remind_at 照常提醒）");

        // 非 DONE 状态 reopen → 400
        ScheduleVO pending = scheduleService.create(UID, createDTO("进行中的", "2026-11-27 09:00:00"), "MANUAL");
        assertThrows(BusinessException.class, () -> scheduleService.reopen(UID, pending.getId()));

        // 他人 reopen → 404
        assertThrows(BusinessException.class, () -> scheduleService.reopen(999L, vo.getId()));
    }

    @Test
    @DisplayName("一键清理（契约 §3.4a）：仅过期的 FAILED→CANCELLED；DONE 与未来的 FAILED 不动")
    void cleanupRules() {
        // 日期相对化：硬编码日期会被跨天自然击穿（11-26 的"未来"在 11-28 已是过去）
        String pastDay = java.time.LocalDate.now().minusDays(3).toString();
        String futureDay = java.time.LocalDate.now().plusDays(2).toString();
        String pastDay2 = java.time.LocalDate.now().minusDays(2).toString();
        ScheduleVO failedPast = scheduleService.create(UID, createDTO("过期的失败项", pastDay + " 09:00:00"), "MANUAL");
        ScheduleVO failedFuture = scheduleService.create(UID, createDTO("未来的失败项", futureDay + " 09:00:00"), "MANUAL");
        ScheduleVO donePast = scheduleService.create(UID, createDTO("过期的完成项", pastDay2 + " 09:00:00"), "MANUAL");
        for (ScheduleVO v : List.of(failedFuture)) { /* future FAILED */ }
        ScheduleItem f1 = scheduleItemMapper.selectById(failedPast.getId());
        f1.setStatus(ScheduleItem.STATUS_FAILED);
        scheduleItemMapper.updateById(f1);
        ScheduleItem f2 = scheduleItemMapper.selectById(failedFuture.getId());
        f2.setStatus(ScheduleItem.STATUS_FAILED);
        scheduleItemMapper.updateById(f2);
        ScheduleItem d1 = scheduleItemMapper.selectById(donePast.getId());
        d1.setStatus(ScheduleItem.STATUS_DONE);
        scheduleItemMapper.updateById(d1);

        int cleaned = scheduleService.cleanup(UID);
        assertEquals(1, cleaned, "只有过期的 FAILED 会被清理");
        assertEquals(ScheduleItem.STATUS_CANCELLED,
                scheduleItemMapper.selectById(failedPast.getId()).getStatus());
        assertEquals(ScheduleItem.STATUS_FAILED,
                scheduleItemMapper.selectById(failedFuture.getId()).getStatus());
        assertEquals(ScheduleItem.STATUS_DONE,
                scheduleItemMapper.selectById(donePast.getId()).getStatus(), "DONE 是受保护历史");
    }

    @Test
    @DisplayName("查询：from/to 按 start_time 过滤")
    void listFilters() {
        scheduleService.create(UID, createDTO("11月", "2026-11-26 09:00:00"), "MANUAL");
        scheduleService.create(UID, createDTO("12月", "2026-12-10 09:00:00"), "MANUAL");
        List<ScheduleVO> nov = scheduleService.list(UID,
                java.time.LocalDate.parse("2026-11-01"), java.time.LocalDate.parse("2026-11-30"), null);
        assertEquals(1, nov.size());
        assertEquals("11月", nov.get(0).getTitle());
    }
}
