package com.suxin.travel.traveljava.notify;

import com.suxin.travel.traveljava.entity.ScheduleItem;

import java.time.format.DateTimeFormatter;

/**
 * 邮件文本模板（契约 §4.6a）。抽成纯静态工具以便单元测试格式与截断规则。
 * 时间口径：一律为日程开始时间 start_time（收件人关心的是日程何时开始，
 * 提前量由正文「提前 n 分钟提醒」表达）；禁止 LocalDateTime.toString() 的 T 形式直接输出。
 */
public class MailTexts {

    private static final DateTimeFormatter SUBJECT_TIME = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    private static final DateTimeFormatter BODY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MailTexts() {
    }

    /** 提醒邮件主题：标题超 20 字符截断加省略号（客户端可视区窄，时间前缀不能被挤出） */
    public static String reminderSubject(ScheduleItem item) {
        return "AI 旅行管家提醒｜" + item.getStartTime().format(SUBJECT_TIME)
                + " " + truncate(item.getTitle(), 20);
    }

    /** 提醒邮件正文：三行式，标题保留完整 */
    public static String reminderBody(ScheduleItem item) {
        return "您有一个安排即将开始：\n\n"
                + "时间：" + item.getStartTime().format(BODY_TIME) + "\n"
                + "事项：" + item.getTitle() + "（提前 " + item.getRemindMinutesBefore() + " 分钟提醒）\n\n"
                + "—— AI 旅行管家";
    }

    public static String bindCodeSubject() {
        return "AI 旅行管家｜邮箱绑定验证码";
    }

    public static String bindCodeBody(String code) {
        return "您的邮箱绑定验证码：" + code + "\n\n"
                + "验证码 10 分钟内有效，请勿透露给他人。\n"
                + "如非本人操作，请忽略本邮件。\n\n"
                + "—— AI 旅行管家";
    }

    static String truncate(String title, int max) {
        return title.length() <= max ? title : title.substring(0, max) + "…";
    }
}
