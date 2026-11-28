package com.suxin.travel.traveljava.reminder;

import com.rabbitmq.client.Channel;
import com.suxin.travel.traveljava.config.RabbitConfig;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import com.suxin.travel.traveljava.notify.NotifyResult;
import com.suxin.travel.traveljava.notify.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 提醒消费者（难点三的核心：幂等 = DB 状态机，不是 Redis SETNX）。
 *
 * 为什么不用 SETNX：幂等键若在发送前置位，渠道失败后 MQ 重投的同一条消息
 * 会被"已消费过"误吞，重试链路彻底失效——提醒静默丢失。
 * DB 状态机把"消费过"与"发送成功"绑成同一次状态迁移（DISPATCHED→SENT），
 * 重试与幂等天然不矛盾。这段对比是本项目最想讲的故事之一。
 *
 * 重试实现说明（审查修复）：spring retry 的拦截器包在监听器方法外层，
 * 而手动 ack 需要在方法内 catch 异常做 nack——异常被吞，容器重试永远不触发。
 * 因此改为 process() 内显式循环重试（最多 maxAttempts 次，带间隔），
 * 全部失败才 FAILED 回写 + nack 进 DLQ。语义与契约 §4.6 完全一致且可实测区分。
 *
 * 失败路径（契约 §4.6）：
 * - 全部渠道不可用（disabled/未绑定/离线）→ 直接 FAILED + NO_CHANNEL 日志，不进重试；
 * - 有渠道可用但全失败 → 显式重试耗尽 → FAILED + nack(requeue=false) → 死信队列
 *   （DLQ 人工重放前须先把日程 CAS 重置回 PENDING）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderConsumer {

    private final ScheduleItemMapper scheduleItemMapper;
    private final NotifyService notifyService;

    @Value("${reminder.retry-attempts:3}")
    private int maxAttempts;

    @Value("${reminder.retry-interval-ms:2000}")
    private long retryIntervalMs;

    @RabbitListener(queues = RabbitConfig.REMINDER_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        long itemId;
        try {
            itemId = Long.parseLong(new String(message.getBody()));
        } catch (Exception e) {
            // 无法解析的毒消息：直接丢弃（ack），不能让它反复重投
            log.error("提醒消息体无法解析，丢弃：{}", (Object) message.getBody(), e);
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            process(itemId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // process 内部重试已耗尽：状态回写 FAILED（出口），消息进 DLQ
            log.error("提醒消费最终失败 itemId={}，回写 FAILED 并转入死信队列", itemId, e);
            scheduleItemMapper.casStatus(itemId,
                    ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_FAILED);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 包级可见以便单元测试直驱 */
    void process(long itemId) {
        ScheduleItem item = scheduleItemMapper.selectById(itemId);
        if (item == null) {
            log.warn("提醒对应的日程不存在 itemId={}", itemId);
            return;
        }
        // 幂等闸门：只有 DISPATCHED 状态才该被本轮消费。
        // 用户取消/改期（状态被重置）后送达的旧消息在这里被识别并丢弃。
        if (!ScheduleItem.STATUS_DISPATCHED.equals(item.getStatus())) {
            log.info("日程状态已非 DISPATCHED（当前 {}），跳过提醒 itemId={}", item.getStatus(), itemId);
            return;
        }
        // 显式重试循环：全部渠道失败时最多再试 maxAttempts 次（契约 §4.6 进程内重试）
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            NotifyResult result = notifyService.sendAll(item);
            if (result.attempted() == 0) {
                // 全部渠道不可用：不重试（重试也不会成功），直接 FAILED 收口
                log.warn("无可用提醒渠道 itemId={}，置 FAILED（NO_CHANNEL）", itemId);
                scheduleItemMapper.casStatus(itemId,
                        ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_FAILED);
                notifyService.logNoChannel(itemId);
                return;
            }
            if (result.success() > 0) {
                scheduleItemMapper.casStatus(itemId,
                        ScheduleItem.STATUS_DISPATCHED, ScheduleItem.STATUS_SENT);
                log.info("提醒发送完成 itemId={} 成功渠道 {}/{}（第 {} 次尝试）",
                        itemId, result.success(), result.attempted(), attempt);
                return;
            }
            log.warn("全部可用渠道发送失败 itemId={} attempt={}/{}", itemId, attempt, maxAttempts);
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("全部可用渠道发送失败（重试 " + maxAttempts + " 次耗尽）itemId=" + itemId);
    }
}
