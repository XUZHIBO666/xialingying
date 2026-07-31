package com.demo.demo.config;


import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration // 必须加 @Configuration 注解，否则 Spring 不会识别为配置类
public class SkillsConfig {

    /**
     * 注册 SkillRegistry Bean：扫描 resources/skills/ 目录下的所有技能
     */
    @Bean
    public SkillRegistry skillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath("skills") // 扫描 resources/skills/ 目录（包含 weather、websearch 等子目录）
                .build();
    }

    /**
     * 注册 SkillsAgentHook Bean：依赖上面定义的 SkillRegistry
     */
    @Bean
    public SkillsAgentHook skillsAgentHook(SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .build();
    }
}
