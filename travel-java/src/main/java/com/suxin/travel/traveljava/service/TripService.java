package com.suxin.travel.traveljava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.dto.TripSaveDTO;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.Trip;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import com.suxin.travel.traveljava.mapper.TripMapper;
import com.suxin.travel.traveljava.vo.ScheduleVO;
import com.suxin.travel.traveljava.vo.TravelRecommendVO;
import com.suxin.travel.traveljava.vo.TripConfirmVO;
import com.suxin.travel.traveljava.vo.TripDetailVO;
import com.suxin.travel.traveljava.vo.TripSaveVO;
import com.suxin.travel.traveljava.vo.TripVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 行程服务：推荐结果一键转日程（M3 难点三的数据入口）。
 * 生成规则（契约 §4.1 v2.1）：仅按 dailyItineraries 数组顺序映射（忽略 day 字段），
 * 第 i 个元素映射 startDate+i，三时段固定 09:30/14:00/19:00，spot trim 非空才生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripService {

    /** 三时段固定映射：上午 09:30 / 下午 14:00 / 晚上 19:00 */
    private static final List<SlotRule> SLOT_RULES = List.of(
            new SlotRule("morning", LocalTime.of(9, 30)),
            new SlotRule("afternoon", LocalTime.of(14, 0)),
            new SlotRule("evening", LocalTime.of(19, 0)));

    private record SlotRule(String field, LocalTime time) {
    }

    private final TripMapper tripMapper;
    private final ScheduleItemMapper scheduleItemMapper;
    private final ObjectMapper objectMapper;

    /**
     * 保存行程并生成日程。
     * 先把全部日程项在内存里构造完并校验（数量>0、标题长度），再开事务统一落库：
     * 校验失败什么都不留；中途插入失败整体回滚，不会留下孤儿 trip + 半截日程。
     */
    @Transactional
    public TripSaveVO save(long uid, TripSaveDTO dto) {
        if (dto.getStartDate().isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("出发日期不能早于今天");
        }
        // 内外层一致性：前端必须原样回传推荐结果，防错拿别单数据
        var rec = dto.getRecommend();
        if (!dto.getCity().equals(rec.getCity()) || !dto.getDays().equals(rec.getDays())
                || doubleCompare(dto.getBudget(), rec.getTotalBudget())) {
            throw BusinessException.badRequest("推荐结果与行程参数不一致");
        }
        if (rec.getDailyItineraries() == null || rec.getDailyItineraries().isEmpty()) {
            throw BusinessException.badRequest("推荐结果不含可生成日程");
        }

        // 第一步：内存构造全部日程项（不碰库）
        List<ScheduleItem> items = new ArrayList<>();
        for (int i = 0; i < rec.getDailyItineraries().size(); i++) {
            var day = rec.getDailyItineraries().get(i);
            LocalDate date = dto.getStartDate().plusDays(i); // 数组顺序映射，忽略 day 字段
            for (SlotRule rule : SLOT_RULES) {
                var slot = slotOf(day, rule.field());
                if (slot == null || slot.getSpot() == null || slot.getSpot().isBlank()) {
                    continue; // spot trim 后非空才生成
                }
                ScheduleItem item = new ScheduleItem();
                item.setUserId(uid);
                item.setTitle(slot.getSpot().trim());
                // LLM 输出的景点名可能超列长，截断兜底（不因 101 个字符毁掉整个行程）
                if (item.getTitle().length() > 100) {
                    item.setTitle(item.getTitle().substring(0, 100));
                }
                item.setType("SPOT");
                item.setStartTime(LocalDateTime.of(date, rule.time()));
                item.setNote(slot.getDescription());
                item.setRemindMinutesBefore(30);
                item.setRemindAt(item.getStartTime().minusMinutes(30));
                item.setStatus(ScheduleItem.STATUS_PENDING);
                item.setSource("TRIP");
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            // 任何库都没写过：契约要求此时 400 且不产生孤儿数据
            throw BusinessException.badRequest("推荐结果不含可生成日程");
        }

        // 第二步：同一事务落库
        Trip trip = new Trip();
        trip.setUserId(uid);
        trip.setCity(dto.getCity());
        trip.setDays(dto.getDays());
        trip.setBudget(java.math.BigDecimal.valueOf(dto.getBudget()));
        trip.setStartDate(dto.getStartDate());
        trip.setStatus("DRAFT");
        try {
            trip.setRecommendJson(objectMapper.writeValueAsString(rec));
        } catch (Exception e) {
            throw BusinessException.internal("行程数据序列化失败");
        }
        tripMapper.insert(trip);
        for (ScheduleItem item : items) {
            item.setTripId(trip.getId());
            scheduleItemMapper.insert(item);
        }
        return new TripSaveVO(trip.getId(), items.size(), TripVO.from(trip));
    }

    public List<TripVO> list(long uid) {
        // 默认排除已取消（逻辑删）的行程
        return tripMapper.selectList(Wrappers.<Trip>lambdaQuery()
                        .eq(Trip::getUserId, uid)
                        .ne(Trip::getStatus, "CANCELLED")
                        .orderByDesc(Trip::getCreatedAt))
                .stream().map(TripVO::from).toList();
    }

    public TripDetailVO detail(long uid, long id) {
        // CANCELLED 行程视为已删除：404（契约 §4.3a，与日程逻辑删语义一致）
        Trip trip = tripMapper.selectOne(Wrappers.<Trip>lambdaQuery()
                .eq(Trip::getId, id).eq(Trip::getUserId, uid)
                .ne(Trip::getStatus, "CANCELLED"));
        if (trip == null) {
            throw BusinessException.notFound();
        }
        // 与日程查询接口同一默认语义：排除已取消（逻辑删）的日程
        List<ScheduleVO> schedules = scheduleItemMapper.selectList(Wrappers.<ScheduleItem>lambdaQuery()
                        .eq(ScheduleItem::getTripId, id)
                        .eq(ScheduleItem::getUserId, uid)
                        .ne(ScheduleItem::getStatus, ScheduleItem.STATUS_CANCELLED)
                        .orderByAsc(ScheduleItem::getStartTime))
                .stream().map(ScheduleVO::from).toList();
        return new TripDetailVO(TripVO.from(trip), schedules);
    }

    /** 确认行程：CAS DRAFT→CONFIRMED（并发双击只成功一次），并生成 DEPART 行前提醒 */
    @Transactional
    public TripConfirmVO confirm(long uid, long id) {
        requireOwned(uid, id);
        // CAS：只有 DRAFT 能推进；并发落败方影响行数=0 → 统一 400（契约 §4.3）
        int updated = tripMapper.update(null, Wrappers.<Trip>lambdaUpdate()
                .eq(Trip::getId, id).eq(Trip::getUserId, uid).eq(Trip::getStatus, "DRAFT")
                .set(Trip::getStatus, "CONFIRMED"));
        if (updated == 0) {
            throw BusinessException.badRequest("行程已确认");
        }
        Trip trip = tripMapper.selectById(id);

        // 行前提醒：start_time=出发日 00:00，提前 240 分钟 = 出发前一天 20:00
        ScheduleItem depart = new ScheduleItem();
        depart.setUserId(uid);
        depart.setTripId(id);
        depart.setTitle("出发：" + trip.getCity());
        depart.setType("DEPART");
        depart.setStartTime(trip.getStartDate().atStartOfDay());
        depart.setRemindMinutesBefore(240);
        depart.setRemindAt(trip.getStartDate().atStartOfDay().minusMinutes(240));
        depart.setSource("TRIP");
        if (!depart.getRemindAt().isAfter(LocalDateTime.now())) {
            // 出发日就是今天：提醒时刻已过去，按契约置 FAILED 不补发
            depart.setStatus(ScheduleItem.STATUS_FAILED);
            log.warn("出发日过近，行前提醒直接置 FAILED tripId={} uid={}", id, uid);
        } else {
            depart.setStatus(ScheduleItem.STATUS_PENDING);
        }
        scheduleItemMapper.insert(depart);
        return new TripConfirmVO(id, "CONFIRMED", depart.getId());
    }

    /**
     * 删除/取消行程（契约 §4.3a）：DRAFT/CONFIRMED → CANCELLED，
     * 级联取消未触发日程（单条 UPDATE 防扫描器交错）；已 CANCELLED 幂等返回 0；
     * FINISHED 为预留终态，删除返回 400。删除后列表/详情默认排除（逻辑删）。
     */
    @Transactional
    public com.suxin.travel.traveljava.vo.TripCancelVO cancelTrip(long uid, long id) {
        Trip trip = requireOwned(uid, id);
        if ("FINISHED".equals(trip.getStatus())) {
            throw BusinessException.badRequest("已结束的行程不能删除");
        }
        if ("CANCELLED".equals(trip.getStatus())) {
            return new com.suxin.travel.traveljava.vo.TripCancelVO(id, 0); // 幂等
        }
        // CAS：防与 confirm 竞态（并发确认/删除只成功一方）
        int updated = tripMapper.update(null, Wrappers.<Trip>lambdaUpdate()
                .eq(Trip::getId, id).eq(Trip::getUserId, uid)
                .in(Trip::getStatus, "DRAFT", "CONFIRMED")
                .set(Trip::getStatus, "CANCELLED"));
        if (updated == 0) {
            throw BusinessException.badRequest("行程状态已变化，请刷新后重试");
        }
        int cancelledSchedules = scheduleItemMapper.cancelUntriggeredByTrip(id, uid);
        return new com.suxin.travel.traveljava.vo.TripCancelVO(id, cancelledSchedules);
    }

    private boolean doubleCompare(Double a, Double b) {
        return a == null || b == null || Math.abs(a - b) > 0.001;
    }

    private TravelRecommendVO.TimeSlot slotOf(TravelRecommendVO.DailyItinerary day, String field) {
        return switch (field) {
            case "morning" -> day.getMorning();
            case "afternoon" -> day.getAfternoon();
            case "evening" -> day.getEvening();
            default -> null;
        };
    }

    private Trip requireOwned(long uid, long id) {
        Trip trip = tripMapper.selectOne(Wrappers.<Trip>lambdaQuery()
                .eq(Trip::getId, id).eq(Trip::getUserId, uid));
        if (trip == null) {
            throw BusinessException.notFound();
        }
        return trip;
    }
}
