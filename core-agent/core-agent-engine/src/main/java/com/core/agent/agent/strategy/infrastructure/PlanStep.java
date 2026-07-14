package com.core.agent.agent.strategy.infrastructure;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Plan-and-Execute 策略中的单个执行步骤。
 *
 * <p>由 LLM 在规划节点生成，由执行节点按顺序消费。
 * 每个步骤只描述“调用什么工具、传入什么参数、期望得到什么”，
 * 不包含具体执行逻辑。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {

    /**
     * 步骤序号，从 1 开始，用于保证执行顺序和日志可读性。
     */
    private int stepNumber;

    /**
     * 要调用的工具名称。
     *
     * <p>可以是本地 {@link com.core.agent.tool.domain.Tool} 的名称，
     * 也可以是 MCP Gateway 中注册的 {@link com.core.agent.tool.domain.ToolDefinition} 名称。</p>
     */
    private String toolName;

    /**
     * 调用该工具时传入的参数（字符串形式）。
     *
     * <p>具体格式由工具决定，通常是 JSON 或自然语言查询串。</p>
     */
    private String toolInput;

    /**
     * 该步骤的目的说明。
     *
     * <p>仅用于调试和评估节点判断是否需要重新规划，不传递给工具。</p>
     */
    private String purpose;

    /**
     * 工具执行后的结果（ Observation ）。
     *
     * <p>由执行节点填充，供后续步骤或最终答案生成使用。</p>
     */
    private String observation;

    /**
     * 标记该步骤是否执行成功。
     *
     * <p>执行失败不会直接导致整个任务失败，
     * 而是把失败信息作为 observation 交给评估节点决定下一步。</p>
     */
    private boolean success;

    /**
     * 执行耗时（毫秒），用于指标统计。
     */
    private long durationMs;

    /**
     * 创建一个新的、未执行的步骤副本（保留规划信息，清空执行结果）。
     */
    public PlanStep resetExecutionResult() {
        PlanStep copy = new PlanStep();
        copy.stepNumber = this.stepNumber;
        copy.toolName = this.toolName;
        copy.toolInput = this.toolInput;
        copy.purpose = this.purpose;
        copy.observation = null;
        copy.success = false;
        copy.durationMs = 0;
        return copy;
    }
}
