package com.demo.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@SpringBootApplication
public class  DemoApplication {

    public static void main(String[] args) {
        log.info("========================================");
        log.info("  Spring Boot Demo 正在启动...");
        log.info("  JDK 版本: {}", System.getProperty("java.version"));
        log.info("========================================");

        SpringApplication.run(DemoApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("========================================");
        log.info("  应用启动成功！");
        log.info("  首页: http://localhost:8080");
        log.info("  测试: http://localhost:8080/hello");
        log.info("========================================");
    }
}
