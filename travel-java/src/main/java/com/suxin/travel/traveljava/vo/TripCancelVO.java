package com.suxin.travel.traveljava.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 删除/取消行程响应（契约 §4.3a） */
@Data
@AllArgsConstructor
public class TripCancelVO {
    private Long tripId;
    /** 级联取消的未触发日程数量 */
    private Integer cancelledSchedules;
}
