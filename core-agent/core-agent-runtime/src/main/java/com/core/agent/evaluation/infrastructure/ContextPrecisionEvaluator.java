package com.core.agent.evaluation.infrastructure;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文精确度评估器。
 *
 * <p>评估检索到的上下文中有多少是与用户问题相关的。
 * 用于衡量 RAG 检索质量。</p>
 */
public class ContextPrecisionEvaluator extends AbstractLlmEvaluator {

    public ContextPrecisionEvaluator(ChatModel chatModel) {
        super(chatModel, "context_precision");
    }

    @Override
    protected String buildPrompt(String query, String answer, List<String> contexts) {
        String contextText;
        if (contexts == null || contexts.isEmpty()) {
            contextText = "无上下文";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contexts.size(); i++) {
                if (i > 0) {
                    sb.append("\n\n");
                }
                sb.append("[").append(i + 1).append("] ").append(contexts.get(i));
            }
            contextText = sb.toString();
        }

        return "你是一位评估专家。请评估以下检索到的上下文与用户问题的相关程度。\n\n"
                + "用户问题：" + query + "\n\n"
                + "检索到的上下文：\n" + contextText + "\n\n"
                + "请按以下格式输出：\n"
                + "Score: 0.00 到 1.00 之间的数字（1.00 表示所有上下文都高度相关；0.00 表示完全不相关）\n"
                + "Reason: 简要说明得分理由\n\n"
                + "Score: ";
    }
}
