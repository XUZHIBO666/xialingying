package com.demo.demo.Service.tool;

import com.demo.demo.Service.Resume.LiepinApplyService;
import com.demo.demo.Service.Resume.ResumeService;
import com.demo.demo.model.Resume;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 猎聘 Agent 工具 — @Tool 注解桥接到 MCP Server（Node.js + Playwright）。
 *
 * <p>因为 ReactAgent builder 只支持 @Tool bean，不支持原生 ToolCallback，
 * 所以这里用 Java @Tool 方法包装 MCP 调用。
 */
@Slf4j
@Component
public class LiepinTool {

    @Resource
    private ResumeService resumeService;

    @Resource
    private LiepinApplyService liepinApplyService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 三个 @Tool 方法 ====================

    @Tool(description = "检查猎聘网登录状态。在投递简历前必须先调用。如果未登录，浏览器会弹出窗口供用户扫码。")
    public String liepinLoginCheck(ToolContext toolContext) {
        return callMCP("liepin_login_check", Map.of());
    }

    @Tool(description = "在猎聘网上搜索岗位。keyword 必须严格使用用户原话中的岗位名称，" +
            "用户说销售就搜销售，绝不要根据简历内容修改。" +
            "必填参数：keyword（岗位关键词）和 city（城市）。")
    public String liepinSearchJobs(
            @ToolParam(description = "岗位关键词，如 销售、产品经理") String keyword,
            @ToolParam(description = "工作城市，如 杭州、北京", required = false) String city) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        if (city != null && !city.isBlank()) args.put("city", city);
        return callMCP("liepin_search_jobs", args);
    }

    @Tool(description = "在猎聘上批量搜索并投递简历（猎聘使用平台自有简历，点击「发简历」按钮即可）。" +
            "keyword 必须严格等于用户原话中的岗位名称，用户说销售就投销售，用户说运营就投运营，" +
            "绝不要根据简历内容自行修改关键词。" +
            "必填 keyword（岗位）和 city（城市），可选 maxCount（默认5）。" +
            "投递流程：搜索岗位 → 打开链接 → 点击聊天页顶部「发简历」按钮。")
    public String liepinBatchApply(
            @ToolParam(description = "岗位关键词，如 销售、产品经理") String keyword,
            @ToolParam(description = "工作城市，如 杭州") String city,
            @ToolParam(description = "投递数量上限，默认5个", required = false) Integer maxCount,
            ToolContext toolContext) {

        String userId = contextValue(toolContext, "user_id");
        // 获取简历ID用于记录（猎聘使用平台自有简历，无需传简历文本）
        Resume resume = resumeService.getLatestResume(userId);
        Long resumeId = (resume != null) ? resume.getId() : null;

        int count = maxCount != null ? maxCount : 5;

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("city", city != null ? city : "");
        args.put("maxCount", count);

        String result = callMCP("liepin_batch_apply", args);

        // 解析 MCP 返回结果，记录到数据库
        try {
            JsonNode node = MAPPER.readTree(result);
            JsonNode details = node.get("details");
            if (details != null && details.isArray()) {
                for (JsonNode d : details) {
                    String title = d.has("title") ? d.get("title").asText() : "";
                    String company = d.has("company") ? d.get("company").asText() : "";
                    String url = d.has("url") ? d.get("url").asText() : "";
                    boolean success = d.has("success") && d.get("success").asBoolean();
                    String msg = d.has("message") ? d.get("message").asText() : "";

                    liepinApplyService.recordApply(userId,
                            resumeId != null ? resumeId : 0L, "liepin",
                            title, company, url, success, msg);
                }
            }
        } catch (Exception e) {
            log.warn("[猎聘] 解析投递结果失败: {}", e.getMessage());
        }

        return result;
    }

    // ==================== MCP JSON-RPC 通信（内嵌，避免 McpClientConfig 循环依赖） ====================

    private static Process serverProcess;
    private static BufferedWriter serverIn;
    private static BufferedReader serverOut;
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    private static final Object INIT_LOCK = new Object();

    private String callMCP(String toolName, Map<String, Object> arguments) {
        ensureStarted();
        int id = REQUEST_ID.getAndIncrement();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "tools/call");
        request.put("params", Map.of("name", toolName, "arguments", arguments));

        Pending pending = new Pending();
        PENDING.put(id, pending);

        try {
            String json = MAPPER.writeValueAsString(request);
            synchronized (serverIn) {
                serverIn.write(json);
                serverIn.newLine();
                serverIn.flush();
            }
        } catch (IOException e) {
            PENDING.remove(id);
            return "{\"error\": \"MCP 请求失败: " + e.getMessage() + "\"}";
        }

        synchronized (pending) {
            try { pending.wait(120_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        PENDING.remove(id);

        if (pending.response == null) return "{\"error\": \"MCP 请求超时\"}";
        // 从 MCP 返回的 content[0].text 中提取
        return extractText(pending.response);
    }

    private void ensureStarted() {
        if (initialized && serverProcess != null && serverProcess.isAlive()) return;
        synchronized (INIT_LOCK) {
            if (initialized && serverProcess != null && serverProcess.isAlive()) return;
            try {
                ProcessBuilder pb = new ProcessBuilder("node", "./liepin-mcp-server/index.js");
                pb.redirectErrorStream(false);
                serverProcess = pb.start();
                serverIn = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream(), StandardCharsets.UTF_8));
                serverOut = new BufferedReader(new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8));

                // stderr reader
                Thread errReader = new Thread(() -> {
                    try (var r = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) log.info("[MCP] {}", line);
                    } catch (IOException ignored) {}
                }, "mcp-err");
                errReader.setDaemon(true);
                errReader.start();

                // initialize
                Map<String, Object> initReq = new LinkedHashMap<>();
                initReq.put("jsonrpc", "2.0");
                initReq.put("id", REQUEST_ID.getAndIncrement());
                initReq.put("method", "initialize");
                initReq.put("params", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "liepin-tool", "version", "1.0")));
                String initJson = MAPPER.writeValueAsString(initReq);
                synchronized (serverIn) {
                    serverIn.write(initJson);
                    serverIn.newLine();
                    serverIn.flush();
                }
                // send initialized notification
                Map<String, Object> notif = new LinkedHashMap<>();
                notif.put("jsonrpc", "2.0");
                notif.put("method", "notifications/initialized");
                notif.put("params", Map.of());
                synchronized (serverIn) {
                    serverIn.write(MAPPER.writeValueAsString(notif));
                    serverIn.newLine();
                    serverIn.flush();
                }

                initialized = true;
                log.info("[LiepinTool] MCP Server 已启动");
            } catch (Exception e) {
                log.error("[LiepinTool] MCP Server 启动失败: {}", e.getMessage());
            }
        }
    }

    static {
        Thread reader = new Thread(() -> {
            while (true) {
                try {
                    if (serverOut == null) { Thread.sleep(100); continue; }
                    String line = serverOut.readLine();
                    if (line == null) break;
                    JsonNode node = MAPPER.readTree(line);
                    if (!node.has("id")) continue;
                    int id = node.get("id").asInt();
                    Pending p = PENDING.get(id);
                    if (p == null) continue;
                    synchronized (p) {
                        if (node.has("result")) p.response = MAPPER.convertValue(node.get("result"), Map.class);
                        p.notifyAll();
                    }
                } catch (Exception ignored) {}
            }
        }, "mcp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private String extractText(Map<String, Object> mcpResult) {
        try {
            Object content = mcpResult.get("content");
            if (content instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m) {
                    Object text = m.get("text");
                    return text != null ? String.valueOf(text) : MAPPER.writeValueAsString(mcpResult);
                }
            }
        } catch (Exception ignored) {}
        try { return MAPPER.writeValueAsString(mcpResult); } catch (Exception ignored) {}
        return "{}";
    }

    private static String contextValue(ToolContext ctx, String key) {
        if (ctx == null) throw new IllegalArgumentException("缺少 ToolContext");
        Object v = ctx.getContext().get(key);
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException("缺少 " + key);
        return s;
    }

    private static class Pending { Map<String, Object> response; }
}
