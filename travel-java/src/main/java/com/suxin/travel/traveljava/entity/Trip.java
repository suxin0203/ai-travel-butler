package com.suxin.travel.traveljava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 行程：推荐结果落库；start_date 是日程日期与行前提醒的锚点 */
@Data
@TableName("trip")
public class Trip {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String city;
    private Integer days;
    private BigDecimal budget;
    /** 出发日期：第 d 天日程日期 = start_date + (d-1) */
    private LocalDate startDate;
    /** DRAFT / CONFIRMED / FINISHED */
    private String status;
    /** 模型原始推荐 JSON，转日程的数据来源 */
    private String recommendJson;
    private LocalDateTime createdAt;
}
