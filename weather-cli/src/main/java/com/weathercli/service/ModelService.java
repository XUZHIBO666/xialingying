package com.weathercli.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型服务 — 列出当前可用的 AI 大模型及 API Key 申请指引。
 *
 * 任务 1: 找可用的模型，申请 API Key
 */
public class ModelService {

    /**
     * 获取所有可用模型列表。
     */
    public List<ModelInfo> getAvailableModels() {
        List<ModelInfo> models = new ArrayList<>();

        // === 国内模型 ===
        models.add(new ModelInfo(
            "DeepSeek",
            "深度求索",
            "DeepSeek-V3, DeepSeek-R1",
            "https://platform.deepseek.com/api_keys",
            "https://platform.deepseek.com",
            "注册即送 500 万 tokens 免费额度；API 价格极低",
            "国内"
        ));

        models.add(new ModelInfo(
            "通义千问 (Qwen)",
            "阿里云",
            "Qwen-Max, Qwen-Plus, Qwen-Turbo",
            "https://dashscope.console.aliyun.com/apiKey",
            "https://dashscope.aliyun.com",
            "新用户有免费额度；通过阿里云 DashScope 平台接入",
            "国内"
        ));

        models.add(new ModelInfo(
            "文心一言 (ERNIE)",
            "百度",
            "ERNIE-4.0, ERNIE-3.5",
            "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application",
            "https://qianfan.cloud.baidu.com",
            "百度智能云千帆平台；有免费调用额度",
            "国内"
        ));

        models.add(new ModelInfo(
            "智谱 AI (GLM)",
            "智谱华章",
            "GLM-4, GLM-4V, GLM-3-Turbo",
            "https://open.bigmodel.cn/usercenter/apikeys",
            "https://open.bigmodel.cn",
            "注册赠送额度；支持多模态",
            "国内"
        ));

        models.add(new ModelInfo(
            "Moonshot (月之暗面)",
            "Moonshot AI",
            "moonshot-v1-8k/32k/128k",
            "https://platform.moonshot.cn/console/api-keys",
            "https://platform.moonshot.cn",
            "Kimi 同款模型；长文本处理能力强",
            "国内"
        ));

        models.add(new ModelInfo(
            "零一万物 (Yi)",
            "零一万物",
            "Yi-Large, Yi-Medium, Yi-Spark",
            "https://platform.lingyiwanwu.com/apikeys",
            "https://platform.lingyiwanwu.com",
            "新用户有免费额度",
            "国内"
        ));

        // === 国外模型 ===
        models.add(new ModelInfo(
            "OpenAI",
            "OpenAI",
            "GPT-4o, GPT-4o-mini, GPT-4-Turbo",
            "https://platform.openai.com/api-keys",
            "https://platform.openai.com",
            "需海外手机号注册；付费使用，按 token 计费",
            "国外"
        ));

        models.add(new ModelInfo(
            "Anthropic (Claude)",
            "Anthropic",
            "Claude Opus 4.8, Claude Sonnet 5, Claude Haiku 4.5",
            "https://console.anthropic.com/settings/keys",
            "https://console.anthropic.com",
            "需海外手机号注册；Claude 系列模型",
            "国外"
        ));

        models.add(new ModelInfo(
            "Google Gemini",
            "Google",
            "Gemini 2.5 Pro, Gemini 2.5 Flash",
            "https://aistudio.google.com/app/apikey",
            "https://aistudio.google.com",
            "Google AI Studio 提供免费额度",
            "国外"
        ));

        models.add(new ModelInfo(
            "Groq",
            "Groq",
            "Llama 3.3 70B, Mixtral 8x7B",
            "https://console.groq.com/keys",
            "https://console.groq.com",
            "免费额度慷慨；推理速度极快（LPU 芯片）",
            "国外"
        ));

        return models;
    }

    /**
     * 打印所有可用模型列表。
     */
    public void printModelList() {
        List<ModelInfo> models = getAvailableModels();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║            可用的 AI 大模型  &  API Key 申请指引            ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        // 国内模型
        System.out.println("║  【国内模型 — 推荐】                                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        for (ModelInfo m : models) {
            if ("国内".equals(m.getRegion())) {
                printModelRow(m);
            }
        }

        // 国外模型
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  【国外模型】                                               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        for (ModelInfo m : models) {
            if ("国外".equals(m.getRegion())) {
                printModelRow(m);
            }
        }

        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 申请步骤提示
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│                  API Key 申请通用步骤                        │");
        System.out.println("├──────────────────────────────────────────────────────────────┤");
        System.out.println("│ 1. 访问对应平台官网注册账号                                  │");
        System.out.println("│ 2. 完成实名认证（国内平台通常需要）                          │");
        System.out.println("│ 3. 进入控制台 → API Keys 管理页面                            │");
        System.out.println("│ 4. 创建新的 API Key 并妥善保存                               │");
        System.out.println("│ 5. 将 API Key 配置到项目环境变量或配置文件中                  │");
        System.out.println("│                                                              │");
        System.out.println("│ ⚠ 注意：API Key 属于敏感信息，切勿提交到 Git 仓库！         │");
        System.out.println("│   建议使用环境变量：export API_KEY=sk-xxxx                    │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private void printModelRow(ModelInfo m) {
        System.out.printf("║  %-12s | %-6s | %-24s    ║%n",
            m.getName(), m.getProvider(), m.getKeyModels());
        System.out.printf("║  API Key: %-48s ║%n", m.getApiKeyUrl());
        System.out.printf("║  备注: %-52s ║%n", m.getNote());
        System.out.println("║                                                              ║");
    }

    // ---- 内部数据类 ----

    public static class ModelInfo {
        private final String name;
        private final String provider;
        private final String keyModels;
        private final String apiKeyUrl;
        private final String website;
        private final String note;
        private final String region;

        public ModelInfo(String name, String provider, String keyModels,
                         String apiKeyUrl, String website, String note, String region) {
            this.name = name;
            this.provider = provider;
            this.keyModels = keyModels;
            this.apiKeyUrl = apiKeyUrl;
            this.website = website;
            this.note = note;
            this.region = region;
        }
        public String getName() { return name; }
        public String getProvider() { return provider; }
        public String getKeyModels() { return keyModels; }
        public String getApiKeyUrl() { return apiKeyUrl; }
        public String getWebsite() { return website; }
        public String getNote() { return note; }
        public String getRegion() { return region; }
    }
}
