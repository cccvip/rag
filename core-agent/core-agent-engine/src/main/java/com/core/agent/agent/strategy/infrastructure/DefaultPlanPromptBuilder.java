package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.PlanPromptBuilder;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute 策略的默认 prompt 构建器。
 *
 * <p>本实现只包含通用约束，不携带任何业务场景特定的指令（如楼层、位置、
 * 中文拒答话术等）。如果业务需要更具体的 prompt，应实现 {@link PlanPromptBuilder}
 * 并注入到 {@link PlanAndExecuteStrategy} 中。</p>
 */
public class DefaultPlanPromptBuilder implements PlanPromptBuilder {

    @Override
    public String buildPlanPrompt(String query, List<PlanStep> previousPlan, NodeContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a planning assistant. Given a user question, create a step-by-step plan to solve it using available tools.\n\n");
        sb.append("Available tools:\n");
        sb.append(listToolDescriptions(ctx)).append("\n\n");

        // 重新规划时，带上之前的观察结果
        if (previousPlan != null && !previousPlan.isEmpty()) {
            sb.append("Previous execution observations:\n");
            for (PlanStep step : previousPlan) {
                if (step.getObservation() != null) {
                    sb.append("Step ").append(step.getStepNumber())
                            .append(" [").append(step.getToolName()).append("]: ");
                    sb.append(step.isSuccess() ? "OK" : "FAIL").append(" - ");
                    sb.append(step.getObservation()).append("\n");
                }
            }
            sb.append("\nThe previous plan was insufficient. Please create a new plan that addresses the gaps.\n\n");
        }

        sb.append("User question: ").append(query).append("\n\n");
        sb.append("Output a JSON array of steps. Each step must have:\n");
        sb.append("- stepNumber: integer starting from 1\n");
        sb.append("- toolName: name of the tool to call\n");
        sb.append("- toolInput: input string for the tool\n");
        sb.append("- purpose: brief description of why this step is needed\n\n");
        sb.append("If the question can be answered directly without tools, output an empty array [].\n");
        sb.append("Output only valid JSON, no markdown formatting.");
        return sb.toString();
    }

    @Override
    public String buildFinalAnswerPrompt(String query, List<PlanStep> plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a careful assistant. Based on the following observations, answer the user question.\n\n");
        sb.append("Strict rules:\n");
        sb.append("1. Only use information from the observations below.\n");
        sb.append("2. If the observations do not contain enough information, say you do not have enough information.\n");
        sb.append("3. Do not make up facts not present in the observations.\n");
        sb.append("4. Output in this exact format:\n");
        sb.append("Confidence: HIGH | MEDIUM | LOW\n");
        sb.append("Final Answer: your concise answer\n\n");

        sb.append("User question: ").append(query).append("\n\n");

        if (plan != null && !plan.isEmpty()) {
            sb.append("Observations:\n");
            for (PlanStep step : plan) {
                sb.append("Step ").append(step.getStepNumber())
                        .append(" [").append(step.getToolName()).append("]: ");
                sb.append(step.isSuccess() ? "OK" : "FAIL").append(" - ");
                sb.append(step.getObservation() != null ? step.getObservation() : "no result");
                sb.append("\n");
            }
        } else {
            sb.append("No tool observations available.\n");
        }

        sb.append("\nConfidence: ");
        return sb.toString();
    }

    /**
     * 列出当前场景下所有可用工具的描述信息。
     */
    private String listToolDescriptions(NodeContext ctx) {
        List<String> lines = new ArrayList<>();

        var mcpGateway = ctx.getMcpGateway();
        if (mcpGateway != null) {
            for (var tool : mcpGateway.listTools(ctx.getTenantId(), ctx.getScene())) {
                lines.add("- " + tool.getName()
                        + "(" + tool.getRiskLevel() + "): " + tool.getDescription());
            }
        }

        ToolRegistry registry = ctx.getToolRegistry();
        if (registry != null) {
            for (Tool tool : registry.all()) {
                lines.add("- " + tool.name()
                        + "(" + tool.riskLevel() + "): " + tool.description());
            }
        }

        return lines.isEmpty()
                ? "(no tools available)"
                : lines.stream().collect(Collectors.joining("\n"));
    }
}
