package com.core.agent.mcp.interfaces.sse;

import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.mcp.registry.McpToolRegistry;
import com.core.agent.tool.domain.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 标准 MCP JSON-RPC 消息处理器测试。
 */
class McpMessageHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private McpMessageHandler createHandler() {
        McpToolRegistry registry = new McpToolRegistry();
        registry.register(com.core.agent.tool.domain.ToolDefinition.builder()
                .name("echo")
                .description("echo tool")
                .service("local")
                .path("/echo")
                .method("POST")
                .riskLevel(com.core.agent.shared.model.RiskLevel.LOW)
                .scene("default")
                .build());

        McpGateway gateway = new McpGateway(registry, service -> null, null, null, 30000, objectMapper);
        return new McpMessageHandler(gateway, objectMapper);
    }

    @Test
    void shouldHandleInitialize() throws Exception {
        McpMessageHandler handler = createHandler();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        String response = handler.handle("session-1", request);

        JsonNode node = objectMapper.readTree(response);
        assertEquals(1, node.get("id").asInt());
        assertNotNull(node.get("result").get("protocolVersion"));
        assertEquals("core-agent-mcp-server", node.get("result").get("serverInfo").get("name").asText());
    }

    @Test
    void shouldHandleToolsList() throws Exception {
        McpMessageHandler handler = createHandler();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        String response = handler.handle("session-1", request);

        JsonNode node = objectMapper.readTree(response);
        assertEquals(2, node.get("id").asInt());
        assertTrue(node.get("result").get("tools").isArray());
    }

    @Test
    void shouldHandleUnknownMethod() throws Exception {
        McpMessageHandler handler = createHandler();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"unknown\"}";
        String response = handler.handle("session-1", request);

        JsonNode node = objectMapper.readTree(response);
        assertEquals(-32601, node.get("error").get("code").asInt());
    }
}
