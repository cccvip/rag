package com.core.agent.evaluation.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG / Agent 回答的评估结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {

    /**
     * 用户问题。
     */
    private String query;

    /**
     * Agent 生成的答案。
     */
    private String answer;

    /**
     * 检索到的上下文（文档片段）。
     */
    private List<String> contexts;

    /**
     * 各指标得分。
     */
    @Builder.Default
    private List<EvaluationMetric> metrics = new ArrayList<>();

    /**
     * 综合得分，所有指标的平均值。
     */
    public double getAverageScore() {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }
        return metrics.stream()
                .filter(EvaluationMetric::isSuccess)
                .mapToDouble(EvaluationMetric::getScore)
                .average()
                .orElse(0.0);
    }

    /**
     * 是否所有指标都通过阈值。
     */
    public boolean isPassed(double threshold) {
        if (metrics == null || metrics.isEmpty()) {
            return false;
        }
        return metrics.stream()
                .filter(EvaluationMetric::isSuccess)
                .allMatch(m -> m.getScore() >= threshold);
    }
}
