package com.demo.demo.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP Client — 通过 stdio JSON-RPC 与 Liepin MCP Server 通信。
 *
 * <p>不依赖 spring-ai-starter-mcp-client 的具体 API 版本，
 * 直接使用标准 JSON-RPC 2.0 协议与 Node.js MCP Server 交互，
 * 将 MCP 工具暴露为 Spring AI ToolCallback Bean。
 *
 * <p>架构：
 * <pre>
 *   ReactAgent → ToolCallback.call() → JSON-RPC over stdio → Node.js MCP Server → Playwright → 猎聘
 * </pre>
 */
@Slf4j
// @Configuration — 已改用 LiepinTool 内嵌 MCP 客户端，避免双进程冲突
public class McpClientConfig {

    private static final String SERVER_SCRIPT = "./liepin-mcp-server/index.js";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REQUEST_TIMEOUT_MS = 120_000;

    /** 与 MCP Server 进程的通信通道 */
    private static Process serverProcess;
    private static BufferedWriter serverIn;
    private static BufferedReader serverOut;
    private static final AtomicInteger requestIdSeq = new AtomicInteger(1);
    /** pending requests: id → future */
    private static final ConcurrentHashMap<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    @Bean
    public List<ToolCallback> liepinMcpToolCallbacks() {
        try {
            startMcpServer();
            List<Map<String, Object>> tools = listMCPTools();
            log.info("[MCP] 获取到 {} 个猎聘工具", tools.size());
            return tools.stream()
                    .map(this::toToolCallback)
                    .toList();
        } catch (Exception e) {
            log.warn("[MCP] 猎聘 MCP Server 启动失败，投递功能暂不可用: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== MCP 协议通信 ====================

    private synchronized void startMcpServer() {
        if (serverProcess != null && serverProcess.isAlive()) return;

        try {
            log.info("[MCP] 启动 Liepin MCP Server: node {}", SERVER_SCRIPT);
            ProcessBuilder pb = new ProcessBuilder("node", SERVER_SCRIPT);
            pb.redirectErrorStream(false); // stderr 用于日志，stdout 用于协议
            serverProcess = pb.start();

            serverIn = new BufferedWriter(
                    new OutputStreamWriter(serverProcess.getOutputStream(), StandardCharsets.UTF_8));
            serverOut = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8));

            // 启动 stderr 读取线程（MCP Server 的日志输出）
            Thread stderrReader = new Thread(() -> {
                try (var err = new BufferedReader(
                        new InputStreamReader(serverProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.info("[MCP-Server] {}", line);
                    }
                } catch (IOException ignored) {}
            }, "mcp-stderr-reader");
            stderrReader.setDaemon(true);
            stderrReader.start();

            // MCP initialize 握手
            Map<String, Object> initResult = sendMCPRequest("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "spring-ai-client", "version", "1.0")
            ));
            log.info("[MCP] 握手完成: {}", initResult);

            // 发送 initialized 通知
            sendMCPNotification("notifications/initialized", Map.of());

        } catch (Exception e) {
            log.error("[MCP] 启动失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法启动 MCP Server", e);
        }
    }

    /** 列出 MCP Server 可用的工具 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listMCPTools() {
        Map<String, Object> result = sendMCPRequest("tools/list", Map.of());
        Object tools = result.get("tools");
        if (tools instanceof List) {
            return (List<Map<String, Object>>) tools;
        }
        return List.of();
    }

    /** 调用 MCP 工具 */
    private String callMCPTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> result = sendMCPRequest("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments
        ));
        // MCP 返回格式：{ content: [{ type: "text", text: "..." }] }
        Object content = result.get("content");
        if (content instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m) {
                Object text = m.get("text");
                return text != null ? String.valueOf(text) : MAPPER.createObjectNode().put("result", String.valueOf(result)).toString();
            }
        }
        return MAPPER.createObjectNode().put("result", String.valueOf(result)).toString();
    }

    // ==================== JSON-RPC 底层 ====================

    private Map<String, Object> sendMCPRequest(String method, Map<String, Object> params) {
        int id = requestIdSeq.getAndIncrement();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        PendingRequest pending = new PendingRequest();
        pendingRequests.put(id, pending);

        try {
            String json = MAPPER.writeValueAsString(request);
            synchronized (serverIn) {
                serverIn.write(json);
                serverIn.newLine();
                serverIn.flush();
            }
            log.debug("[MCP] → {} id={}", method, id);
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new RuntimeException("MCP 请求发送失败: " + method, e);
        }

        // 等待响应
        synchronized (pending) {
            try {
                pending.wait(REQUEST_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pendingRequests.remove(id);
                throw new RuntimeException("MCP 请求中断: " + method);
            }
        }
        pendingRequests.remove(id);

        if (pending.response == null) {
            throw new RuntimeException("MCP 请求超时: " + method);
        }
        if (pending.error != null) {
            throw new RuntimeException("MCP 请求错误: " + method + " → " + pending.error);
        }
        return pending.response;
    }

    private void sendMCPNotification(String method, Map<String, Object> params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        try {
            String json = MAPPER.writeValueAsString(notification);
            synchronized (serverIn) {
                serverIn.write(json);
                serverIn.newLine();
                serverIn.flush();
            }
        } catch (IOException e) {
            log.error("[MCP] 通知发送失败: {}", method, e);
        }
    }

    /** 将 MCP 工具定义转为 Spring AI ToolCallback */
    private ToolCallback toToolCallback(Map<String, Object> toolDef) {
        String name = String.valueOf(toolDef.getOrDefault("name", "unknown"));
        String description = String.valueOf(toolDef.getOrDefault("description", ""));
        Object inputSchema = toolDef.getOrDefault("inputSchema", Map.of());

        final String inputSchemaJson = toJson(inputSchema);

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchemaJson)
                        .build();
            }

            @Override
            @SuppressWarnings("unchecked")
            public String call(String toolInput) {
                try {
                    JsonNode node = MAPPER.readTree(toolInput);
                    Map<String, Object> args = MAPPER.convertValue(node, Map.class);
                    return callMCPTool(name, args != null ? args : Map.of());
                } catch (Exception e) {
                    // 如果 toolInput 不是 JSON，直接作为参数传入
                    log.warn("[MCP] 无法解析 toolInput，原样传入: {}", toolInput);
                    return callMCPTool(name, Map.of("input", toolInput));
                }
            }
        };
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ==================== 响应读取 ====================

    static {
        Thread responseReader = new Thread(() -> {
            while (true) {
                try {
                    if (serverOut == null) {
                        Thread.sleep(100);
                        continue;
                    }
                    String line = serverOut.readLine();
                    if (line == null) break;

                    JsonNode node = MAPPER.readTree(line);
                    // 跳过通知（无 id）
                    if (!node.has("id")) continue;

                    int id = node.get("id").asInt();
                    PendingRequest pending = pendingRequests.get(id);
                    if (pending == null) {
                        log.debug("[MCP] 收到未知 id 的响应: {}", id);
                        continue;
                    }

                    synchronized (pending) {
                        if (node.has("error")) {
                            pending.error = node.get("error").toString();
                        } else if (node.has("result")) {
                            pending.response = MAPPER.convertValue(node.get("result"), Map.class);
                        }
                        pending.notifyAll();
                    }
                } catch (Exception e) {
                    if (serverOut == null) break; // 进程已关闭
                    log.error("[MCP] 响应读取异常: {}", e.getMessage());
                }
            }
        }, "mcp-response-reader");
        responseReader.setDaemon(true);
        responseReader.start();
    }

    private static class PendingRequest {
        Map<String, Object> response;
        String error;
    }
}
