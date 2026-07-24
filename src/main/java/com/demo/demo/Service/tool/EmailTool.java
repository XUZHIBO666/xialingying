package com.demo.demo.Service.tool;

import com.demo.demo.Service.MailService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 邮件发送工具 — 使用 Spring AI @Tool 注解，LLM 自动识别并调用。
 */
@Component
public class EmailTool {

    private final MailService mailService;

    public EmailTool(MailService mailService) {
        this.mailService = mailService;
    }

    @Tool(description = "发送一封邮件到指定邮箱地址。" +
                        "当用户要求发送邮件、邮寄、记录备忘到邮箱、把内容发到邮箱时调用此工具。" +
                        "收件人地址由用户提供，如果没有明确给出则向用户确认。")
    public String sendEmail(
            @ToolParam(description = "收件人邮箱地址，例如 someone@example.com") String to,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "邮件正文内容") String content) {

        if (!mailService.isConfigured()) {
            return "邮件发送失败：邮件服务未配置，请联系管理员设置 SMTP 信息";
        }

        try {
            return mailService.send(to, subject, content);
        } catch (Exception e) {
            return "邮件发送失败: " + e.getMessage();
        }
    }
}
