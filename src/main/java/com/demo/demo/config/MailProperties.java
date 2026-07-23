package com.demo.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "spring.mail")
public class MailProperties {
    private String host = "smtp.qq.com";
    private int port = 587;
    private String username = "";
    private String password = "";
    private String fromName = "微信Bot助手";
    private int rateLimitCount = 3;
    private int rateLimitMinutes = 10;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public int getRateLimitCount() { return rateLimitCount; }
    public void setRateLimitCount(int rateLimitCount) { this.rateLimitCount = rateLimitCount; }
    public int getRateLimitMinutes() { return rateLimitMinutes; }
    public void setRateLimitMinutes(int rateLimitMinutes) { this.rateLimitMinutes = rateLimitMinutes; }



}


