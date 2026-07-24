package com.demo.demo.Service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索工具 — 使用百度搜索 API，LLM 自动识别并调用。
 */
@Slf4j
@Component
public class WebSearchTool {

    @Value("${ai.search.baidu.api-key:}")
    private String apiKey;

    @Value("${ai.search.baidu.api-url:https://qianfan.baidubce.com/v2/ai_search/web_search}")
    private String apiUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WebSearchTool() {
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    @Tool(description = "联网搜索工具，获取互联网上的最新信息。" +
                        "当用户询问任何需要实时数据、最新消息、未知信息的问题时必须使用此工具，" +
                        "包括但不限于：新闻、考试分数线、政策、股票、天气之外的实时数据、赛事、产品信息等。" +
                        "返回搜索到的相关内容。"+"这是一个查询工具，当用户有查询需求时可调用进行联网搜索")
    public String search(
            @ToolParam(description = "搜索关键词或问题，用简洁的自然语言描述") String query) {
        if (apiKey == null || apiKey.isBlank()) {
            return "搜索功能未配置 API Key，请联系管理员。";
        }

        log.info("[WebSearch] 搜索关键词: {}", query);

        try {
            String requestBody = String.format(
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}",
                    escapeJson(query));

            String response = restClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            log.debug("[WebSearch] 原始响应: {}", response);
            return formatResults(response);
        } catch (Exception e) {
            log.error("[WebSearch] 搜索失败: {}", e.getMessage());
            return "搜索失败: " + e.getMessage();
        }
    }

    private String formatResults(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            // 解析 references（根层级）— 千帆 v2/ai_search/web_search 响应格式
            List<String> results = new ArrayList<>();
            JsonNode references = root.path("references");
            if (references.isArray()) {
                int count = 0;
                for (JsonNode item : references) {
                    if (count >= 5) break;
                    String title = item.path("title").asText("");
                    String content = item.path("content").asText("");
                    String url = item.path("url").asText("");
                    if (!title.isBlank() || !content.isBlank()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(count + 1).append(". ");
                        if (!title.isBlank()) sb.append(title).append("\n");
                        if (!content.isBlank()) {
                            String sc = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                            sb.append("   ").append(sc).append("\n");
                        }
                        if (!url.isBlank()) sb.append("   ").append(url).append("\n");
                        results.add(sb.toString());
                        count++;
                    }
                }
            }

            if (!results.isEmpty()) {
                return String.join("\n", results);
            }
            return "未找到相关搜索结果。";
        } catch (Exception e) {
            log.warn("[WebSearch] 解析响应失败: {}", e.getMessage());
            if (jsonResponse.length() > 800) {
                return jsonResponse.substring(0, 800) + "...";
            }
            return jsonResponse;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
