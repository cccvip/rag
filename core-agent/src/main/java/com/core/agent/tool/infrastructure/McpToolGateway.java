package com.core.agent.tool.infrastructure;
import com.core.agent.agent.domain.Agent;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolCallResult;


/**
 * Agent 侧工具调用网关接口。
 *
 * <p>抽象 Agent 对工具的调用方式，使 Agent 不必关心工具是本地实现还是远端 REST 服务。</p>
 */
public interface McpToolGateway {

    /**
     * 调用指定工具。
     *
     * @param toolName 工具名称
     * @param input    工具输入（JSON 字符串或纯文本）
     * @param tenantId 租户 ID
     * @param scene    业务场景
     * @return 工具调用结果
     */
    ToolCallResult call(String toolName, String input, String tenantId, String scene);

    /**
     * 通过请求对象调用工具。
     */
    default ToolCallResult call(ToolCallRequest request) {
        return call(request.getName(),
                request.getParams() == null ? null : request.getParams().toString(),
                request.getTenantId(),
                request.getScene());
    }
}
