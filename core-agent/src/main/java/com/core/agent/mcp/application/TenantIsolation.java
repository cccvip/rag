package com.core.agent.mcp.application;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolDefinition;


import java.util.List;

/**
 * 租户隔离策略。
 *
 * <p>负责按租户/场景过滤工具列表，并校验调用权限。</p>
 */
public interface TenantIsolation {

    /**
     * 过滤该租户在指定场景下可见的工具。
     *
     * @param tools    全量工具定义
     * @param tenantId 租户 ID
     * @param scene    业务场景
     * @return 可见工具列表
     */
    List<ToolDefinition> filterVisibleTools(List<ToolDefinition> tools, String tenantId, String scene);

    /**
     * 校验当前租户是否有权限调用指定工具。
     *
     * @param tool    目标工具
     * @param request MCP 调用请求
     * @return true 表示允许调用
     */
    boolean isAllowedToCall(ToolDefinition tool, ToolCallRequest request);
}
