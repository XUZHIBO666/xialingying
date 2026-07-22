package com.demo.demo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@RestController
public class HelloController {

    @GetMapping("/")
    public String home() {
        log.info(">>> 访问了首页");
        return "<h1>Spring Boot Demo 运行成功！🎉</h1>\n"
                + "<ul>\n"
                + "    <li><a href=\"/hello\">/hello</a> — 打招呼（查看控制台日志）</li>\n"
                + "    <li><a href=\"/hello/你的名字\">/hello/{name}</a> — 个性化打招呼</li>\n"
                + "</ul>";
    }

    @GetMapping("/hello")
    public String hello() {
        log.debug(">>> DEBUG 级别日志 - 调试信息");
        log.info(">>> INFO 级别日志 - 收到 /hello 请求");
        log.warn(">>> WARN 级别日志 - 这是一条警告示例");
        log.error(">>> ERROR 级别日志 - 这是一条错误示例（仅演示）");
        return "Hello, Spring Boot!（查看 IDEA 控制台可看到 DEBUG/INFO/WARN/ERROR 四种日志）";
    }

    @GetMapping("/hello/{name}")
    public String helloName(@PathVariable String name) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info(">>> 用户 [{}] 在 [{}] 访问了 /hello/{}", name, time, name);
        return String.format("你好，%s！<br>当前服务器时间：%s", name, time);
    }
}
