package com.core.agent.tool;

import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.infrastructure.ToolFunctionCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool → Spring AI FunctionCallback 适配器测试。
 */
class ToolFunctionCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldExposeToolMetadata() {
        Tool tool = new TestTool();
        ToolFunctionCallback callback = new ToolFunctionCallback(tool);

        assertEquals("test_tool", callback.getName());
        assertEquals("A test tool", callback.getDescription());
        assertNotNull(callback.getInputTypeSchema());
        assertTrue(callback.getInputTypeSchema().contains("input"));
    }

    @Test
    void shouldCallToolWithJsonInput() throws Exception {
        Tool tool = new TestTool();
        ToolFunctionCallback callback = new ToolFunctionCallback(tool);

        String result = callback.call("{\"input\":\"hello\"}");
        JsonNode node = mapper.readTree(result);

        assertEquals("processed: hello", node.get("result").asText());
    }

    @Test
    void shouldCallToolWithPlainInput() throws Exception {
        Tool tool = new TestTool();
        ToolFunctionCallback callback = new ToolFunctionCallback(tool);

        String result = callback.call("plain text");
        JsonNode node = mapper.readTree(result);

        assertEquals("processed: plain text", node.get("result").asText());
    }

    private static class TestTool implements Tool {
        @Override
        public String name() {
            return "test_tool";
        }

        @Override
        public String description() {
            return "A test tool";
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.LOW;
        }

        @Override
        public String execute(String input) {
            return "processed: " + input;
        }
    }
}
