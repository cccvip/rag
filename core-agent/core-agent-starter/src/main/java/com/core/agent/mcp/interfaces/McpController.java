package com.core.agent.mcp.interfaces;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolCallResult;
import com.core.agent.tool.domain.ToolDefinition;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP Gateway REST 端点。
 *
 * <p>对外暴露：</p>
 * <ul>
 *     <li>POST /mcp/tools/list - 列出当前租户可见的工具</li>
 *     <li>POST /mcp/tools/call - 调用指定工具</li>
 * </ul>
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private final McpGateway gateway;

    public McpController(McpGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 列出当前租户在指定场景下可见的工具。
     */
    @PostMapping("/tools/list")
    public Map<String, List<ToolDefinition>> listTools(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default-tenant") String tenantId,
            @RequestHeader(value = "X-Scene", defaultValue = "default") String scene) {
        return Map.of("tools", gateway.listTools(tenantId, scene));
    }

    /**
     * 调用指定工具。
     */
    @PostMapping("/tools/call")
    public ToolCallResult callTool(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default-tenant") String tenantId,
            @RequestHeader(value = "X-Scene", defaultValue = "default") String scene,
            @RequestBody ToolCallRequest request) {
        if (request.getTenantId() == null) {
            request.setTenantId(tenantId);
        }
        if (request.getScene() == null) {
            request.setScene(scene);
        }
        return gateway.call(request);
    }
}
