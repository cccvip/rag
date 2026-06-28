package com.core.agent.context;

/**
 * 上下文内容块类型。
 *
 * 不同类型在上下文窗口管理中具有不同的保留优先级和预算占比。
 */
public enum BlockType {
    /**
     * System Prompt，最高优先级，通常不裁剪。
     */
    SYSTEM_PROMPT,

    /**
     * 用户原始问题。
     */
    USER_QUESTION,

    /**
     * Agent 最终回答（历史轮次）。
     */
    ASSISTANT_ANSWER,

    /**
     * Tool 执行结果 / Observation。
     */
    TOOL_RESULT,

    /**
     * ReAct 中间推理 Thought。
     */
    THOUGHT,

    /**
     * 系统级反思、评判等元信息。
     */
    SYSTEM_META
}
