package com.suxin.travel.traveljava.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 行程详情（契约 §4.2）：行程 + 其下日程（按 startTime 正序） */
@Data
@AllArgsConstructor
public class TripDetailVO {
    private TripVO trip;
    private List<ScheduleVO> schedules;
}
