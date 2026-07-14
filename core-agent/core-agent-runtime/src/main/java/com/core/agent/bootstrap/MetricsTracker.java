package com.core.agent.bootstrap;
import com.core.agent.tenant.domain.ToolCallRecord;
import com.core.agent.tool.domain.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(MetricsTracker.class);

    private boolean taskSuccess = false;
    private int stepCount = 0;
    private int toolCallCount = 0;
    private int toolCallSuccessCount = 0;
    private int repeatedActionCount = 0;
    private int highRiskAttemptCount = 0;
    private int highRiskBlockedCount = 0;
    private int planCount = 0;
    private int replanCount = 0;
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
     * 记录一次规划调用。
     */
    public void recordPlan() {
        planCount++;
    }

    /**
     * 记录一次重新规划。
     */
    public void recordReplan() {
        replanCount++;
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
     * 获取已召回文档 ID 集合（只读副本）。
     */
    public Set<String> getRetrievedDocIds() {
        return new HashSet<>(retrievedDocIds);
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
        StringBuilder report = new StringBuilder();
        report.append("\n========== Agent Evaluation Metrics ==========\n");
        report.append("Task Success Rate     : ").append(taskSuccess ? "100%" : "0%").append("\n");
        report.append("Total Steps           : ").append(stepCount).append("\n");
        report.append("Avg Steps             : ").append(stepCount).append("\n");
        report.append("Plan Count            : ").append(planCount).append("\n");
        report.append("Replan Count          : ").append(replanCount).append("\n");
        report.append("Tool Call Success Rate: ")
                .append(formatRate(toolCallSuccessCount, toolCallCount)).append("\n");
        report.append("Repeated Actions      : ").append(repeatedActionCount).append("\n");
        report.append("High-risk Interception: ")
                .append(formatRate(highRiskBlockedCount, highRiskAttemptCount)).append("\n");
        report.append("Citation Accuracy     : ")
                .append(citationAccuracy == null ? "N/A" : String.format("%.2f%%", citationAccuracy * 100)).append("\n");
        report.append("Total Latency         : ").append(totalLatencyMs).append(" ms\n");
        report.append("LLM Total Tokens      : ").append(llmTotalTokens).append("\n");
        report.append("================================================\n");
        log.info(report.toString());
    }

    private String formatRate(long numerator, long denominator) {
        if (denominator == 0) {
            return "N/A";
        }
        return String.format("%.2f%%", numerator * 100.0 / denominator);
    }
}
