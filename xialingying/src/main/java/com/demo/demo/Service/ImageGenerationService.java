package com.demo.demo.Service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeImageApi;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageModel;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.util.Base64;

/**
 * 图片生成服务 —— 基于 Spring AI Alibaba DashScopeImageModel。
 *
 * <p>旧版手写 OkHttp + Gson 调 SiliconFlow，现已替换为百炼 DashScope 文生图。
 */
@Slf4j
@Service
public class ImageGenerationService {

    // ==================== DashScope 组件 ====================

    private DashScopeImageModel imageModel;

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${ai.image.model:wanx2.0-t2i-turbo}")
    private String model;

    @Value("${ai.image.size:1024x1024}")
    private String size;

    // ==================== 初始化 ====================

    /**
     * 在 @Value 注入完成后构建 DashScopeImageModel。
     * 不能用构造函数，因为 @Value 在构造之后才注入。
     */
    @PostConstruct
    public void init() {
        // 1. 创建 DashScope 图片 API 客户端
        DashScopeImageApi imageApi = DashScopeImageApi.builder()
                .apiKey(apiKey)
                .build();

        // 2. 配置图片生成参数：wanx 模型用 width/height，不支持 "1024x1024" 字符串格式
        DashScopeImageOptions options = DashScopeImageOptions.builder()
                .withModel(model)
                .withWidth(1024)
                .withHeight(1024)
                .withN(1)   // 每次生成 1 张图片
                .build();

        // 3. 组装完整的图片生成模型
        this.imageModel = DashScopeImageModel.builder()
                .dashScopeApi(imageApi)
                .defaultOptions(options)
                .build();

        log.info("[图片生成] 初始化完成 model={} size={}", model, size);
    }

    // ==================== 状态查询 ====================

    /** 是否已配置 API Key */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    // ==================== 图片生成（核心方法） ====================

    /**
     * 调用百炼 DashScope 文生图 API 生成图片。
     * 优先使用 base64 直接返回，其次下载 URL。
     *
     * @param prompt 图片提示词（中文或英文）
     * @return 图片字节数组
     * @throws IOException API 调用失败或返回为空时抛出
     */
    public byte[] generateImage(String prompt) throws IOException {
        // 前置校验
        if (!isConfigured()) {
            throw new IOException("图片生成 API 未配置");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IOException("图片提示词不能为空");
        }

        try {
            // 调用 DashScope 文生图 API
            ImageResponse response = imageModel.call(new ImagePrompt(prompt));
            Image image = response.getResult().getOutput();

            // 优先取 base64（不需要二次下载，速度更快）
            if (image.getB64Json() != null) {
                byte[] bytes = Base64.getDecoder().decode(image.getB64Json());
                log.info("[图片生成] 成功（base64） size={}KB", bytes.length / 1024);
                return bytes;
            }

            // 没有 base64 则从 URL 下载
            if (image.getUrl() != null) {
                byte[] bytes = new URL(image.getUrl()).openStream().readAllBytes();
                log.info("[图片生成] 成功（URL下载） size={}KB", bytes.length / 1024);
                return bytes;
            }

            throw new IOException("图片生成返回为空");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("[图片生成] 失败: {}", e.getMessage(), e);
            throw new IOException("图片生成失败: " + e.getMessage());
        }
    }
}
