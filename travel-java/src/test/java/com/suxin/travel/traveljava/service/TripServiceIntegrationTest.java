package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.dto.TripSaveDTO;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.Trip;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import com.suxin.travel.traveljava.mapper.TripMapper;
import com.suxin.travel.traveljava.vo.TravelRecommendVO;
import com.suxin.travel.traveljava.vo.TripConfirmVO;
import com.suxin.travel.traveljava.vo.TripSaveVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 行程转日程集成测试：契约 §4.1~§4.3 的全部生成规则与校验 */
@SpringBootTest
@ActiveProfiles("test")
class TripServiceIntegrationTest {

    @Autowired
    private TripService tripService;
    @Autowired
    private TripMapper tripMapper;
    @Autowired
    private ScheduleItemMapper scheduleItemMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long UID = 401L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM schedule_item");
        jdbcTemplate.update("DELETE FROM trip");
    }

    private TripSaveDTO dto(LocalDate startDate) {
        TripSaveDTO dto = new TripSaveDTO();
        dto.setCity("北京");
        dto.setDays(2);
        dto.setBudget(3000.0);
        dto.setStartDate(startDate);
        dto.setRecommend(recommend());
        return dto;
    }

    private TravelRecommendVO recommend() {
        TravelRecommendVO vo = new TravelRecommendVO();
        vo.setCity("北京");
        vo.setDays(2);
        vo.setTotalBudget(3000.0);
        TravelRecommendVO.DailyItinerary d1 = new TravelRecommendVO.DailyItinerary();
        d1.setDay(1);
        d1.setMorning(slot("天安门广场", "早起看升旗"));
        d1.setAfternoon(slot("   ", "空白时段应跳过"));
        d1.setEvening(slot("王府井大街", "夜市小吃"));
        TravelRecommendVO.DailyItinerary d2 = new TravelRecommendVO.DailyItinerary();
        d2.setDay(99); // day 字段乱写，生成必须按数组顺序而非 day 值
        d2.setMorning(slot("颐和园", "皇家园林"));
        d2.setAfternoon(slot(null, "空时段"));
        vo.setDailyItineraries(List.of(d1, d2));
        return vo;
    }

    private TravelRecommendVO.TimeSlot slot(String spot, String desc) {
        TravelRecommendVO.TimeSlot s = new TravelRecommendVO.TimeSlot();
        s.setSpot(spot);
        s.setDescription(desc);
        return s;
    }

    @Test
    @DisplayName("转日程：按数组序映射日期、三时段固定时间、空白/空 spot 跳过")
    void saveGeneratesSchedules() {
        LocalDate start = LocalDate.now().plusDays(3);
        TripSaveVO vo = tripService.save(UID, dto(start));

        assertEquals(3, vo.getScheduleCount(), "4 个非空 spot（空白串跳过、null 跳过）");
        List<ScheduleItem> items = scheduleItemMapper.selectList(null);
        assertEquals(3, items.size());
        // 第 0 个元素 → startDate，morning 09:30
        assertTrue(items.stream().anyMatch(i -> "天安门广场".equals(i.getTitle())
                && i.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .equals(start.format(DATE) + " 09:30")));
        // 第 1 个元素（day=99 被忽略）→ startDate+1
        assertTrue(items.stream().anyMatch(i -> "颐和园".equals(i.getTitle())
                && i.getStartTime().toLocalDate().equals(start.plusDays(1))));
        // 全部 source=TRIP、PENDING、提前 30 分钟
        assertTrue(items.stream().allMatch(i -> "TRIP".equals(i.getSource())
                && "PENDING".equals(i.getStatus())
                && i.getRemindAt().equals(i.getStartTime().minusMinutes(30))));
        // trip 落库
        Trip trip = tripMapper.selectList(null).get(0);
        assertEquals("DRAFT", trip.getStatus());
        assertEquals(start, trip.getStartDate());
    }

    @Test
    @DisplayName("校验：出发日期早于今天 400；内外层不一致 400；无有效时段 400")
    void saveValidations() {
        assertThrows(BusinessException.class, () -> tripService.save(UID, dto(LocalDate.now().minusDays(1))));

        TripSaveDTO mismatch = dto(LocalDate.now().plusDays(3));
        mismatch.setCity("上海"); // 与 recommend 内层不一致
        assertThrows(BusinessException.class, () -> tripService.save(UID, mismatch));

        TripSaveDTO empty = dto(LocalDate.now().plusDays(3));
        TravelRecommendVO vo = empty.getRecommend();
        vo.getDailyItineraries().forEach(d -> {
            d.setMorning(slot(null, null));
            d.setAfternoon(slot(null, null));
            d.setEvening(slot("  ", null));
        });
        assertThrows(BusinessException.class, () -> tripService.save(UID, empty));
    }

    @Test
    @DisplayName("确认：DEPART 行前提醒 = 出发前一天 20:00；重复确认 400；非本人 404")
    void confirmRules() {
        LocalDate start = LocalDate.now().plusDays(5);
        TripSaveVO saved = tripService.save(UID, dto(start));

        TripConfirmVO confirmed = tripService.confirm(UID, saved.getTripId());
        assertEquals("CONFIRMED", confirmed.getStatus());
        ScheduleItem depart = scheduleItemMapper.selectById(confirmed.getDepartReminderId());
        assertEquals("DEPART", depart.getType());
        assertEquals(start.minusDays(1).atTime(20, 0), depart.getRemindAt());
        assertEquals("PENDING", depart.getStatus());

        assertThrows(BusinessException.class, () -> tripService.confirm(UID, saved.getTripId()));
        assertThrows(BusinessException.class, () -> tripService.confirm(999L, saved.getTripId()));
    }

    @Test
    @DisplayName("取消行程（契约 §4.3a）：trip→CANCELLED、未触发日程级联取消、幂等、详情404")
    void cancelTripRules() {
        LocalDate start = LocalDate.now().plusDays(4);
        TripSaveVO saved = tripService.save(UID, dto(start));
        tripService.confirm(UID, saved.getTripId());

        var vo = tripService.cancelTrip(UID, saved.getTripId());
        assertTrue(vo.getCancelledSchedules() >= 4, "未触发日程应被级联取消");
        assertEquals("CANCELLED", tripMapper.selectById(saved.getTripId()).getStatus());

        // 幂等：重复删除返回 0
        var again = tripService.cancelTrip(UID, saved.getTripId());
        assertEquals(0, again.getCancelledSchedules());

        // 删除后详情 404、列表不显示
        assertThrows(BusinessException.class, () -> tripService.detail(UID, saved.getTripId()));
        assertEquals(0, tripService.list(UID).size());

        // FINISHED → 400（预留终态）
        jdbcTemplate.update("UPDATE trip SET status='FINISHED' WHERE id=?", saved.getTripId());
        assertThrows(BusinessException.class, () -> tripService.cancelTrip(UID, saved.getTripId()));
    }

    @Test
    @DisplayName("出发日就是今天：DEPART 直接 FAILED 不补发（契约 §4.3）")
    void confirmWithPastDepartReminder() {
        TripSaveDTO today = dto(LocalDate.now());
        TripSaveVO saved = tripService.save(UID, today);
        TripConfirmVO confirmed = tripService.confirm(UID, saved.getTripId());
        ScheduleItem depart = scheduleItemMapper.selectById(confirmed.getDepartReminderId());
        assertEquals(ScheduleItem.STATUS_FAILED, depart.getStatus());
    }
}
