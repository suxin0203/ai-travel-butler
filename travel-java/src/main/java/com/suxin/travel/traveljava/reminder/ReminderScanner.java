package com.suxin.travel.traveljava.reminder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.suxin.travel.traveljava.config.RabbitConfig;
import com.suxin.travel.traveljava.entity.ReminderLog;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.mapper.ReminderLogMapper;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒扫描器（难点三的起点）：每分钟把"到点的日程"投进 MQ。
 *
 * 为什么用"每分钟扫描"而不是每条日程一个定时器：日程量大时定时器爆炸；
 * 扫描 + MQ 让"发现到期"与"执行通知"解耦，通知渠道的耗时/故障不影响扫描节奏。
 *
 * 防重复投递三件套：
 * 1. CAS PENDING→DISPATCHED（影响行数=0 说明别的实例/上一轮已投递，跳过）；
 * 2. MQ 投递失败 → CAS 回滚 DISPATCHED→PENDING，下一轮重扫（消息没出去状态先别推进）；
 * 3. 消费端再查一次状态（非 DISPATCHED 即跳过），兜住"投递后被用户取消/改期"的窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScanner {

    private final ScheduleItemMapper scheduleItemMapper;
    private final ReminderLogMapper reminderLogMapper;
    private final RabbitTemplate rabbitTemplate;

    /** 每分钟的整分触发（cron 秒位固定 0），契约声明"实际提醒最多晚于 remind_at 约 1 分钟"即源于此 */
    @Scheduled(cron = "0 * * * * *")
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        rescueStuckDispatched(now);
        // 错过即 FAILED（契约 §3.4/§3.9）：remind_at 已过超过 2 分钟宽限窗的 PENDING 不再补发。
        // 宽限窗 = 2×扫描周期：正常到点项（0~60s 延迟投递）不受影响，只有"被落下"的才收口
        List<ScheduleItem> missed = scheduleItemMapper.selectList(Wrappers.<ScheduleItem>lambdaQuery()
                .eq(ScheduleItem::getStatus, ScheduleItem.STATUS_PENDING)
                .lt(ScheduleItem::getRemindAt, now.minusMinutes(2)));
        for (ScheduleItem item : missed) {
            if (scheduleItemMapper.casStatus(item.getId(),
                    ScheduleItem.STATUS_PENDING, ScheduleItem.STATUS_FAILED) == 1) {
                ReminderLog row = new ReminderLog();
                row.setScheduleItemId(item.getId());
                row.setChannel("NONE");
                row.setStatus("FAIL");
                row.setDetail("MISSED");
                row.setCreatedAt(now);
                reminderLogMapper.insert(row);
                log.info("提醒已错过（超 2 分钟宽限窗），置 FAILED itemId={} remindAt={}", item.getId(), item.getRemindAt());
            }
        }

        List<ScheduleItem> due = scheduleItemMapper.selectList(Wrappers.<ScheduleItem>lambdaQuery()
                .eq(ScheduleItem::getStatus, ScheduleItem.STATUS_PENDING)
                .le(ScheduleItem::getRemindAt, now));
        if (due.isEmpty()) {
            return;
        }
        log.info("提醒扫描：{} 条到期待投递", due.size());
        for (ScheduleItem item : due) {
            // CAS 抢占：只有把 PENDING 推进到 DISPATCHED 的那个线程才有资格投递
            if (scheduleItemMapper.casStatus(item.getId(),
                    ScheduleItem.STATUS_PENDING, ScheduleItem.STATUS_DISPATCHED) == 0) {
                continue;
            }
            try {
                // 消息体只带 itemId，消费端回库取完整数据，避免消息膨胀与陈旧快照。
                // CorrelationData 携带 itemId：broker 侧丢消息（confirm nack）时由
                // RabbitConfirmConfig 回滚 CAS，重新扫描捞起——fire-and-forget 不再裸奔
                rabbitTemplate.convertAndSend(RabbitConfig.REMINDER_EXCHANGE,
                        RabbitConfig.REMINDER_ROUTING, String.valueOf(item.getId()),
                        new org.springframework.amqp.rabbit.connection.CorrelationData(
                                String.valueOf(item.getId())));
                log.info("已投递提醒 itemId={} remindAt={}", item.getId(), item.getRemindAt());
            } catch (Exception e) {
                // 同步异常（连接断开等）：回滚状态，下一轮扫描重新捞起
                log.error("MQ 投递失败，回滚状态 itemId={}", item.getId(), e);
                scheduleItemMapper.casStatus(item.getId(),
                        ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_PENDING);
            }
        }
    }

    /**
     * DISPATCHED 卡死兜底（契约 §4.6 v2.10）：CAS 抢占后进程崩溃 / broker confirm 丢失
     * 会让日程永久滞留 DISPATCHED——提醒静默丢失，这是提醒链路此前唯一已知的丢失窗口
     * （RabbitConfirmConfig 注释里自认的待办）。救援判据：DISPATCHED 且 updated_at 早于
     * now−5 分钟。5 分钟远大于消费端重试总时长（3×2s + 渠道耗时 <30s），在途消息不会被
     * 误伤；逐条 CAS 重置保持与消费端/confirm 回调的并发安全，重置后 updated_at 刷新，
     * 该项以 PENDING 回到正常扫描节奏（到点的下一轮即重新投递）。
     */
    private void rescueStuckDispatched(LocalDateTime now) {
        LocalDateTime threshold = now.minusMinutes(5);
        List<ScheduleItem> stuck = scheduleItemMapper.selectList(Wrappers.<ScheduleItem>lambdaQuery()
                .eq(ScheduleItem::getStatus, ScheduleItem.STATUS_DISPATCHED)
                .lt(ScheduleItem::getUpdatedAt, threshold));
        for (ScheduleItem item : stuck) {
            if (scheduleItemMapper.casStatus(item.getId(),
                    ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_PENDING) == 1) {
                ReminderLog row = new ReminderLog();
                row.setScheduleItemId(item.getId());
                row.setChannel("NONE");
                row.setStatus("FAIL");
                row.setDetail("DISPATCHED_TIMEOUT");
                row.setCreatedAt(now);
                reminderLogMapper.insert(row);
                log.warn("DISPATCHED 滞留超 5 分钟，兜底重置 PENDING itemId={} updatedAt={}",
                        item.getId(), item.getUpdatedAt());
            }
        }
    }
}
