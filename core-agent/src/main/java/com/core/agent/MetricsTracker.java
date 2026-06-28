package com.core.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 评测指标追踪器。
 *
 * 记录指标：
 * - 任务成功率
 * - 平均步数
 * - 工具调用成功率
 * - 重复动作次数
 * - 证据引用准确率
 * - 高风险动作拦截率
 * - 成本和延迟
 */
public class MetricsTracker {

    private boolean taskSuccess = false;
    private int stepCount = 0;
    private int toolCallCount = 0;
    private int toolCallSuccessCount = 0;
    private int repeatedActionCount = 0;
    private int highRiskAttemptCount = 0;
    private int highRiskBlockedCount = 0;
    private long totalLatencyMs = 0;
    private long llmTotalTokens = 0;
    private Double citationAccuracy = null;

    private String lastAction = null;
    private final List<ToolCallRecord> records = new ArrayList<>();
    private final Set<String> retrievedDocIds = new HashSet<>();

    /**
     * 记录一步 Agent 推理。
     */
    public void recordStep() {
        stepCount++;
    }

    /**
     * 记录一次工具调用。
     */
    public void recordToolCall(String toolName, String input, String output,
                               boolean success, long latencyMs, boolean blocked) {
        toolCallCount++;
        if (success) {
            toolCallSuccessCount++;
        }
        if (blocked) {
            highRiskBlockedCount++;
        }
        if (toolName.equals(lastAction)) {
            repeatedActionCount++;
        }
        lastAction = toolName;
        totalLatencyMs += latencyMs;
        records.add(new ToolCallRecord(toolName, input, output, success, latencyMs, blocked));
    }

    /**
     * 记录一次高风险动作尝试。
     */
    public void recordHighRiskAttempt() {
        highRiskAttemptCount++;
    }

    /**
     * 记录 LLM 调用开销。
     */
    public void recordLlmCall(Long promptTokens, Long generationTokens, Long totalTokens, long latencyMs) {
        totalLatencyMs += latencyMs;
        llmTotalTokens += (totalTokens != null ? totalTokens : 0);
    }

    /**
     * 记录检索召回的文档 ID，用于后续引用准确率计算。
     */
    public void recordRetrievedDocs(String docsOutput) {
        // 简单解析：假设文档输出中包含 [doc-xxxx] 格式的文档 ID
        Matcher matcher = Pattern.compile("\\[doc-([^\\]]+)\\]").matcher(docsOutput);
        while (matcher.find()) {
            retrievedDocIds.add(matcher.group(1));
        }
    }

    /**
     * 根据最终答案中的引用标记和已召回文档，计算证据引用准确率。
     */
    public void computeCitationAccuracy(String finalAnswer) {
        Matcher matcher = Pattern.compile("\\[doc-([^\\]]+)\\]").matcher(finalAnswer);
        List<String> citations = new ArrayList<>();
        while (matcher.find()) {
            citations.add(matcher.group(1));
        }

        if (citations.isEmpty()) {
            this.citationAccuracy = null;
            return;
        }

        long matched = citations.stream()
                .filter(retrievedDocIds::contains)
                .count();
        this.citationAccuracy = (double) matched / citations.size();
    }

    public void setTaskSuccess(boolean success) {
        this.taskSuccess = success;
    }

    /**
     * 打印评测报告。
     */
    public void printReport() {
        System.out.println("\n========== Agent Evaluation Metrics ==========");
        System.out.println("Task Success Rate     : " + (taskSuccess ? "100%" : "0%"));
        System.out.println("Total Steps           : " + stepCount);
        System.out.println("Avg Steps             : " + stepCount);
        System.out.println("Tool Call Success Rate: " +
                formatRate(toolCallSuccessCount, toolCallCount));
        System.out.println("Repeated Actions      : " + repeatedActionCount);
        System.out.println("High-risk Interception: " +
                formatRate(highRiskBlockedCount, highRiskAttemptCount));
        System.out.println("Citation Accuracy     : " +
                (citationAccuracy == null ? "N/A" : String.format("%.2f%%", citationAccuracy * 100)));
        System.out.println("Total Latency         : " + totalLatencyMs + " ms");
        System.out.println("LLM Total Tokens      : " + llmTotalTokens);
        System.out.println("================================================\n");
    }

    private String formatRate(long numerator, long denominator) {
        if (denominator == 0) {
            return "N/A";
        }
        return String.format("%.2f%%", numerator * 100.0 / denominator);
    }
}
