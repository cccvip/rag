package com.core.agent.evaluation;

import com.core.agent.evaluation.domain.EvaluationMetric;
import com.core.agent.evaluation.domain.EvaluationResult;
import com.core.agent.evaluation.infrastructure.RagasEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAGAS 简化版评估器测试。
 */
class RagasEvaluatorTest {

    @Test
    void shouldEvaluateRagSample() {
        ChatModel mockModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String text = prompt.getInstructions().get(0).getContent();
                String response;
                if (text.contains("忠诚度")) {
                    response = "Score: 0.95\nReason: 答案完全基于上下文，无幻觉。";
                } else if (text.contains("相关性")) {
                    response = "Score: 0.90\nReason: 答案直接回答了问题。";
                } else {
                    response = "Score: 0.85\nReason: 大部分上下文与问题相关。";
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.empty();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };

        RagasEvaluator evaluator = new RagasEvaluator(mockModel);

        EvaluationResult result = evaluator.evaluate(
                "3楼化学泄漏如何撤离？",
                "应从3楼东侧楼梯向下撤离，禁止乘坐电梯。",
                List.of(
                        "[doc-1001] 3楼东侧楼梯为消防疏散通道。",
                        "[doc-1002] 化学泄漏事故禁止乘坐电梯。"
                )
        );

        assertEquals("3楼化学泄漏如何撤离？", result.getQuery());
        assertEquals(3, result.getMetrics().size());
        assertTrue(result.getAverageScore() > 0.8, "平均分应大于 0.8");
        assertTrue(result.isPassed(0.8), "应通过 0.8 阈值");

        for (EvaluationMetric metric : result.getMetrics()) {
            assertTrue(metric.isSuccess(), "指标 " + metric.getName() + " 应评估成功");
            assertFalse(metric.getReason().isBlank(), "指标应有评估理由");
        }
    }

    @Test
    void shouldHandleEmptyContexts() {
        ChatModel mockModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String response = "Score: 0.00\nReason: 无上下文，无法验证。";
                return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.empty();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };

        RagasEvaluator evaluator = new RagasEvaluator(mockModel);
        EvaluationResult result = evaluator.evaluate("问题", "答案", List.of());

        assertEquals(3, result.getMetrics().size());
        assertEquals(0.0, result.getAverageScore(), 0.001);
    }
}
