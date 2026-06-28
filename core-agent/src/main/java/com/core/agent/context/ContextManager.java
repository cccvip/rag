package com.core.agent.context;

import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文管理器。
 *
 * 职责：
 * - 把历史对话、ReAct 中间状态等内容组织成 {@link MessageBlock}
 * - 按 {@link ContextStrategy} 定义的优先级裁剪 Token
 * - 保证 System Prompt 预留空间，剩余预算从低优先级内容开始丢弃
 * - 输出可直接传给 LLM 的上下文字符串
 */
public class ContextManager {

    private final TokenCountEstimator tokenCountEstimator;

    public ContextManager(TokenCountEstimator tokenCountEstimator) {
        this.tokenCountEstimator = tokenCountEstimator;
    }

    /**
     * 估算文本 Token 数。
     */
    public int estimate(String text) {
        return tokenCountEstimator.estimate(text);
    }

    /**
     * 组装上下文（不包含 System Prompt）。
     *
     * System Prompt 由调用方作为独立的 SystemMessage 传给 LLM，
     * 本方法只负责把其他内容块按策略优先级裁剪并格式化为用户上下文。
     *
     * @param systemPrompt System Prompt 文本（仅用于扣除 Token 预算）
     * @param blocks       其他内容块（已按时间顺序排列）
     * @param strategy     上下文裁剪策略
     * @param maxTokens    最大 Token 预算（包含 System Prompt）
     * @return 组装后的用户上下文字符串
     */
    public String assemble(String systemPrompt, List<MessageBlock> blocks,
                           ContextStrategy strategy, int maxTokens) {
        int systemTokens = tokenCountEstimator.estimate(systemPrompt);
        int availableForBlocks = Math.max(0, maxTokens - systemTokens);

        List<MessageBlock> kept = blocks;
        int totalBlockTokens = blocks.stream().mapToInt(MessageBlock::getTokenCount).sum();

        if (totalBlockTokens > availableForBlocks) {
            int overBudget = totalBlockTokens - availableForBlocks;
            kept = strategy.trim(blocks, overBudget);
        }

        // 保持原始时间顺序
        List<MessageBlock> ordered = new ArrayList<>(kept);
        ordered.sort(Comparator.comparingInt(blocks::indexOf));
        return buildContextString(ordered);
    }

    private String buildContextString(List<MessageBlock> blocks) {
        StringBuilder sb = new StringBuilder();

        for (MessageBlock block : blocks) {
            switch (block.getType()) {
                case USER_QUESTION -> sb.append("User: ").append(block.getContent()).append("\n");
                case ASSISTANT_ANSWER -> sb.append("Assistant: ").append(block.getContent()).append("\n");
                case TOOL_RESULT -> sb.append("Observation: ").append(block.getContent()).append("\n");
                case THOUGHT -> sb.append("Thought: ").append(block.getContent()).append("\n");
                case SYSTEM_META -> sb.append("System Note: ").append(block.getContent()).append("\n");
                default -> sb.append(block.getContent()).append("\n");
            }
        }

        return sb.toString();
    }
}
