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
        //将用户信息向量化
        float[] userVec = embed(userMessage);
        //将AI信息向量化
        float[] aiVec = embed(assistantMessage);
        //保存用户信息，和用户向量化信息
        saveOne(userId, "user", userMessage, userVec);
        //保存AI信息，和AI向量化信息
        saveOne(userId, "assistant", assistantMessage, aiVec);
        //检查消息是否达到上限值，从下往上检索只留下后四十条信息
        pruneUser(userId);
    }

    /** 检索与当前消息最相关的历史记忆 */
    public List<String> retrieveRelevant(String userId, String currentMessage) {
        //将当条信息向量化
        float[] queryVec = embed(currentMessage);

        List<Row> rows = jdbc.query(
                "SELECT content, embedding FROM vector_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT 200",
                (rs, rowNum) -> new Row(rs.getString("content"), parseJson(rs.getString("embedding"))),
                userId);

        if (rows.isEmpty()) {
            return List.of();
        }

        return rows.stream()
                .map(r -> new Scored(r.content, cosine(queryVec, r.vec)))//记录每条信息和当前信息的余弦相似度
                .filter(s -> s.score > 0.5)//过滤掉余弦相似度小于0.5的
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())//按照余弦相似度的大小从大到小排序
                .limit(RETRIEVAL_TOP_K)//限制信息个数五条
                .map(s -> s.content)//提取信息内容
                .toList();
    }

    // ==================== 内部方法 ====================
    //返回向量化后的信息
    private float[] embed(String text) {
        return embeddingModel.embed(text);//将信息向量化,成为一个浮点值数组
    }
    //将信息以及向量化过后的信息保存在数据库中
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
    //检查信息是否已经达到了上限值，从下往上检索只留下后四十条信息
    private void pruneUser(String userId) {
        jdbc.update(
                "DELETE FROM vector_memory WHERE user_id = ? AND id NOT IN ("
                        + "SELECT id FROM ("
                        + "  SELECT id FROM vector_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
                        + ") AS t)",
                userId, userId, MAX_PER_USER);
    }
    //将JSON格式的信息转化为float数组
    private float[] parseJson(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            return new float[0];
        }
    }
//计算向量值的余弦相似度
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
    //定义一个记录类，用于保存检索到的向量信息
    private record Row(String content, float[] vec) {}
    //定义一个记录类，用于保存检索到的向量信息以及相似度
    private record Scored(String content, double score) {}
}
