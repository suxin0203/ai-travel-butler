package com.suxin.travel.traveljava.notify;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.User;

/**
 * 提醒渠道抽象（contract §4.6 渠道矩阵）。
 * 新增渠道 = 实现本接口 + 注册为 Bean，消费者与状态机零改动——
 * 这就是"多渠道通知、可扩展渠道层"的设计落点（后期插 ICS 日历渠道也走这里）。
 */
public interface NotifyChannel {

    /** 渠道标识，与 reminder_log.channel 对应：SSE / MAIL / WECHAT */
    String name();

    /**
     * 该渠道对这位用户是否"可用"（渠道开关打开 + 用户已完成绑定）。
     * false = 渠道不可用：不参与成败判定，也不写发送日志（契约 §4.6）。
     */
    boolean enabledFor(User user);

    /** 执行发送。true = 投递成功；抛异常按失败处理 */
    boolean send(User user, ScheduleItem item);
}
