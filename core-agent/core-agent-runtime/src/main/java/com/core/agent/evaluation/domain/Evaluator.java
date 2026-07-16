package com.core.agent.evaluation.domain;

import java.util.List;

/**
 * RAG / Agent 回答评估器接口。
 *
 * <p>参考 RAGAS 指标设计，但用 Java + LLM-as-a-Judge 方式实现，
 * 便于在 Java AI 中台中落地。</p>
 */
public interface Evaluator {

    /**
     * 评估单个回答。
     *
     * @param query    用户问题
     * @param answer   Agent 生成的答案
     * @param contexts 检索到的上下文片段
     * @return 评估结果
     */
    EvaluationMetric evaluate(String query, String answer, List<String> contexts);
}
