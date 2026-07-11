package com.core.agent.mcp.infrastructure;
import com.core.agent.mcp.application.ProtocolConverter;
import com.core.agent.mcp.application.ServiceResolver;
import com.core.agent.mcp.application.TenantIsolation;
import com.core.agent.mcp.registry.McpToolRegistry;
import com.core.agent.shared.exception.McpException;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolCallResult;
import com.core.agent.tool.domain.ToolDefinition;
import com.core.agent.tool.infrastructure.McpToolGateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * MCP Gateway 核心实现。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>工具注册与发现</li>
 *     <li>按租户/场景过滤工具列表</li>
 *     <li>MCP 协议 ↔ REST 协议转换</li>
 *     <li>将工具调用路由转发到对应后端服务</li>
 * </ul>
 */
public class McpGateway implements McpToolGateway {

    private static final Logger log = LoggerFactory.getLogger(McpGateway.class);

    private final McpToolRegistry toolRegistry;
    private final ServiceResolver serviceResolver;
    private final ProtocolConverter protocolConverter;
    private final TenantIsolation tenantIsolation;
    private final int timeoutMs;
    private final ObjectMapper objectMapper;

    public McpGateway(McpToolRegistry toolRegistry,
                      ServiceResolver serviceResolver,
                      ProtocolConverter protocolConverter,
                      TenantIsolation tenantIsolation,
                      int timeoutMs) {
        this(toolRegistry, serviceResolver, protocolConverter, tenantIsolation, timeoutMs, new ObjectMapper());
    }

    public McpGateway(McpToolRegistry toolRegistry,
                      ServiceResolver serviceResolver,
                      ProtocolConverter protocolConverter,
                      TenantIsolation tenantIsolation,
                      int timeoutMs,
                      ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.serviceResolver = serviceResolver;
        this.protocolConverter = protocolConverter;
        this.tenantIsolation = tenantIsolation;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper;
    }

    /**
     * 列出当前租户在指定场景下可见的工具。
     */
    public List<ToolDefinition> listTools(String tenantId, String scene) {
        String targetScene = scene == null ? "default" : scene;
        List<ToolDefinition> all = toolRegistry.getByScene(targetScene);
        if (tenantIsolation == null) {
            return all;
        }
        return tenantIsolation.filterVisibleTools(all,
                tenantId == null ? "default-tenant" : tenantId, targetScene);
    }

    /**
     * 执行 MCP 工具调用。
     */
    @Override
    public ToolCallResult call(String toolName, String input, String tenantId, String scene) {
        long start = System.currentTimeMillis();
        try {
            ToolDefinition tool = toolRegistry.get(toolName);
            if (tool == null) {
                return ToolCallResult.fail("Tool not found: " + toolName);
            }

            ToolCallRequest request = ToolCallRequest.builder()
                    .name(toolName)
                    .params(parseParams(input))
                    .tenantId(tenantId)
                    .scene(scene)
                    .build();

            if (tenantIsolation != null && !tenantIsolation.isAllowedToCall(tool, request)) {
                return ToolCallResult.fail("Tool " + toolName + " is not allowed for tenant " + tenantId);
            }

            return invokeBackend(tool, request, start);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Failed to call tool {} for tenant {}", toolName, tenantId, e);
            return ToolCallResult.fail(e.getMessage(), duration);
        }
    }

    @Override
    public ToolCallResult call(ToolCallRequest request) {
        return call(request.getName(),
                request.getParams() == null ? null : request.getParams().toString(),
                request.getTenantId(),
                request.getScene());
    }

    private ToolCallResult invokeBackend(ToolDefinition tool, ToolCallRequest request, long start) {
        HttpURLConnection connection = null;
        try {
            URL baseUrl = serviceResolver.resolve(tool.getService());
            URL requestUrl = protocolConverter.buildRequestUrl(baseUrl, tool, request);

            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            protocolConverter.prepareConnection(connection, tool, request);

            String responseBody = protocolConverter.parseResponse(connection);
            long duration = System.currentTimeMillis() - start;
            log.debug("Tool {} called successfully in {} ms", tool.getName(), duration);
            return ToolCallResult.ok(responseBody, duration);
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - start;
            return ToolCallResult.fail("IO error calling tool " + tool.getName() + ": " + e.getMessage(), duration);
        } catch (McpException e) {
            long duration = System.currentTimeMillis() - start;
            return ToolCallResult.fail("Service resolution failed: " + e.getMessage(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolCallResult.fail("Unexpected error: " + e.getMessage(), duration);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JsonNode parseParams(String input) {
        if (input == null || input.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(input);
        } catch (Exception e) {
            // 非 JSON 输入包装为 { "input": "..." }
            return objectMapper.valueToTree(new java.util.HashMap<String, String>() {{
                put("input", input);
            }});
        }
    }
}
