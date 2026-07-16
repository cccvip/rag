package com.core.agent.evaluation.infrastructure;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 忠诚度评估器。
 *
 * <p>评估 Agent 答案中的信息是否都能在检索到的上下文中找到依据。
 * 这是 RAG 场景最核心的指标，用于检测幻觉。</p>
 */
public class FaithfulnessEvaluator extends AbstractLlmEvaluator {

    public FaithfulnessEvaluator(ChatModel chatModel) {
        super(chatModel, "faithfulness");
    }

    @Override
    protected String buildPrompt(String query, String answer, List<String> contexts) {
        String contextText = contexts == null || contexts.isEmpty()
                ? "无上下文"
                : contexts.stream().collect(Collectors.joining("\n\n---\n\n"));

        return "你是一位严格的评估专家。请评估以下回答对给定上下文的忠诚度，即回答中的每个事实是否都能在上下文中找到依据。\n\n"
                + "用户问题：" + query + "\n\n"
                + "检索到的上下文：\n" + contextText + "\n\n"
                + "Agent 回答：" + answer + "\n\n"
                + "请按以下格式输出：\n"
                + "Score: 0.00 到 1.00 之间的数字（1.00 表示完全基于上下文，无幻觉；0.00 表示完全胡说）\n"
                + "Reason: 简要说明得分理由\n\n"
                + "Score: ";
    }
}
