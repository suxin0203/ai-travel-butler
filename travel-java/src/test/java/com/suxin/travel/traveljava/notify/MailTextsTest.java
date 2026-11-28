package com.suxin.travel.traveljava.notify;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件文本模板单元测试（契约 §4.6a）：主题格式、20 字截断、正文时间无 T 形式。
 */
class MailTextsTest {

    private ScheduleItem item(String title) {
        ScheduleItem item = new ScheduleItem();
        item.setTitle(title);
        item.setStartTime(LocalDateTime.of(2026, 11, 26, 9, 30));
        item.setRemindMinutesBefore(60);
        return item;
    }

    @Test
    @DisplayName("提醒主题：AI 旅行管家提醒｜MM月dd日 HH:mm 标题")
    void subjectFormat() {
        assertEquals("AI 旅行管家提醒｜11月26日 09:30 故宫博物院",
                MailTexts.reminderSubject(item("故宫博物院")));
    }

    @Test
    @DisplayName("主题标题超 20 字截断加省略号，正文保留完整标题")
    void subjectTruncatesLongTitle() {
        String longTitle = "一二三四五六七八九十一二三四五六七八九十一二三四五";  // 25 字
        String subject = MailTexts.reminderSubject(item(longTitle));
        assertTrue(subject.endsWith("…"));
        assertEquals("AI 旅行管家提醒｜11月26日 09:30 " + longTitle.substring(0, 20) + "…", subject);
        String body = MailTexts.reminderBody(item(longTitle));
        assertTrue(body.contains(longTitle));
    }

    @Test
    @DisplayName("正文：时间 yyyy-MM-dd HH:mm（无 T 形式）+ 完整标题 + 提前分钟数")
    void bodyFormat() {
        String body = MailTexts.reminderBody(item("故宫博物院"));
        assertTrue(body.contains("时间：2026-11-26 09:30"));
        assertFalse(body.contains("2026-11-26T"));
        assertTrue(body.contains("事项：故宫博物院（提前 60 分钟提醒）"));
        assertTrue(body.contains("—— AI 旅行管家"));
    }

    @Test
    @DisplayName("恰 20 字的标题不截断（边界）")
    void subjectKeepsExactTwentyChars() {
        String title = "一二三四五六七八九十一二三四五六七八九十";  // 恰 20 字
        String subject = MailTexts.reminderSubject(item(title));
        assertEquals("AI 旅行管家提醒｜11月26日 09:30 " + title, subject);
    }

    @Test
    @DisplayName("验证码邮件：主题与正文含 6 位码与有效期声明")
    void bindCodeMail() {
        assertEquals("AI 旅行管家｜邮箱绑定验证码", MailTexts.bindCodeSubject());
        String body = MailTexts.bindCodeBody("123456");
        assertTrue(body.contains("123456"));
        assertTrue(body.contains("10 分钟内有效"));
        assertTrue(body.contains("请勿透露给他人"));
    }
}
