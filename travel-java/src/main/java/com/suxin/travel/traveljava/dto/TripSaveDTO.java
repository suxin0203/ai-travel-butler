package com.suxin.travel.traveljava.dto;

import com.suxin.travel.traveljava.vo.TravelRecommendVO;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 保存行程请求（契约 §4.1）：推荐结果原样回传 + 出发日期 */
@Data
public class TripSaveDTO {

    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市名不能超过 50 字符")
    private String city;

    @NotNull(message = "天数不能为空")
    @Min(value = 1, message = "天数不能少于1")
    @Max(value = 30, message = "天数不能超过30")
    private Integer days;

    @NotNull(message = "预算不能为空")
    @DecimalMin(value = "100.00", message = "预算不能少于100")
    private Double budget;

    /** 出发日期，不得早于当日（service 校验，需用服务器时钟） */
    @NotNull(message = "出发日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "推荐结果不能为空")
    private TravelRecommendVO recommend;
}
