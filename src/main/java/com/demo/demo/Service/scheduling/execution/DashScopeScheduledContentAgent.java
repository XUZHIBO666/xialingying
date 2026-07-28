package com.demo.demo.Service.scheduling.execution;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.weather.WeatherReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dedicated, side-effect-free content agent for scheduled pushes.
 *
 * <p>Builds a standalone {@link DashScopeChatModel} — no MemorySaver,
 * no vector memory, no tools.  Only formats weather data into a short
 * user-facing message.
 */
@Slf4j
@Component
public class DashScopeScheduledContentAgent implements ScheduledContentAgent {

    private final DashScopeChatModel chatModel;

    public DashScopeScheduledContentAgent(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        this.chatModel = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-max")
                        .withTemperature(0.7)
                        .withMaxToken(300)
                        .build())
                .build();
        log.info("[ScheduledContentAgent] Initialized (no-tool, no-memory)");
    }

    @Override
    public String generate(ScheduledTask task, WeatherReport report) {
        String prompt = buildPrompt(task, report);
        try {
            var response = chatModel.call(new Prompt(prompt));
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("[ScheduledContentAgent] Empty response for {}", task.taskId());
                return null;
            }
            log.debug("[ScheduledContentAgent] Generated {} chars for {}",
                    text.length(), task.taskId());
            return text.trim();
        } catch (Exception e) {
            log.error("[ScheduledContentAgent] Failed for {}: {}", task.taskId(), e.getMessage());
            return null;
        }
    }

    private String buildPrompt(ScheduledTask task, WeatherReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下天气数据，生成一条简短的天气推送消息（不超过100字），语气友好。\n\n");
        sb.append("城市：").append(report.location().name()).append("\n");

        if (report.current() != null) {
            var c = report.current();
            sb.append("当前温度：").append(String.format("%.0f°C", c.temperatureCelsius()))
                    .append("，体感温度：").append(String.format("%.0f°C", c.apparentTemperatureCelsius()))
                    .append("，湿度：").append(c.relativeHumidityPercent()).append("%")
                    .append("，风速：").append(String.format("%.0f km/h", c.windSpeedKmh())).append("\n");
        }

        if (report.forecast() != null) {
            var f = report.forecast();
            sb.append("预报日期：").append(f.date())
                    .append("，最高温：").append(String.format("%.0f°C", f.maxTemperatureCelsius()))
                    .append("，最低温：").append(String.format("%.0f°C", f.minTemperatureCelsius()))
                    .append("，降水概率：").append(f.precipitationProbability()).append("%\n");
        }

        sb.append("\n请直接输出推送消息，不要添加额外说明。");
        return sb.toString();
    }
}
