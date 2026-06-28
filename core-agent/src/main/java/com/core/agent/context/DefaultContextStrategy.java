package com.core.agent.context;

/**
 * 默认上下文策略。
 *
 * 适合通用 QA 场景，System Prompt 和用户问题优先保留，推理历史可压缩。
 */
public class DefaultContextStrategy implements ContextStrategy {

    @Override
    public int priority(MessageBlock block) {
        return switch (block.getType()) {
            case SYSTEM_PROMPT -> 0;   // 永不裁剪
            case USER_QUESTION -> 1;   // 用户问题优先
            case ASSISTANT_ANSWER -> 2;
            case TOOL_RESULT -> 3;
            case SYSTEM_META -> 4;
            case THOUGHT -> 5;         // 推理历史最后保留
        };
    }
}
