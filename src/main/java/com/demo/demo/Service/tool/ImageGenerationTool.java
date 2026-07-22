package com.demo.demo.Service.tool;

import com.demo.demo.Service.ImageGenerationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 图片生成工具 — 使用 Spring AI @Tool 注解。
 * 生成的图片通过 {@link #takeLastImage()} 取出（ThreadLocal，线程安全）。
 */
@Component
public class ImageGenerationTool {

    private final ImageGenerationService imageGenerationService;
    private final ThreadLocal<byte[]> lastGeneratedImage = new ThreadLocal<>();

    public ImageGenerationTool(ImageGenerationService imageGenerationService) {
        this.imageGenerationService = imageGenerationService;
    }

    @Tool(description = "根据用户的文字描述生成一张图片。" +
                        "当用户要求生成、制作、画、创建一张图片时调用此工具。" +
                        "参数 prompt 是图片的详细描述，支持中文或英文。")
    public String generateImage(
            @ToolParam(description = "图片的详细描述，中文或英文") String prompt) {
        try {
            byte[] imageBytes = imageGenerationService.generateImage(prompt);
            lastGeneratedImage.set(imageBytes);
            return "图片已生成，大小 " + (imageBytes.length / 1024) + " KB。请用文字简短告知用户图片已生成。";
        } catch (Exception e) {
            return "图片生成失败: " + e.getMessage();
        }
    }

    /** 取出当前线程最近一次生成的图片，取后清空。 */
    public byte[] takeLastImage() {
        byte[] image = lastGeneratedImage.get();
        lastGeneratedImage.remove();
        return image;
    }
}
