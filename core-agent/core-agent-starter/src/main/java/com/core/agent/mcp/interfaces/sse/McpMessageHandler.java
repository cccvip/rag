package com.core.agent.mcp.interfaces.sse;

import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolCallResult;
import com.core.agent.tool.domain.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 标准 MCP JSON-RPC 消息处理器。
 *
 * <p>当前支持：</p>
 * <ul>
 *     <li>initialize - 协议握手</li>
 *     <li>tools/list - 列出工具</li>
 *     <li>tools/call - 调用工具</li>
 * </ul>
 */
@Component
public class McpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(McpMessageHandler.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpGateway gateway;
    private final ObjectMapper objectMapper;

    public McpMessageHandler(McpGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public String handle(String sessionId, String message) {
        try {
            JsonNode request = objectMapper.readTree(message);
            JsonNode idNode = request.get("id");
            String method = request.has("method") ? request.get("method").asText() : "";

            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (idNode != null && !idNode.isNull()) {
                response.set("id", idNode);
            }

            switch (method) {
                case "initialize":
                    response.set("result", buildInitializeResult());
                    break;
                case "tools/list":
                    response.set("result", buildToolsListResult("default-tenant", "default"));
                    break;
                case "tools/call":
                    response.set("result", handleToolCall(request.get("params")));
                    break;
                default:
                    response.set("error", buildError(-32601, "Method not found: " + method));
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Failed to handle MCP message: {}", message, e);
            return buildErrorResponse(e.getMessage());
        }
    }

    private ObjectNode buildInitializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("listChanged", false);
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);

        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", "core-agent-mcp-server");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);

        return result;
    }

    private ObjectNode buildToolsListResult(String tenantId, String scene) {
        List<ToolDefinition> tools = gateway.listTools(tenantId, scene);
        ArrayNode toolsArray = objectMapper.createArrayNode();

        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            toolNode.set("inputSchema", buildInputSchema());
            toolsArray.add(toolNode);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolsArray);
        return result;
    }

    private ObjectNode buildInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("type", "string");
        input.put("description", "Tool input parameters as JSON string");
        properties.set("input", input);
        schema.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("input");
        schema.set("required", required);
        return schema;
    }

    private ObjectNode handleToolCall(JsonNode params) {
        String name = params.has("name") ? params.get("name").asText() : "";
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
        String input = arguments.has("input") ? arguments.get("input").asText() : arguments.toString();

        ToolCallResult callResult = gateway.call(name, input, "default-tenant", "default");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("content", callResult.isSuccess()
                ? callResult.getData()
                : ("Error: " + callResult.getError()));
        result.put("isError", !callResult.isSuccess());
        return result;
    }

    private ObjectNode buildError(int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    private String buildErrorResponse(String message) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("error", buildError(-32700, message));
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"serialization error\"}}";
        }
    }
}
