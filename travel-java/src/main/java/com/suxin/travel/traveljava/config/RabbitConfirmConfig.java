package com.suxin.travel.traveljava.config;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * RabbitMQ publisher confirm：投递的每条消息带 CorrelationData(id=itemId)。
 * broker 确认失败（消息没到 RabbitMQ）时把日程 CAS 回 PENDING，下一轮扫描重投——
 * 堵住"convertAndSend 写 socket 即返回、broker 侧丢失无感知"的窗口。
 * 应用在 CAS 之后、发送之前崩溃的窗口仍存在，需待办里的 updated_at 兜底扫描彻底关闭。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitConfirmConfig {

    private final RabbitTemplate rabbitTemplate;
    private final ScheduleItemMapper scheduleItemMapper;

    @PostConstruct
    public void bindConfirmCallback() {
        rabbitTemplate.setConfirmCallback((correlation, ack, cause) -> {
            if (ack) {
                return;
            }
            if (correlation == null || correlation.getId() == null) {
                log.error("消息确认失败且无关联 id，cause={}", cause);
                return;
            }
            try {
                long itemId = Long.parseLong(correlation.getId());
                log.error("broker 未确认提醒消息 itemId={} cause={}，回滚状态待重投", itemId, cause);
                scheduleItemMapper.casStatus(itemId,
                        ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_PENDING);
            } catch (NumberFormatException ignore) {
                // 非日程消息（本项目当前只有提醒消息），忽略
            }
        });
    }
}
