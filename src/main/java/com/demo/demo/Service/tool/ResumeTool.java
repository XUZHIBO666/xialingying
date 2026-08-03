package com.demo.demo.Service.tool;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.Resume.ResumeService;
import com.demo.demo.model.Resume;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ResumeTool {

    @Resource
    private ResumeService resumeService;

    @Lazy
    @Resource
    private AIService aiService;

    @Tool(description = "分析用户最新上传的简历，从HR视角给出修改建议。当用户说「分析我的简历」" +
            "「看看我的简历有什么问题」「简历优化」等时使用。")
    public String analyzeResume(ToolContext toolContext) {
        String userId = contextValue(toolContext, "user_id");
        Resume resume = resumeService.getLatestResume(userId);
        if (resume == null) {
            return "你还没有上传简历。请发送 PDF 或 Word 格式的简历文件。";
        }

        String content = resumeService.getEffectiveContent(resume.getId());
        String prompt = """
                  你是资深职业顾问。分析这份简历，给出修改建议：
                  1. 缺失的关键信息
                  2. 可量化的业绩描述建议
                  3. 技能描述的优化建议
                  4. 模板化的改写参考（给出一个段落示例）

                  限制 250 字以内，使用友好的口吻。

                  简历：
                  """ + (content.length() > 3000 ? content.substring(0, 3000) : content);

        String analysis = aiService.chat(userId, prompt);
        if (analysis != null && !analysis.isBlank()) {
            resumeService.saveAnalysisResult(resume.getId(), analysis);
        }
        return analysis != null ? analysis : "分析失败，请稍后重试。";
    }

    @Tool(description = "根据用户提供的补充信息，更新并润色简历的某个部分。" +
            "当用户说「帮我补充...」「加上...经历」「更新简历」等时使用。" +
            "必填参数：supplement（用户想补充的内容）和 section（要修改的部分，如 工作经历/项目经验/技能/教育背景）")
    public String supplementResume(
            @ToolParam(description = "用户补充的具体内容") String supplement,
            @ToolParam(description = "要修改的简历部分，如：工作经历、项目经验、技能、教育背景、自我评价") String section,
            ToolContext toolContext) {

        String userId = contextValue(toolContext, "user_id");
        Resume resume = resumeService.getLatestResume(userId);
        if (resume == null) {
            return "你还没有上传简历。请先发送 PDF 或 Word 格式的简历文件。";
        }

        String currentContent = resumeService.getEffectiveContent(resume.getId());
        String prompt = """
                  你是简历优化专家。用户当前的简历内容是：

                  ---
                  %s
                  ---

                  用户想修改「%s」部分，补充内容如下：
                  「%s」

                  请：
                  1. 将补充内容用 STAR 法则和专业语言润色
                  2. 整合到对应部分
                  3. 输出优化后的完整段落（只输出该部分，不需要完整简历）

                  限定 200 字以内。
                  """.formatted(
                currentContent.length() > 2000 ? currentContent.substring(0, 2000) : currentContent,
                section, supplement);

        String optimized = aiService.chat(userId, prompt);
        if (optimized != null && !optimized.isBlank()) {
            // 拼接：把优化后的段落合并回原内容
            String merged = currentContent + "\n\n【已优化 - " + section + "】\n" + optimized;
            resumeService.saveOptimizedContent(resume.getId(), merged);
        }
        return optimized != null
                ? "✅ 已更新「" + section + "」部分：\n\n" + optimized
                : "更新失败，请稍后重试。";
    }

    @Tool(description = "查看用户当前简历的完整内容。当用户说「看看我的简历」「我的简历」等时使用。")
    public String getResume(ToolContext toolContext) {
        String userId = contextValue(toolContext, "user_id");
        Resume resume = resumeService.getLatestResume(userId);
        if (resume == null) {
            return "你还没有上传简历。请发送 PDF 或 Word 格式的简历文件。";
        }
        String content = resumeService.getEffectiveContent(resume.getId());
        String status = resume.getStatus();
        String statusText = switch (status) {
            case "PENDING_ANALYSIS" -> "待分析";
            case "ANALYZED" -> "已分析";
            case "OPTIMIZED" -> "已优化";
            default -> status;
        };
        return "📋 你的简历（状态：" + statusText + "，文件名：" + resume.getResumeName()
                + "）\n\n" + (content.length() > 3000 ? content.substring(0, 3000) + "..." : content);
    }

    // ==================== 工具方法 ====================

    private static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null) {
            throw new IllegalArgumentException("缺少可信工具上下文");
        }
        Map<String, Object> ctx = toolContext.getContext();
        Object value = ctx.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        return text;
    }
}

