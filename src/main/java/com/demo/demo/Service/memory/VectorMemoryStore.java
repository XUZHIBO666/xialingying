package com.demo.demo.Service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class VectorMemoryStore {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int RETRIEVAL_TOP_K = 5;
    private static final int MAX_PER_USER = 40; // 20轮对话，每轮user+assistant两条

    public VectorMemoryStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        log.info("[VectorMemory] 初始化完成");
    }

    /** 保存一轮对话（user + assistant 各一条向量） */
    public void saveTurn(String userId, String userMessage, String assistantMessage) {
        float[] userVec = embed(userMessage);
        float[] aiVec = embed(assistantMessage);
        saveOne(userId, "user", userMessage, userVec);
        saveOne(userId, "assistant", assistantMessage, aiVec);
        pruneUser(userId);
    }

    /** 检索与当前消息最相关的历史记忆 */
    public List<String> retrieveRelevant(String userId, String currentMessage) {
        float[] queryVec = embed(currentMessage);

        List<Row> rows = jdbc.query(
                "SELECT content, embedding FROM vector_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT 200",
                (rs, rowNum) -> new Row(rs.getString("content"), parseJson(rs.getString("embedding"))),
                userId);

        if (rows.isEmpty()) {
            return List.of();
        }

        return rows.stream()
                .map(r -> new Scored(r.content, cosine(queryVec, r.vec)))
                .filter(s -> s.score > 0.5)
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())
                .limit(RETRIEVAL_TOP_K)
                .map(s -> s.content)
                .toList();
    }

    // ==================== 内部方法 ====================

    private float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    private void saveOne(String userId, String role, String content, float[] vec) {
        try {
            String json = objectMapper.writeValueAsString(vec);
            jdbc.update(
                    "INSERT INTO vector_memory (user_id, role, content, embedding, created_at) VALUES (?, ?, ?, ?, ?)",
                    userId, role, content, json, LocalDateTime.now());
        } catch (JsonProcessingException e) {
            log.error("[VectorMemory] 序列化向量失败: {}", e.getMessage());
        }
    }

    private void pruneUser(String userId) {
        jdbc.update(
                "DELETE FROM vector_memory WHERE user_id = ? AND id NOT IN ("
                        + "SELECT id FROM ("
                        + "  SELECT id FROM vector_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
                        + ") AS t)",
                userId, userId, MAX_PER_USER);
    }

    private float[] parseJson(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            return new float[0];
        }
    }

    private double cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        double denominator = Math.sqrt(na) * Math.sqrt(nb);
        return denominator == 0 ? 0 : dot / denominator;
    }

    private record Row(String content, float[] vec) {}
    private record Scored(String content, double score) {}
}
