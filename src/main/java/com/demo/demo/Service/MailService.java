package com.demo.demo.Service;

import com.demo.demo.config.MailProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MailService {

    private final JavaMailSender javaMailSender;
    private final MailProperties mailProperties;
    private final Map<String, Window> rateLimitWindows = new ConcurrentHashMap<>();

    public MailService(JavaMailSender javaMailSender, MailProperties mailProperties) {
        this.javaMailSender = javaMailSender;
        this.mailProperties = mailProperties;
    }

    public boolean isConfigured() {
        return mailProperties.getUsername() != null && !mailProperties.getUsername().isBlank()
                && mailProperties.getPassword() != null && !mailProperties.getPassword().isBlank();
    }

    public String send(String to, String subject, String content) {
        if (to == null || !to.matches("^[\\w.\\-]+@[\\w\\-]+(\\.[\\w\\-]+)+$")) {
            log.warn("[邮件] 收件人地址非法: {}", maskEmail(to));
            return "邮件发送失败：收件人地址格式不正确";
        }

        if (subject != null && subject.length() > 200) {
            subject = subject.substring(0, 197) + "...";
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailProperties.getUsername());
            helper.setTo(to);
            helper.setSubject(subject != null ? subject : "(无主题)");
            helper.setText(content != null ? content : "", false);
            javaMailSender.send(message);

            log.info("[邮件] 发送成功 to={} subject={}",
                    maskEmail(to), subject);
            return "邮件已发送到 " + maskEmail(to);
        } catch (Exception e) {
            log.error("[邮件] 发送失败 to={}", maskEmail(to), e);
            return "邮件发送失败：" + e.getMessage();
        }
    }

    public boolean checkRateLimit(String userId) {
        long now = System.currentTimeMillis();
        Window window = rateLimitWindows.compute(userId, (k, v) -> {
            if (v == null || now - v.startTime > mailProperties.getRateLimitMinutes() * 60_000L) {
                return new Window(now, 1);
            }
            v.count++;
            return v;
        });

        boolean allowed = window.count <= mailProperties.getRateLimitCount();
        if (!allowed) {
            log.info("[邮件] 用户触发频率限制 userId={} count={}", userId, window.count);
        }
        return allowed;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = email.substring(at);
        if (name.length() <= 2) return name + "***" + domain;
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + domain;
    }

    static class Window {
        final long startTime;
        int count;

        Window(long startTime, int count) {
            this.startTime = startTime;
            this.count = count;
        }
    }
}
