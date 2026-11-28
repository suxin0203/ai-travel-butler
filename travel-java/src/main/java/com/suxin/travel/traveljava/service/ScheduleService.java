package com.suxin.travel.traveljava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.dto.ScheduleCreateDTO;
import com.suxin.travel.traveljava.dto.ScheduleUpdateDTO;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import com.suxin.travel.traveljava.vo.ScheduleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日程服务：CRUD + 越权防护 + remind_at 派生/重置联动。
 * 两个铁律：
 * 1. 所有按 id 的查询都带 user_id 条件——横向越权（用户 A 操作用户 B 的日程）在此统一拦截，
 *    且对外一律 404，不暴露"存在但不是你的"这一信息；
 * 2. remind_at 恒等 start_time - remind_minutes_before，修改任一因子必须重算，
 *    非终态状态一律重置回 PENDING（新时间需要重新提醒）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleItemMapper scheduleItemMapper;

    /** 允许的提醒状态值（契约 §3.4 枚举表），查询参数与内部状态迁移共用 */
    public static final java.util.Set<String> STATUSES = java.util.Set.of(
            ScheduleItem.STATUS_PENDING, ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_SENT,
            ScheduleItem.STATUS_FAILED, ScheduleItem.STATUS_CANCELLED, ScheduleItem.STATUS_DONE);

    /**
     * 字段级校验（controller 的 @Valid 与对话工具路径共用这一个入口）。
     * 为什么放 service：模型发起的 create_schedule 不经过 @Valid，
     * 校验若只留在 controller，模型参数会绕过约束直插数据库。
     */
    private void validateFields(String title, String type, Integer minutes, String location, String note) {
        if (title == null || title.isBlank()) {
            throw BusinessException.badRequest("日程标题不能为空");
        }
        if (title.length() > 100) {
            throw BusinessException.badRequest("标题不能超过 100 字符");
        }
        if (minutes != null && (minutes < 0 || minutes > 1440)) {
            throw BusinessException.badRequest("提前提醒须在 0~1440 分钟之间");
        }
        if (location != null && location.length() > 200) {
            throw BusinessException.badRequest("地点不能超过 200 字符");
        }
        if (note != null && note.length() > 500) {
            throw BusinessException.badRequest("备注不能超过 500 字符");
        }
    }

    /** 创建（source 由调用方指定：页面 MANUAL / 对话 AI / 行程 TRIP） */
    public ScheduleVO create(long uid, ScheduleCreateDTO dto, String source) {
        String type = dto.getType() == null || dto.getType().isBlank() ? "TODO" : dto.getType();
        if (!ScheduleCreateDTO.TYPES.contains(type)) {
            throw BusinessException.badRequest("日程类型不合法：" + type);
        }
        validateFields(dto.getTitle(), type, dto.getRemindMinutesBefore(), dto.getLocation(), dto.getNote());
        if (dto.getStartTime() == null) {
            throw BusinessException.badRequest("开始时间不能为空");
        }
        int minutes = dto.getRemindMinutesBefore() == null ? 30 : dto.getRemindMinutesBefore();

        ScheduleItem item = new ScheduleItem();
        item.setUserId(uid);
        item.setTitle(dto.getTitle());
        item.setType(type);
        item.setStartTime(dto.getStartTime());
        item.setEndTime(dto.getEndTime());
        item.setLocation(dto.getLocation());
        item.setNote(dto.getNote());
        item.setRemindMinutesBefore(minutes);
        item.setSource(source);
        // 派生字段：remind_at = start_time - remind_minutes_before
        item.setRemindAt(dto.getStartTime().minusMinutes(minutes));
        item.setStatus(ScheduleItem.STATUS_PENDING);
        validateEndAfterStart(item);
        scheduleItemMapper.insert(item);
        return ScheduleVO.from(item);
    }

    /** 按契约 §3.6 的部分更新语义修改 */
    public ScheduleVO update(long uid, long id, ScheduleUpdateDTO dto) {
        ScheduleItem item = requireOwned(uid, id);
        if (ScheduleItem.STATUS_CANCELLED.equals(item.getStatus())
                || ScheduleItem.STATUS_DONE.equals(item.getStatus())) {
            throw BusinessException.badRequest("已取消或已完成的日程不能修改");
        }
        if (dto.getTitle() != null) {
            if (dto.getTitle().isBlank()) {
                throw BusinessException.badRequest("标题不能清空");
            }
            item.setTitle(dto.getTitle());
        }
        if (dto.getType() != null) {
            if (!ScheduleCreateDTO.TYPES.contains(dto.getType())) {
                throw BusinessException.badRequest("日程类型不合法：" + dto.getType());
            }
            item.setType(dto.getType());
        }
        if (dto.getStartTime() != null) {
            item.setStartTime(dto.getStartTime());
        }
        if (dto.getEndTime() != null) {
            item.setEndTime(dto.getEndTime());
        }
        if (dto.getLocation() != null) {
            // 契约：空串 = 清空
            item.setLocation(dto.getLocation().isEmpty() ? null : dto.getLocation());
        }
        if (dto.getNote() != null) {
            item.setNote(dto.getNote().isEmpty() ? null : dto.getNote());
        }
        if (dto.getRemindMinutesBefore() != null) {
            item.setRemindMinutesBefore(dto.getRemindMinutesBefore());
        }
        validateFields(item.getTitle(), item.getType(), item.getRemindMinutesBefore(),
                item.getLocation(), item.getNote());
        validateEndAfterStart(item);
        // 联动规则：重算 remind_at；非终态一律重置回 PENDING（新时间要重新提醒）
        item.setRemindAt(item.getStartTime().minusMinutes(item.getRemindMinutesBefore()));
        item.setStatus(ScheduleItem.STATUS_PENDING);
        scheduleItemMapper.updateById(item);
        return ScheduleVO.from(item);
    }

    /**
     * 逻辑删（CANCELLED）。幂等：已取消的重复删仍返回成功。
     * v2.8 起放开 DONE 可删（用户拍板：页面要清爽，完成态的历史不该是删不掉的垃圾；
     * 历史回看由「历史日程」折叠承担，用户可自行清理）。对话工具与前端共用。
     */
    public void delete(long uid, long id) {
        ScheduleItem item = requireOwned(uid, id);
        if (!ScheduleItem.STATUS_CANCELLED.equals(item.getStatus())) {
            item.setStatus(ScheduleItem.STATUS_CANCELLED);
            scheduleItemMapper.updateById(item);
        }
    }

    /** 完成：PENDING/DISPATCHED/SENT/FAILED → DONE；已取消/已完成均 400（契约 §3.8） */
    public void complete(long uid, long id) {
        ScheduleItem item = requireOwned(uid, id);
        if (ScheduleItem.STATUS_CANCELLED.equals(item.getStatus())) {
            throw BusinessException.badRequest("已取消的日程不能标记完成");
        }
        if (ScheduleItem.STATUS_DONE.equals(item.getStatus())) {
            throw BusinessException.badRequest("该日程已完成，无需重复操作");
        }
        item.setStatus(ScheduleItem.STATUS_DONE);
        scheduleItemMapper.updateById(item);
    }

    /**
     * 恢复（撤销误操作完成）：仅 DONE → PENDING（契约 §3.9）。
     * remind_at 不变：未来的到点照常提醒；已过期的由扫描器"宽限窗"规则置 FAILED(MISSED)，不补发。
     */
    public void reopen(long uid, long id) {
        ScheduleItem item = requireOwned(uid, id);
        if (!ScheduleItem.STATUS_DONE.equals(item.getStatus())) {
            throw BusinessException.badRequest("只有已完成的日程可以恢复");
        }
        item.setStatus(ScheduleItem.STATUS_PENDING);
        scheduleItemMapper.updateById(item);
    }

    /** 一键清理（契约 §3.4a）：仅 FAILED 且已开始的日程批量置 CANCELLED（DONE 是受保护历史，不动） */
    public int cleanup(long uid) {
        return scheduleItemMapper.update(null, Wrappers.<ScheduleItem>lambdaUpdate()
                .eq(ScheduleItem::getUserId, uid)
                .eq(ScheduleItem::getStatus, ScheduleItem.STATUS_FAILED)
                .lt(ScheduleItem::getStartTime, LocalDate.now().atStartOfDay())
                .set(ScheduleItem::getStatus, ScheduleItem.STATUS_CANCELLED));
    }

    /** 查询：默认排除 CANCELLED；from/to 按 start_time 过滤；status 须在枚举表内（契约 §3.4） */
    public List<ScheduleVO> list(long uid, LocalDate from, LocalDate to, String status) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw BusinessException.badRequest("非法状态值：" + status);
        }
        var wrapper = Wrappers.<ScheduleItem>lambdaQuery().eq(ScheduleItem::getUserId, uid);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ScheduleItem::getStatus, status);
        } else {
            wrapper.ne(ScheduleItem::getStatus, ScheduleItem.STATUS_CANCELLED);
        }
        if (from != null) {
            wrapper.ge(ScheduleItem::getStartTime, from.atStartOfDay());
        }
        if (to != null) {
            wrapper.le(ScheduleItem::getStartTime, to.atTime(23, 59, 59));
        }
        wrapper.orderByAsc(ScheduleItem::getStartTime);
        return scheduleItemMapper.selectList(wrapper).stream().map(ScheduleVO::from).toList();
    }

    /** 供对话工具 query_schedules 使用：按日期或关键词查询，最多返回 20 条摘要 */
    public List<ScheduleVO> queryForTool(long uid, LocalDate date, String keyword) {
        var wrapper = Wrappers.<ScheduleItem>lambdaQuery()
                .eq(ScheduleItem::getUserId, uid)
                .ne(ScheduleItem::getStatus, ScheduleItem.STATUS_CANCELLED);
        if (date != null) {
            wrapper.ge(ScheduleItem::getStartTime, date.atStartOfDay())
                    .le(ScheduleItem::getStartTime, date.atTime(23, 59, 59));
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ScheduleItem::getTitle, keyword.trim());
        }
        wrapper.last("LIMIT 20");
        return scheduleItemMapper.selectList(wrapper).stream().map(ScheduleVO::from).toList();
    }

    /** 越权防护统一入口：查不到 = 不存在或非本人，一律 404 */
    private ScheduleItem requireOwned(long uid, long id) {
        ScheduleItem item = scheduleItemMapper.selectOne(Wrappers.<ScheduleItem>lambdaQuery()
                .eq(ScheduleItem::getId, id)
                .eq(ScheduleItem::getUserId, uid));
        if (item == null) {
            throw BusinessException.notFound();
        }
        return item;
    }

    private void validateEndAfterStart(ScheduleItem item) {
        if (item.getEndTime() != null && !item.getEndTime().isAfter(item.getStartTime())) {
            throw BusinessException.badRequest("结束时间必须晚于开始时间");
        }
    }
}
