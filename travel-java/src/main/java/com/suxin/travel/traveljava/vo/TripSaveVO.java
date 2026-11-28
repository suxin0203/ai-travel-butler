package com.suxin.travel.traveljava.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 保存行程响应（契约 §4.1） */
@Data
@AllArgsConstructor
public class TripSaveVO {
    private Long tripId;
    private Integer scheduleCount;
    private TripVO trip;
}
