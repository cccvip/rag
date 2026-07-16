package com.core.agent.tool.infrastructure;

import com.core.agent.tool.domain.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.Map;

/**
 * 把 CoreAgent 的 {@link Tool} 适配成 Spring AI 的 {@link FunctionCallback}。
 *
 * <p>这样 LLM 可以通过标准 Function Calling 机制调用 CoreAgent 注册的工具，
 * 而不是依赖字符串匹配。</p>
 */
public class ToolFunctionCallback implements FunctionCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolFunctionCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tool tool;

    public ToolFunctionCallback(Tool tool) {
        this.tool = tool;
    }

    @Override
    public String getName() {
        return tool.name();
    }

    @Override
    public String getDescription() {
        return tool.description();
    }

    /**
     * 返回工具的 JSON Schema。
     *
     * <p>为兼容现有 {@link Tool#execute(String)} 接口，统一暴露一个 string 类型
     * 的 {@code input} 参数。业务工具若需要结构化输入，可在 execute 内部解析 JSON。</p>
     */
    @Override
    public String getInputTypeSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "input": {
                      "type": "string",
                      "description": "工具输入参数"
                    }
                  },
                  "required": ["input"]
                }
                """;
    }

    /**
     * 被 Spring AI 调用时执行工具。
     *
     * @param functionInput LLM 生成的 JSON 参数
     * @return 工具执行结果
     */
    @Override
    public String call(String functionInput) {
        try {
            String input = extractInput(functionInput);
            String result = tool.execute(input);
            return MAPPER.writeValueAsString(Map.of("result", result));
        } catch (Exception e) {
            log.error("Failed to execute tool {} via function calling", tool.name(), e);
            try {
                return MAPPER.writeValueAsString(Map.of("error", e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\":\"serialization failed\"}";
            }
        }
    }

    private String extractInput(String functionInput) {
        if (functionInput == null || functionInput.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = MAPPER.readValue(functionInput, Map.class);
            Object input = map.get("input");
            return input == null ? functionInput : input.toString();
        } catch (Exception e) {
            // 不是 JSON 或没有 input 字段时，把整个字符串当作输入
            return functionInput;
        }
    }
}
