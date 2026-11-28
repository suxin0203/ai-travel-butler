package com.suxin.travel.traveljava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日程项：提醒扫描的领域对象。
 * 关键约定：remind_at 是派生字段 = start_time - remind_minutes_before，
 * 修改任一因子必须重算，且原状态为 PENDING/DISPATCHED 时重置回 PENDING。
 */
@Data
@TableName("schedule_item")
public class ScheduleItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 来源行程，手工创建为 null */
    private Long tripId;
    private String title;
    /** SPOT / FOOD / TRANSPORT / TODO / DEPART */
    private String type;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String location;
    private String note;
    private Integer remindMinutesBefore;
    /** 派生字段：start_time - remind_minutes_before */
    private LocalDateTime remindAt;
    // remind_enabled 列已随契约 v2.10 删除：该字段从未有入口可置 false（恒 DB 默认 true），
    // 「关单项提醒」实际不可达，属误导性死字段——关闭提醒的正确方式=删除或改期日程
    /** PENDING / DISPATCHED / SENT / FAILED / CANCELLED / DONE */
    private String status;
    /** AI / MANUAL / TRIP */
    private String source;
    private LocalDateTime createdAt;
    /**
     * 行更新时间（DB ON UPDATE CURRENT_TIMESTAMP 自动维护，应用层不显式写）：
     * DISPATCHED 卡死兜底扫描的时间判据——CAS/回滚/重置都会刷新它（稳健性轮 v2.10）
     */
    private LocalDateTime updatedAt;

    /** 状态常量：集中定义避免魔法值散落 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_DONE = "DONE";
}
