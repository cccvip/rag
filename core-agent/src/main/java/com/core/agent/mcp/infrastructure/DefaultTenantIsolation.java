package com.core.agent.mcp.infrastructure;
import com.core.agent.mcp.application.TenantIsolation;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolDefinition;


import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 默认租户隔离实现。
 *
 * <p>按业务场景过滤工具列表，并支持配置租户黑名单/白名单。</p>
 */
public class DefaultTenantIsolation implements TenantIsolation {

    /** 租户可见的场景集合：tenantId -> set of scenes。 */
    private final Set<String> defaultAllowedScenes = ConcurrentHashMap.newKeySet();

    /** 租户黑名单：tenantId -> set of tool names。 */
    private final ConcurrentHashMap<String, Set<String>> tenantToolBlacklist = new ConcurrentHashMap<>();

    public DefaultTenantIsolation() {
        // 默认所有场景对 default-tenant 可见
    }

    /**
     * 允许租户访问指定场景。
     */
    public void allowScene(String tenantId, String scene) {
        tenantToolBlacklist.computeIfAbsent(tenantId, k -> new CopyOnWriteArraySet<>());
    }

    /**
     * 禁止租户调用指定工具。
     */
    public void denyTool(String tenantId, String toolName) {
        tenantToolBlacklist.computeIfAbsent(tenantId, k -> new CopyOnWriteArraySet<>()).add(toolName);
    }

    @Override
    public List<ToolDefinition> filterVisibleTools(List<ToolDefinition> tools, String tenantId, String scene) {
        return tools.stream()
                .filter(tool -> scene == null || tool.getScene().equals(scene))
                .filter(tool -> !isBlacklisted(tenantId, tool.getName()))
                .toList();
    }

    @Override
    public boolean isAllowedToCall(ToolDefinition tool, ToolCallRequest request) {
        if (!tool.getScene().equals(request.getScene())) {
            return false;
        }
        return !isBlacklisted(request.getTenantId(), tool.getName());
    }

    private boolean isBlacklisted(String tenantId, String toolName) {
        Set<String> blacklist = tenantToolBlacklist.get(tenantId);
        return blacklist != null && blacklist.contains(toolName);
    }
}
