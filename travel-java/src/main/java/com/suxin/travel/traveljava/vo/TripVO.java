package com.suxin.travel.traveljava.vo;

import com.suxin.travel.traveljava.entity.Trip;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 行程（契约 §4.2） */
@Data
public class TripVO {
    private Long id;
    private String city;
    private Integer days;
    private BigDecimal budget;
    private LocalDate startDate;
    private String status;
    private LocalDateTime createdAt;

    public static TripVO from(Trip t) {
        TripVO vo = new TripVO();
        vo.setId(t.getId());
        vo.setCity(t.getCity());
        vo.setDays(t.getDays());
        vo.setBudget(t.getBudget());
        vo.setStartDate(t.getStartDate());
        vo.setStatus(t.getStatus());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
