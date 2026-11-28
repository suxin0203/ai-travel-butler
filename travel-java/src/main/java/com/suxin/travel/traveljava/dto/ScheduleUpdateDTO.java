package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修改日程请求（API 契约 §3.6 部分更新语义）。
 * 全部字段可空：null = 不提供（保持原值）；location/note 传空串 = 清空；
 * title 传空串由 service 拒绝；endTime/remindMinutesBefore 不支持清空。
 * 注意 @NotBlank 换成了 @Size(min=1)：空串要进入 service 层做"清空/拒绝"的语义判断。
 */
@Data
public class ScheduleUpdateDTO {

    @Size(min = 1, max = 100, message = "标题须为 1~100 字符")
    private String title;

    private String type;

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
