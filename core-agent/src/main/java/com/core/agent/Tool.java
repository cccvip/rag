package com.core.agent;

/**
 * Tool 接口：每个 Agent 可调用的工具都需要实现此接口。
 */
public interface Tool {

    /**
     * 工具名称，Agent 通过名称调用该工具。
     */
    String name();

    /**
     * 工具描述，会作为 prompt 的一部分告诉 LLM 这个工具能做什么。
     */
    String description();

    /**
     * 工具风险等级，用于 GuardRail 安全管控。
     */
    RiskLevel riskLevel();

    /**
     * 执行工具逻辑。
     *
     * @param input 工具输入参数
     * @return 工具执行结果，会作为 Observation 返回给 LLM
     */
    String execute(String input);
}
