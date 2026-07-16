package com.core.agent.evaluation.infrastructure;

import com.core.agent.evaluation.domain.EvaluationMetric;
import com.core.agent.evaluation.domain.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 LLM-as-a-Judge 的评估器基类。
 *
 * <p>子类只需提供评估 prompt 和指标名称，基类负责调用 LLM 并解析
 * "Score: x.xx\nReason: ..." 格式的输出。</p>
 */
public abstract class AbstractLlmEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(AbstractLlmEvaluator.class);

    private static final Pattern SCORE_PATTERN = Pattern.compile("Score:\\s*([0-9]*\\.?[0-9]+)");
    private static final Pattern REASON_PATTERN = Pattern.compile("Reason:\\s*(.*)", Pattern.DOTALL);

    protected final ChatModel chatModel;
    protected final String metricName;

    protected AbstractLlmEvaluator(ChatModel chatModel, String metricName) {
        this.chatModel = chatModel;
        this.metricName = metricName;
    }

    @Override
    public EvaluationMetric evaluate(String query, String answer, List<String> contexts) {
        if (chatModel == null) {
            return failed("ChatModel is not available");
        }

        try {
            String promptText = buildPrompt(query, answer, contexts);
            String llmOutput = callLlm(promptText);
            return parseOutput(llmOutput);
        } catch (Exception e) {
            log.error("Failed to evaluate metric {}", metricName, e);
            return failed(e.getMessage());
        }
    }

    /**
     * 子类实现：构建发给 LLM 的评估 prompt。
     */
    protected abstract String buildPrompt(String query, String answer, List<String> contexts);

    private String callLlm(String promptText) {
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));
        return chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getContent();
    }

    private EvaluationMetric parseOutput(String llmOutput) {
        double score = 0.0;
        Matcher scoreMatcher = SCORE_PATTERN.matcher(llmOutput);
        if (scoreMatcher.find()) {
            try {
                score = Double.parseDouble(scoreMatcher.group(1));
                score = Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse score from: {}", llmOutput);
            }
        }

        String reason = "";
        Matcher reasonMatcher = REASON_PATTERN.matcher(llmOutput);
        if (reasonMatcher.find()) {
            reason = reasonMatcher.group(1).trim();
        }

        return EvaluationMetric.builder()
                .name(metricName)
                .score(score)
                .reason(reason)
                .success(true)
                .build();
    }

    private EvaluationMetric failed(String error) {
        return EvaluationMetric.builder()
                .name(metricName)
                .score(0.0)
                .success(false)
                .error(error)
                .build();
    }
}
