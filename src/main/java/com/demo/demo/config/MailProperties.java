package com.demo.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.mail")
public class MailProperties {
    private String host = "smtp.qq.com";
    private int port = 465;
    private String username = "";
    private String password = "";
    private String fromName = "微信Bot助手";
    private int rateLimitCount = 3;
    private int rateLimitMinutes = 10;
}


