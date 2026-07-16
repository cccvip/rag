package com.core.agent.evaluation.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个评估指标结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationMetric {

    /**
     * 指标名称，例如 faithfulness、answer_relevancy、context_precision。
     */
    private String name;

    /**
     * 指标得分，范围 [0, 1]。
     */
    private double score;

    /**
     * 评估理由，由 LLM 生成。
     */
    private String reason;

    /**
     * 是否评估成功。
     */
    private boolean success;

    /**
     * 评估失败时的错误信息。
     */
    private String error;
}
