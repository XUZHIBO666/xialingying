package com.demo.demo.Service.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间查询工具 — 使用 Spring AI @Tool 注解。
 */
@Component
public class TimeTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");

    @Tool(description = "获取当前的精确日期和时间。当用户询问现在几点、今天几号、当前时间、日期时使用此工具。")
    public String getCurrentTime() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
