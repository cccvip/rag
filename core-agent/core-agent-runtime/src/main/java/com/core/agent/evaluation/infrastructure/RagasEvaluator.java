package com.core.agent.evaluation.infrastructure;

import com.core.agent.evaluation.domain.EvaluationResult;
import com.core.agent.evaluation.domain.Evaluator;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 简化版 RAGAS 评估器。
 *
 * <p>组合多个 LLM-as-a-Judge 指标，对 RAG / Agent 回答做综合评估。</p>
 */
public class RagasEvaluator {

    private final List<Evaluator> evaluators;

    public RagasEvaluator(ChatModel chatModel) {
        this(List.of(
                new FaithfulnessEvaluator(chatModel),
                new AnswerRelevancyEvaluator(chatModel),
                new ContextPrecisionEvaluator(chatModel)
        ));
    }

    public RagasEvaluator(List<Evaluator> evaluators) {
        this.evaluators = evaluators == null ? List.of() : evaluators;
    }

    /**
     * 对单个样本进行综合评估。
     */
    public EvaluationResult evaluate(String query, String answer, List<String> contexts) {
        EvaluationResult result = EvaluationResult.builder()
                .query(query)
                .answer(answer)
                .contexts(contexts == null ? List.of() : contexts)
                .metrics(new ArrayList<>())
                .build();

        for (Evaluator evaluator : evaluators) {
            result.getMetrics().add(evaluator.evaluate(query, answer, contexts));
        }

        return result;
    }
}
