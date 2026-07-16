package com.core.agent.evaluation.infrastructure;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 答案相关性评估器。
 *
 * <p>评估 Agent 答案是否直接回答了用户问题，而不是答非所问或过度展开。</p>
 */
public class AnswerRelevancyEvaluator extends AbstractLlmEvaluator {

    public AnswerRelevancyEvaluator(ChatModel chatModel) {
        super(chatModel, "answer_relevancy");
    }

    @Override
    protected String buildPrompt(String query, String answer, List<String> contexts) {
        String contextText = contexts == null || contexts.isEmpty()
                ? "无上下文"
                : contexts.stream().collect(Collectors.joining("\n\n---\n\n"));

        return "你是一位评估专家。请评估以下回答对用户问题的相关性。\n\n"
                + "用户问题：" + query + "\n\n"
                + "检索到的上下文：\n" + contextText + "\n\n"
                + "Agent 回答：" + answer + "\n\n"
                + "请按以下格式输出：\n"
                + "Score: 0.00 到 1.00 之间的数字（1.00 表示完全切题；0.00 表示完全无关）\n"
                + "Reason: 简要说明得分理由\n\n"
                + "Score: ";
    }
}
