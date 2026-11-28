package com.suxin.travel.traveljava.notify;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/**
 * 邮件渠道（QQ SMTP 授权码）。
 * 可用判定 = channels.mail 开关 + SMTP 配置齐全 + 用户已绑定 email；
 * 任一不满足即"渠道不可用"，不参与成败判定（契约 §4.6）。
 */
@Slf4j
@Component
public class MailNotifyChannel implements NotifyChannel {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean enabled;
    private final String from;

    public MailNotifyChannel(ObjectProvider<JavaMailSender> mailSenderProvider,
                             @Value("${channels.mail:false}") boolean enabled,
                             @Value("${spring.mail.host:}") String host,
                             @Value("${spring.mail.username:}") String username) {
        this.mailSenderProvider = mailSenderProvider;
        this.enabled = enabled;
        this.from = username;
    }

    @Override
    public String name() {
        return "MAIL";
    }

    @Override
    public boolean enabledFor(User user) {
        if (!enabled) {
            return false;
        }
        // 用户偏好关闭（契约 §2.5b）→ 渠道不可用，静默跳过
        if (Boolean.FALSE.equals(user.getNotifyMail())) {
            return false;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || from == null || from.isBlank()) {
            return false; // SMTP 没配全
        }
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    @Override
    public boolean send(User user, ScheduleItem item) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            return false;
        }
        // 文本模板抽到 MailTexts（契约 §4.6a）：带「日期 时间 事项」主题，正文三行式，
        // 时间格式化（修掉此前 LocalDateTime.toString() 的 T 形式）
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject(MailTexts.reminderSubject(item));
        message.setText(MailTexts.reminderBody(item));
        sender.send(message);
        log.info("提醒邮件已发送 uid={} email={} itemId={}", user.getId(), user.getEmail(), item.getId());
        return true;
    }
}
