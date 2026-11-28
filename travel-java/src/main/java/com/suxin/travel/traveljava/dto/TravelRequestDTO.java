package com.suxin.travel.traveljava.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;


@Data
public class TravelRequestDTO {
    /** NotBlank 而非 NotNull：空串/纯空格的 city 也会被拦下，不让它进提示词 */
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

    /**
     * v2.9 开放偏好备注（契约 §2.6）：人数/饮食忌口/同行人情况/不想玩什么等自由文本。
     * 仅注入推荐提示词作生成上下文（三层边界见 TravelService.buildTravelPrompt），
     * 不落库、不参与结构化逻辑——保存行程回传的 recommend 结构里没有它，天然不会持久化。
     */
    @Size(max = 200, message = "备注不能超过 200 字符")
    private String note;
}
