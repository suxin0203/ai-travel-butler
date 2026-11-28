package com.suxin.travel.traveljava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 提醒发送记录：每个渠道一条，排查与补偿依据 */
@Data
@TableName("reminder_log")
public class ReminderLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scheduleItemId;
    /** SSE / MAIL / WECHAT */
    private String channel;
    /** SUCCESS / FAIL */
    private String status;
    private String detail;
    private LocalDateTime createdAt;
}
