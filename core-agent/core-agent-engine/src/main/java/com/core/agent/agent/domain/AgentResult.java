package com.core.agent.agent.domain;

import com.core.agent.agent.graph.domain.GraphResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 执行结果对象。
 *
 * <p>在保持 {@link Agent#run(String, String)} 兼容性的同时，把最终答案、
 * 置信度、引用、完成状态等元数据一起暴露给调用方。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    /** 最终答案文本 */
    private String answer;

    /** 置信度：HIGH / MEDIUM / LOW / UNKNOWN */
    private String confidence;

    /** 答案中引用的文档编号列表，如 [doc-1001] */
    private List<String> citations;

    /** 是否成功生成最终答案 */
    private boolean completed;

    /** 是否等待人工审批 */
    private boolean awaitingApproval;

    /** 人工审批 checkpoint token */
    private String checkpointToken;

    /** 错误信息（当 completed=false 且非审批状态时） */
    private String errorMessage;

    /** 整个任务是否成功完成 */
    private boolean success;

    private static final Pattern DOC_CITATION_PATTERN = Pattern.compile("\\[doc-([^\\]]+)\\]");

    /**
     * 从状态图执行结果构造 AgentResult。
     */
    public static AgentResult fromGraphResult(GraphResult result) {
        if (result == null) {
            return unknown("Agent execution halted without final answer.");
        }

        if (result.isAwaitingApproval()) {
            return AgentResult.builder()
                    .answer("Awaiting human approval. Checkpoint: " + result.getCheckpointToken())
                    .confidence("UNKNOWN")
                    .citations(Collections.emptyList())
                    .awaitingApproval(true)
                    .checkpointToken(result.getCheckpointToken())
                    .build();
        }

        if (result.getErrorMessage() != null) {
            return AgentResult.builder()
                    .answer("Agent execution failed: " + result.getErrorMessage())
                    .confidence("LOW")
                    .citations(Collections.emptyList())
                    .errorMessage(result.getErrorMessage())
                    .build();
        }

        if (result.isCompleted()) {
            String answer = result.getFinalAnswer();
            String confidence = extractConfidence(result);
            List<String> citations = extractCitations(answer);
            Boolean answerSuccess = result.getFinalState() != null
                    ? result.getFinalState().getVariable("answerSuccess")
                    : null;
            boolean success = answerSuccess != null ? answerSuccess : !"LOW".equals(confidence);
            return AgentResult.builder()
                    .answer(answer)
                    .confidence(confidence)
                    .citations(citations)
                    .completed(true)
                    .success(success)
                    .build();
        }

        return unknown("Agent execution halted without final answer.");
    }

    private static AgentResult unknown(String answer) {
        return AgentResult.builder()
                .answer(answer)
                .confidence("UNKNOWN")
                .citations(Collections.emptyList())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static String extractConfidence(GraphResult result) {
        if (result.getFinalState() != null) {
            String confidence = result.getFinalState().getVariable("answerConfidence");
            if (confidence != null && !confidence.isBlank()) {
                return confidence;
            }
        }
        return "MEDIUM";
    }

    /**
     * 从文本中提取 [doc-xxxx] 引用编号。
     */
    public static List<String> extractCitations(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> citations = new ArrayList<>();
        Matcher matcher = DOC_CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String docId = matcher.group(1).trim();
            String citation = "doc-" + docId;
            if (!citations.contains(citation)) {
                citations.add(citation);
            }
        }
        return citations;
    }
}
