package com.suxin.travel.traveljava.vo;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import lombok.Data;

import java.time.LocalDateTime;

/** 日程响应结构（API 契约 §3.4），由实体转换，屏蔽多余字段 */
@Data
public class ScheduleVO {
    private Long id;
    private Long tripId;
    private String title;
    private String type;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String location;
    private String note;
    private Integer remindMinutesBefore;
    private LocalDateTime remindAt;
    private String status;
    private String source;

    public static ScheduleVO from(ScheduleItem item) {
        ScheduleVO vo = new ScheduleVO();
        vo.setId(item.getId());
        vo.setTripId(item.getTripId());
        vo.setTitle(item.getTitle());
        vo.setType(item.getType());
        vo.setStartTime(item.getStartTime());
        vo.setEndTime(item.getEndTime());
        vo.setLocation(item.getLocation());
        vo.setNote(item.getNote());
        vo.setRemindMinutesBefore(item.getRemindMinutesBefore());
        vo.setRemindAt(item.getRemindAt());
        vo.setStatus(item.getStatus());
        vo.setSource(item.getSource());
        return vo;
    }
}
