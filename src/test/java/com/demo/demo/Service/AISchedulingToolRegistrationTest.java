package com.demo.demo.Service;

import com.demo.demo.Service.scheduling.tool.CreateScheduledTaskTool;
import com.demo.demo.Service.scheduling.tool.ManageScheduledTaskTool;
import com.demo.demo.Service.scheduling.tool.TrustedToolContextInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural verification that all 8 tools are registered and the
 * trusted interceptor is in place.  Does NOT start Spring context.
 */
class AISchedulingToolRegistrationTest {

    // ==================== tool count ====================

    @Test
    void originalSixToolsShouldStillHaveToolAnnotations() {
        // The 6 original tools
        List<Class<?>> originalTools = List.of(
                com.demo.demo.Service.tool.WeatherTool.class,
                com.demo.demo.Service.tool.TimeTool.class,
                com.demo.demo.Service.tool.ImageGenerationTool.class,
                com.demo.demo.Service.tool.VoiceReplyTool.class,
                com.demo.demo.Service.tool.WebSearchTool.class,
                com.demo.demo.Service.tool.EmailTool.class);

        for (Class<?> toolClass : originalTools) {
            boolean hasToolMethod = Arrays.stream(toolClass.getDeclaredMethods())
                    .anyMatch(m -> m.isAnnotationPresent(Tool.class));
            assertTrue(hasToolMethod, toolClass.getSimpleName() + " must have @Tool method");
        }
    }

    @Test
    void newSchedulingToolsShouldHaveToolAnnotations() {
        assertHasToolMethod(CreateScheduledTaskTool.class, "createDailyWeatherTask");
        assertHasToolMethod(ManageScheduledTaskTool.class, "manage");
    }

    @Test
    void createToolMustNotExposeTargetIdAsParam() {
        Method m = findMethod(CreateScheduledTaskTool.class, "createDailyWeatherTask");
        List<String> paramNames = Arrays.stream(m.getParameters())
                .map(p -> p.getName())
                .toList();
        assertFalse(paramNames.contains("targetId"),
                "@Tool method must NOT expose targetId as parameter. Params: " + paramNames);
        assertFalse(paramNames.contains("ownerTargetId"),
                "@Tool method must NOT expose ownerTargetId. Params: " + paramNames);
    }

    @Test
    void manageToolMustNotExposeTargetIdAsParam() {
        Method m = findMethod(ManageScheduledTaskTool.class, "manage");
        List<String> paramNames = Arrays.stream(m.getParameters())
                .map(p -> p.getName())
                .toList();
        assertFalse(paramNames.contains("targetId"),
                "@Tool method must NOT expose targetId. Params: " + paramNames);
    }

    // ==================== interceptor ====================

    @Test
    void trustedInterceptorShouldExtendToolInterceptor() {
        assertTrue(
                com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor.class
                        .isAssignableFrom(TrustedToolContextInterceptor.class),
                "TrustedToolContextInterceptor must extend ToolInterceptor");
    }

    // ==================== AIService constructor ====================

    @Test
    void aiServiceConstructorShouldAcceptAllDependencies() {
        // Verify AIService has a constructor that accepts all 11 dependencies
        var constructors = AIService.class.getConstructors();
        assertTrue(constructors.length >= 1, "AIService must have at least one constructor");

        var constructor = constructors[0];
        Set<String> paramTypes = Arrays.stream(constructor.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        // All 8 tools + 3 infra
        List<String> expected = List.of(
                "ContextManager", "WeatherTool", "TimeTool", "ImageGenerationTool",
                "VoiceReplyTool", "WebSearchTool", "EmailTool",
                "CreateScheduledTaskTool", "ManageScheduledTaskTool",
                "TrustedToolContextInterceptor", "VectorMemoryStore");
        for (String name : expected) {
            assertTrue(paramTypes.contains(name),
                    "AIService constructor missing parameter: " + name);
        }
    }

    // ==================== helpers ====================

    private void assertHasToolMethod(Class<?> clazz, String methodName) {
        Method m = findMethod(clazz, methodName);
        assertTrue(m.isAnnotationPresent(Tool.class),
                clazz.getSimpleName() + "." + methodName + " must have @Tool");
    }

    private Method findMethod(Class<?> clazz, String name) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Method " + name + " not found in " + clazz.getSimpleName()));
    }
}
