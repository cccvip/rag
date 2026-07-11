package com.core.agent.tool.domain;
import com.core.agent.shared.model.RiskLevel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP Gateway 工具定义。
 *
 * <p>描述一个远端业务服务暴露的工具，包含 MCP 协议元数据和 REST 路由信息。
 * 业务服务只需暴露普通 REST 接口，由 MCP Gateway 统一负责协议转换。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具名称，Agent 通过该名称调用工具。 */
    private String name;

    /** 工具描述，作为 prompt 的一部分告诉 LLM 该工具能力。 */
    private String description;

    /** 所属业务服务名，由 ServiceResolver 解析为实际地址。 */
    private String service;

    /** 业务服务上的 REST 路径。 */
    private String path;

    /** HTTP 方法，默认 POST。 */
    private String method;

    /** 风险等级，用于 GuardRail 安全管控。 */
    private RiskLevel riskLevel;

    /** 场景标识，如 ops / rag / analytics。 */
    private String scene;

    /** 输入参数 JSON Schema（可选，用于 MCP 协议校验与 LLM 提示）。 */
    private Map<String, Object> inputSchema;

    /** 输出结果 JSON Schema（可选）。 */
    private Map<String, Object> outputSchema;

    /** 扩展参数，如 headers、timeout、retry 等。 */
    private Map<String, String> parameters;

    public String getMethod() {
        return method == null || method.isBlank() ? "POST" : method.toUpperCase();
    }

    public String getScene() {
        return scene == null ? "default" : scene;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel == null ? RiskLevel.LOW : riskLevel;
    }
}
