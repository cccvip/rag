package com.core.agent.context;

/**
 * 运维场景上下文策略。
 *
 * 日志、指标等工具结果优先保留，推理历史可以压缩。
 */
public class OpsContextStrategy implements ContextStrategy {

    @Override
    public int priority(MessageBlock block) {
        return switch (block.getType()) {
            case SYSTEM_PROMPT -> 0;
            case TOOL_RESULT -> 1;     // 日志/指标优先
            case USER_QUESTION -> 2;
            case THOUGHT -> 3;         // 推理历史也相对重要
            case ASSISTANT_ANSWER -> 4;
            case SYSTEM_META -> 5;
        };
    }
}
