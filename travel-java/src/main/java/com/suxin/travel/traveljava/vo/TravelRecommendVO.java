package com.suxin.travel.traveljava.vo;

import lombok.Data;

import java.util.List;

/**
 * 行程推荐结果结构（与提示词里要求的 JSON 对应，供前端渲染与 M3 一键转日程消费）。
 * 注意：模型可能输出额外字段（如 success），全局 ObjectMapper 已忽略未知字段，不影响解析。
 */
@Data
public class TravelRecommendVO {
    private String city;
    private Integer days;
    private Double totalBudget;
    private List<DailyItinerary> dailyItineraries;
    private BudgetBreakdown budgetBreakdown;
    private List<String> tips;
    private List<String> warnings;

    @Data
    public static class DailyItinerary {
        private Integer day;
        private String date;
        private TimeSlot morning;
        private TimeSlot afternoon;
        private TimeSlot evening;
    }

    @Data
    public static class TimeSlot {
        private String spot;
        private String duration;
        private String transportation;
        private String description;
        private String ticket;
    }

    @Data
    public static class BudgetBreakdown {
        private Double accommodation;
        private Double food;
        private Double transportation;
        private Double tickets;
        private Double other;
    }
}
