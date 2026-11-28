package com.suxin.travel.traveljava.notify;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 站内在线推送渠道：页面开着就能收到（contract §4.6），始终视为可启用 */
@Component
@RequiredArgsConstructor
public class SseNotifyChannel implements NotifyChannel {

    private final SseRegistry sseRegistry;

    @Value("${channels.sse:true}")
    private boolean enabled;

    @Override
    public String name() {
        return "SSE";
    }

    @Override
    public boolean enabledFor(User user) {
        // 契约 §4.6：用户离线 = SSE 渠道不可用（不参与成败判定），
        // 而不是"可用渠道发送失败"——否则每个离线用户都会制造一条无解的 DLQ 死信
        return enabled && sseRegistry.isOnline(user.getId());
    }

    @Override
    public boolean send(User user, ScheduleItem item) {
        // 事件结构见契约 §1.6：type/eventId/scheduleId/title/content/remindAt
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "reminder");
        payload.put("eventId", SseRegistry.newEventId());
        payload.put("scheduleId", item.getId());
        payload.put("title", item.getTitle());
        payload.put("content", "您 " + item.getStartTime().toLocalTime() + " 有安排："
                + item.getTitle() + "（提前 " + item.getRemindMinutesBefore() + " 分钟提醒）");
        payload.put("remindAt", item.getRemindAt().toString().replace('T', ' '));
        return sseRegistry.push(user.getId(), payload);
    }
}
