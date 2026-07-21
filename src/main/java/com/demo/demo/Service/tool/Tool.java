package com.demo.demo.Service.tool;

import com.google.gson.JsonObject;

/**
 * LLM Function Calling 工具接口。
 * 每个实现类通过 Spring {@code @Component} 自动注册到 {@link ToolRegistry}。
 */
public interface Tool {

    /** 工具名称，LLM 通过此名称匹配 tool_calls。 */
    String name();

    /** 工具功能描述，LLM 据此判断何时调用。 */
    String description();

    /** JSON Schema 格式的参数定义（不含 type: "object" 外层包装）。 */
    JsonObject parameters();

    /** 执行工具，返回给 LLM 的结果文本。 */
    String execute(JsonObject arguments) throws Exception;
}
