package com.suxin.travel.traveljava.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 确认行程响应（契约 §4.3） */
@Data
@AllArgsConstructor
public class TripConfirmVO {
    private Long tripId;
    private String status;
    private Long departReminderId;
}
