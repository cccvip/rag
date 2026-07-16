package com.core.agent.agent.strategy.domain;

import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.infrastructure.PlanStep;

import java.util.List;

/**
 * Plan-and-Execute 策略的 prompt 构建器。
 *
 * <p>负责把用户问题、执行步骤、观察结果转换为发送给 LLM 的文本 prompt。
 * 通过抽取这个接口，业务场景可以注入自己的 prompt 模板（例如 RAG 的引用格式、
 * 特定领域的拒答话术），而 {@link com.core.agent.agent.strategy.infrastructure.PlanAndExecuteStrategy}
 * 本身只保留通用的状态图编排逻辑。</p>
 */
public interface PlanPromptBuilder {

    /**
     * 构建规划节点使用的 prompt。
     *
     * @param query        用户原始问题
     * @param previousPlan 之前的计划步骤（重新规划时携带已有的观察结果，首次规划时为 null 或空列表）
     * @param ctx          当前节点上下文
     * @return 发送给 LLM 的 prompt 文本
     */
    String buildPlanPrompt(String query, List<PlanStep> previousPlan, NodeContext ctx);

    /**
     * 构建最终答案节点使用的 prompt。
     *
     * @param query 用户原始问题
     * @param plan  已执行的计划步骤（包含观察结果）
     * @return 发送给 LLM 的 prompt 文本
     */
    String buildFinalAnswerPrompt(String query, List<PlanStep> plan);
}
