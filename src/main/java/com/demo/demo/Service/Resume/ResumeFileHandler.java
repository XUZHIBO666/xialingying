package com.demo.demo.Service.Resume;

import com.demo.demo.Service.AIService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ResumeFileHandler {
    @Resource
    private ResumeFileParser resumeFileParser;

    @Resource
    private ResumeService resumeService;

    @Resource
    private AIService aiService;

    /** 支持的文件扩展名 */
    private static final java.util.Set<String> SUPPORTED_EXTS =
            java.util.Set.of("pdf", "docx", "doc");

    /**
     * 处理用户发送的文件消息。
     *
     * @param fromUser    发送者 userId
     * @param contextToken 微信上下文令牌
     * @param fileName     文件名（含扩展名）
     * @param fileBytes    文件字节数组
     * @param fileSize     文件大小
     * @return 回复给用户的消息，null 表示无回复
     */
    public String onFile(String fromUser, String contextToken,
                         String fileName, byte[] fileBytes, long fileSize) {

        // 1. 检查文件格式
        String ext = getFileExtension(fileName);
        if (ext == null || !SUPPORTED_EXTS.contains(ext)) {
            return "📄 目前支持 PDF 和 Word（.docx）格式的简历，" +
                    "请重新发送。当前文件: " + fileName;
        }

        // 2. 解析文件文本
        log.info("[简历文件] 开始解析 from={} fileName={} size={}",
                maskUserId(fromUser), fileName, fileSize);
        //resumeFileParser里面的方法，解析文件内容并产生回复
        String content = resumeFileParser.parse(fileBytes, ext);
        if (content == null || content.isBlank()) {
            return "❌ 简历解析失败，文件可能是空的或格式不支持。请重新发送。";
        }

        // 3. 存入数据库
        resumeService.saveResume(fromUser, fileName, content, ext, fileSize);

        // 4. 调用 AI 分析文件内容，并产生建议
        String analysisPrompt = buildAnalysisPrompt(content);
        String analysis = aiService.chat(fromUser, contextToken, analysisPrompt);

        if (analysis == null || analysis.isBlank()) {
            return "✅ 简历已收到（" + content.length() + "字），但 AI 暂时无法分析，" +
                    "请稍后说「分析我的简历」。";
        }

        // 5. 保存分析结果到最新简历，并且将分析结果写入数据库
        var latest = resumeService.getLatestResume(fromUser);
        if (latest != null) {
            resumeService.saveAnalysisResult(latest.getId(), analysis);
        }

        // 6. 返回分析结果
        return "📋 **简历分析结果**\n\n" + analysis;
    }

    /** 构建 AI 分析提示词 */
    private String buildAnalysisPrompt(String resumeContent) {
        return """
                  ## 任务：简历评估与优化建议

                  你是一位资深 HR 和职业规划师。请仔细分析以下简历内容，从以下维度给出具体的修改建议：

                  1. **格式与排版**：篇幅是否合理？结构是否清晰？
                  2. **工作经历**：是否用 STAR 法则描述？成果是否量化？
                  3. **技能亮点**：核心技能是否突出？与技术栈是否匹配？
                  4. **教育背景**：是否突出重点？
                  5. **缺失信息**：缺少哪些关键信息？

                  ⚠️ 要求：
                  - 每个维度给出 1-3 条具体可操作的建议
                  - 指出具体的缺失内容（如「缺少项目经历」「没有联系方式」等）
                  - 给出修改后的简历模板框架
                  - 最后询问用户想补充哪些内容，你可以帮忙润色整合
                  - 限定 300 字以内

                  简历内容：
                  %s
                  """.formatted(
                resumeContent.length() > 4000
                        ? resumeContent.substring(0, 4000) + "...(已截断)"
                        : resumeContent);
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }

}
