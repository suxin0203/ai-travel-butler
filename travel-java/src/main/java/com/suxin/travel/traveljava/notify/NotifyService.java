package com.suxin.travel.traveljava.notify;

import com.suxin.travel.traveljava.entity.ReminderLog;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.User;
import com.suxin.travel.traveljava.mapper.ReminderLogMapper;
import com.suxin.travel.traveljava.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 渠道编排：遍历所有渠道 Bean，可用则发送、逐渠道写 reminder_log。
 * "部分成功也算整体成功"（SENT）：因为失败的渠道会在日志里留下 FAIL 记录可排查，
 * 而用户已经至少通过一条渠道收到了提醒，重试只会造成重复打扰。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final List<NotifyChannel> channels;
    private final ReminderLogMapper reminderLogMapper;
    private final UserMapper userMapper;

    public NotifyResult sendAll(ScheduleItem item) {
        User user = userMapper.selectById(item.getUserId());
        if (user == null) {
            return new NotifyResult(0, 0);
        }
        String content = "您 " + item.getStartTime().toLocalTime()
                + " 有安排：" + item.getTitle()
                + "（提前 " + item.getRemindMinutesBefore() + " 分钟提醒）";
        int attempted = 0;
        int success = 0;
        for (NotifyChannel channel : channels) {
            if (!channel.enabledFor(user)) {
                continue;
            }
            attempted++;
            boolean ok = false;
            String detail = null;
            try {
                ok = channel.send(user, item);
            } catch (Exception e) {
                detail = e.getMessage();
                log.warn("渠道 {} 发送异常 itemId={}", channel.name(), item.getId(), e);
            }
            saveLog(item.getId(), channel.name(), ok, detail);
            if (ok) {
                success++;
            }
        }
        return new NotifyResult(attempted, success);
    }

    /** 全渠道不可用的收口日志（契约 §4.6：置 FAILED + detail=NO_CHANNEL） */
    public void logNoChannel(long itemId) {
        saveLogRaw(itemId, "NONE", "FAIL", "NO_CHANNEL");
    }

    private void saveLog(long itemId, String channel, boolean ok, String detail) {
        saveLogRaw(itemId, channel, ok ? "SUCCESS" : "FAIL", detail);
    }

    private void saveLogRaw(long itemId, String channel, String status, String detail) {
        ReminderLog row = new ReminderLog();
        row.setScheduleItemId(itemId);
        row.setChannel(channel);
        row.setStatus(status);
        row.setDetail(detail);
        row.setCreatedAt(LocalDateTime.now());
        reminderLogMapper.insert(row);
    }
}
