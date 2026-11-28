package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** 新建日程请求（API 契约 §3.5），对话工具与前端页面共用 */
@Data
public class ScheduleCreateDTO {

    public static final java.util.Set<String> TYPES =
            java.util.Set.of("SPOT", "FOOD", "TRANSPORT", "TODO", "DEPART");

    @NotBlank(message = "日程标题不能为空")
    @Size(max = 100, message = "标题不能超过 100 字符")
    private String title;

    /** 缺省 TODO */
    private String type;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Size(max = 200, message = "地点不能超过 200 字符")
    private String location;

    @Size(max = 500, message = "备注不能超过 500 字符")
    private String note;

    @Min(value = 0, message = "提前提醒不能小于 0 分钟")
    @Max(value = 1440, message = "提前提醒不能超过 1440 分钟")
    private Integer remindMinutesBefore;
}
