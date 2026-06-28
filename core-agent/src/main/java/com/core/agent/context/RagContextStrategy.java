package com.core.agent.context;

/**
 * RAG 场景上下文策略。
 *
 * 检索到的文档片段（Tool Result）优先保留，因为最终答案需要引用来源。
 */
public class RagContextStrategy implements ContextStrategy {

    @Override
    public int priority(MessageBlock block) {
        return switch (block.getType()) {
            case SYSTEM_PROMPT -> 0;
            case TOOL_RESULT -> 1;     // 检索文档优先保留
            case USER_QUESTION -> 2;
            case ASSISTANT_ANSWER -> 3;
            case SYSTEM_META -> 4;
            case THOUGHT -> 5;
        };
    }
}
