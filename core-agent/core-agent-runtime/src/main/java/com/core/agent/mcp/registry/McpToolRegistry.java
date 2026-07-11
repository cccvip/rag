package com.core.agent.mcp.registry;
import com.core.agent.mcp.application.TenantIsolation;
import com.core.agent.tool.domain.ToolDefinition;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册表。
 *
 * <p>负责维护所有工具定义，支持按名称、按场景、按租户过滤查询。</p>
 */
public class McpToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final TenantIsolation tenantIsolation;

    public McpToolRegistry() {
        this(null, null);
    }

    public McpToolRegistry(List<ToolDefinition> tools) {
        this(tools, null);
    }

    public McpToolRegistry(List<ToolDefinition> tools, TenantIsolation tenantIsolation) {
        this.tenantIsolation = tenantIsolation;
        if (tools != null) {
            tools.forEach(this::register);
        }
    }

    /**
     * 注册一个工具定义。
     */
    public void register(ToolDefinition tool) {
        if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
            throw new IllegalArgumentException("Tool name must not be empty");
        }
        tools.put(tool.getName(), tool);
    }

    /**
     * 根据名称获取工具定义。
     */
    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /**
     * 获取全部工具定义。
     */
    public Collection<ToolDefinition> all() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 按场景过滤工具。
     */
    public List<ToolDefinition> getByScene(String scene) {
        String targetScene = scene == null ? "default" : scene;
        return tools.values().stream()
                .filter(t -> t.getScene().equals(targetScene))
                .toList();
    }

    /**
     * 按租户 + 场景过滤可见工具。
     */
    public List<ToolDefinition> getVisibleTools(String tenantId, String scene) {
        List<ToolDefinition> all = getByScene(scene);
        if (tenantIsolation == null) {
            return all;
        }
        return tenantIsolation.filterVisibleTools(all, tenantId, scene);
    }

    /**
     * 判断指定工具是否存在。
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 清空注册表。
     */
    public void clear() {
        tools.clear();
    }
}
