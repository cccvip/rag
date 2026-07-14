package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentMessage;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolCallResult;
import com.core.agent.tool.domain.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute 执行策略。
 *
 * <p>与 ReAct "边想边做" 不同，Plan-and-Execute 会先让 LLM 生成一份完整的步骤计划，
 * 再按顺序执行每个步骤，最后评估结果是否充分。如果不够充分，则带着已有的观察结果重新规划。</p>
 *
 * <p>这种状态图形态更适合：</p>
 * <ul>
 *     <li>多跳检索：RAG 场景下需要先后查询多个不同来源</li>
 *     <li>确定性流程：步骤之间依赖关系清晰，不希望 LLM 每一步都重新决策</li>
 *     <li>成本敏感场景：减少 LLM 调用次数，一次规划 + 一次评估</li>
 * </ul>
 */
public class PlanAndExecuteStrategy implements ExecutionStrategy<NodeContext> {

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecuteStrategy.class);

    /**
     * Jackson 对象映射器，用于把 LLM 输出的 JSON 计划解析为 {@link PlanStep} 列表。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 状态变量 key：当前计划列表。
     */
    private static final String KEY_PLAN = "plan";

    /**
     * 状态变量 key：当前执行到的步骤索引。
     */
    private static final String KEY_CURRENT_STEP = "currentStepIndex";

    /**
     * 状态变量 key：已重新规划的次数。
     *
     * <p>防止无限循环：超过阈值后直接给出当前最佳答案。</p>
     */
    private static final String KEY_REPLAN_COUNT = "replanCount";

    /**
     * 状态变量 key：用户原始问题。
     */
    private static final String KEY_QUERY = "query";

    /**
     * 最大允许重新规划次数。
     */
    private static final int MAX_REPLAN = 2;

    /**
     * LLM 调用超时时间（秒）。
     */
    private static final int LLM_TIMEOUT_SECONDS = 60;

    private final String scene;

    public PlanAndExecuteStrategy(String scene) {
        this.scene = scene;
    }

    @Override
    public String name() {
        return "plan-and-execute";
    }

    /**
     * 编译生成 Plan-and-Execute 状态图。
     *
     * <p>图结构：</p>
     * <pre>
     * START -> planNode -> executeNode -> evaluateNode
     *                          ^              |
     *                          |              v
     *                          └──── 需要重新规划 ──┘
     *                                         |
     *                                         v
     *                                      endNode
     * </pre>
     *
     * <p>每个节点只负责一件明确的事，符合状态图设计哲学：</p>
     * <ul>
     *     <li>planNode：生成步骤计划</li>
     *     <li>executeNode：按顺序执行所有步骤</li>
     *     <li>evaluateNode：判断结果是否充分，决定结束或重新规划</li>
     *     <li>endNode：生成最终答案</li>
     * </ul>
     */
    @Override
    public AgentGraph<NodeContext> compile() {
        return AgentGraph.<NodeContext>builder()
                .startNode("planNode")
                .addNode("planNode", new PlanNode())
                .addNode("executeNode", new ExecuteNode())
                .addNode("evaluateNode", new EvaluateNode())
                .addNode("endNode", new EndNode())
                // 规划完成后进入执行节点
                .addEdge("planNode", "executeNode")
                // 执行完成后进入评估节点
                .addEdge("executeNode", "evaluateNode")
                // 评估认为需要重新规划时，回到 planNode
                .addConditionalEdge("evaluateNode", "planNode",
                        state -> Boolean.TRUE.equals(state.getVariable("needReplan")))
                // 评估认为可以结束时，进入 endNode
                .addConditionalEdge("evaluateNode", "endNode",
                        state -> !Boolean.TRUE.equals(state.getVariable("needReplan")))
                .endNode("endNode")
                .maxSteps(20)
                .build();
    }

    /**
     * 规划节点：让 LLM 根据用户问题和已有观察，生成下一步执行计划。
     *
     * <p>如果是首次规划，只基于 query；如果是重新规划，还会带上之前步骤的 observation，
     * 让 LLM 能够利用已获取的信息进行调整。</p>
     */
    private class PlanNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "planNode";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String query = state.getVariable(KEY_QUERY);
            List<PlanStep> previousPlan = state.getVariable(KEY_PLAN);
            int replanCount = state.getVariable(KEY_REPLAN_COUNT) == null
                    ? 0 : (int) state.getVariable(KEY_REPLAN_COUNT);

            if (query == null) {
                return state.error("Missing query in state");
            }

            log.debug("Planning for query: {}, replanCount: {}", query, replanCount);

            // 构造规划 prompt
            String planPrompt = buildPlanPrompt(query, previousPlan, ctx);

            try {
                String llmOutput = callLlm(ctx, planPrompt);
                List<PlanStep> plan = parsePlan(llmOutput);

                if (plan.isEmpty()) {
                    // LLM 没有生成任何步骤，直接让评估节点去生成最终答案
                    return state.withVariable(KEY_PLAN, new ArrayList<PlanStep>())
                            .withVariable(KEY_CURRENT_STEP, 0)
                            .withVariable(KEY_REPLAN_COUNT, replanCount + 1)
                            .withVariable("needReplan", false)
                            .withMessage(AgentMessage.thought("No plan steps generated, will answer directly."));
                }

                return state.withVariable(KEY_PLAN, plan)
                        .withVariable(KEY_CURRENT_STEP, 0)
                        .withVariable(KEY_REPLAN_COUNT, replanCount + 1)
                        .withVariable("needReplan", false)
                        .withMessage(AgentMessage.thought("Plan generated with " + plan.size() + " steps."));

            } catch (Exception e) {
                log.error("Failed to generate plan", e);
                return state.error("Plan generation failed: " + e.getMessage());
            }
        }

        /**
         * 构建规划 prompt。
         *
         * <p>要求 LLM 输出 JSON 数组，每个元素包含 stepNumber、toolName、toolInput、purpose。</p>
         */
        private String buildPlanPrompt(String query, List<PlanStep> previousPlan, NodeContext ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append("You are a planning assistant. Given a user question, create a step-by-step plan to solve it using available tools.\n\n");
            sb.append("Available tools:\n");
            sb.append(listToolDescriptions(ctx)).append("\n\n");

            // 重新规划时，带上之前的观察结果
            if (previousPlan != null && !previousPlan.isEmpty()) {
                sb.append("Previous execution observations:\n");
                for (PlanStep step : previousPlan) {
                    sb.append("Step ").append(step.getStepNumber())
                            .append(" [").append(step.getToolName()).append("]: ");
                    if (step.getObservation() != null) {
                        sb.append(step.getObservation());
                    } else {
                        sb.append("(not executed)");
                    }
                    sb.append("\n");
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

        /**
         * 解析 LLM 返回的 JSON 计划。
         *
         * <p>做一些容错处理：去除 markdown 代码块标记、trim 空白。</p>
         */
        private List<PlanStep> parsePlan(String llmOutput) throws Exception {
            String cleaned = llmOutput.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?\\s*", "")
                        .replaceAll("\\s*```$", "");
            }
            return MAPPER.readValue(cleaned, new TypeReference<List<PlanStep>>() {});
        }
    }

    /**
     * 执行节点：按顺序执行规划节点生成的所有步骤。
     *
     * <p>每个步骤执行后，把 observation 写回 {@link PlanStep}，
     * 并把更新后的计划列表写回 {@link AgentState}。</p>
     */
    private class ExecuteNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "executeNode";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            List<PlanStep> plan = state.getVariable(KEY_PLAN);
            if (plan == null || plan.isEmpty()) {
                // 没有计划步骤，直接跳过执行进入评估
                return state.withVariable("executionSummary", "No steps to execute.");
            }

            log.debug("Executing plan with {} steps", plan.size());

            List<PlanStep> executedPlan = new ArrayList<>();
            StringBuilder summary = new StringBuilder();

            for (PlanStep step : plan) {
                PlanStep executed = executeStep(step, ctx);
                executedPlan.add(executed);

                summary.append("Step ").append(executed.getStepNumber())
                        .append(" [").append(executed.getToolName()).append("]: ");
                if (executed.isSuccess()) {
                    summary.append("OK - ").append(executed.getObservation());
                } else {
                    summary.append("FAIL - ").append(executed.getObservation());
                }
                summary.append("\n");
            }

            return state.withVariable(KEY_PLAN, executedPlan)
                    .withVariable(KEY_CURRENT_STEP, executedPlan.size())
                    .withVariable("executionSummary", summary.toString())
                    .withMessage(AgentMessage.observation(summary.toString()));
        }

        /**
         * 执行单个步骤。
         *
         * <p>支持两种工具来源：</p>
         * <ul>
         *     <li>本地 {@link ToolRegistry} 中的 {@link Tool}</li>
         *     <li>MCP Gateway 中注册的远端工具</li>
         * </ul>
         */
        private PlanStep executeStep(PlanStep step, NodeContext ctx) {
            long start = System.currentTimeMillis();
            PlanStep result = step.resetExecutionResult();

            String tenantId = ctx.getTenantId();
            String sceneName = scene != null ? scene : ctx.getScene();
            String toolName = step.getToolName();
            String toolInput = step.getToolInput();

            try {
                // 1. 先尝试从本地 ToolRegistry 查找
                ToolRegistry registry = ctx.getToolRegistry();
                if (registry != null && registry.get(toolName) != null) {
                    Tool tool = registry.get(toolName);

                    // GuardRail 检查
                    if (!isToolAllowed(tool, ctx.getGuardRail(), tenantId, ctx.getUserId())) {
                        result.setObservation("Blocked by GuardRail: tool '" + toolName + "' is " + tool.riskLevel());
                        result.setSuccess(false);
                        result.setDurationMs(System.currentTimeMillis() - start);
                        return result;
                    }

                    String observation = tool.execute(toolInput);
                    result.setObservation(observation);
                    result.setSuccess(!observation.startsWith("Error"));
                    result.setDurationMs(System.currentTimeMillis() - start);
                    return result;
                }

                // 2. 再尝试通过 MCP Gateway 调用远端工具
                McpGateway mcpGateway = ctx.getMcpGateway();
                if (mcpGateway != null) {
                    ToolCallResult callResult = mcpGateway.call(toolName, toolInput, tenantId, sceneName);
                    if (callResult.isSuccess()) {
                        result.setObservation(callResult.getData());
                        result.setSuccess(true);
                    } else {
                        result.setObservation("Error: " + callResult.getError());
                        result.setSuccess(false);
                    }
                    result.setDurationMs(System.currentTimeMillis() - start);
                    return result;
                }

                // 3. 都没找到
                result.setObservation("Error: tool '" + toolName + "' not found in local registry or MCP Gateway");
                result.setSuccess(false);
                result.setDurationMs(System.currentTimeMillis() - start);
                return result;

            } catch (Exception e) {
                log.error("Failed to execute step {} tool {}", step.getStepNumber(), toolName, e);
                result.setObservation("Error: step execution failed: " + e.getMessage());
                result.setSuccess(false);
                result.setDurationMs(System.currentTimeMillis() - start);
                return result;
            }
        }
    }

    /**
     * 评估节点：判断当前执行结果是否足以回答问题。
     *
     * <p>让 LLM 做一个二分类决策：</p>
     * <ul>
     *     <li>结果充分 → 路由到 endNode</li>
     *     <li>结果不充分 → 标记 needReplan=true，路由回 planNode</li>
     * </ul>
     */
    private class EvaluateNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "evaluateNode";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String query = state.getVariable(KEY_QUERY);
            List<PlanStep> plan = state.getVariable(KEY_PLAN);
            int replanCount = state.getVariable(KEY_REPLAN_COUNT) == null
                    ? 0 : (int) state.getVariable(KEY_REPLAN_COUNT);
            String summary = state.getVariable("executionSummary");

            // 超过最大重新规划次数，强制结束，避免无限循环
            if (replanCount > MAX_REPLAN) {
                log.warn("Max replan count reached, forcing completion");
                return state.withVariable("needReplan", false)
                        .withVariable("forceComplete", true)
                        .withMessage(AgentMessage.system("Max replan reached, will provide best-effort answer."));
            }

            // 没有步骤且直接回答的场景
            if ((plan == null || plan.isEmpty()) && (summary == null || summary.isBlank())) {
                return state.withVariable("needReplan", false)
                        .withMessage(AgentMessage.system("No plan and no observations, answer directly."));
            }

            String evalPrompt = buildEvaluatePrompt(query, plan, summary);

            try {
                String llmOutput = callLlm(ctx, evalPrompt).trim().toLowerCase();
                boolean needReplan = llmOutput.contains("replan") || llmOutput.contains("insufficient");

                log.debug("Evaluation result: needReplan={}", needReplan);

                return state.withVariable("needReplan", needReplan)
                        .withVariable("evaluationResult", llmOutput)
                        .withMessage(AgentMessage.system("Evaluation: " + llmOutput));

            } catch (Exception e) {
                log.error("Failed to evaluate result", e);
                // 评估失败时保守处理：直接结束，避免卡住
                return state.withVariable("needReplan", false)
                        .withVariable("evaluationResult", "Evaluation failed: " + e.getMessage());
            }
        }

        private String buildEvaluatePrompt(String query, List<PlanStep> plan, String summary) {
            StringBuilder sb = new StringBuilder();
            sb.append("You are evaluating whether the executed plan has gathered enough information to answer the user question.\n\n");
            sb.append("User question: ").append(query).append("\n\n");

            if (summary != null && !summary.isBlank()) {
                sb.append("Execution summary:\n").append(summary).append("\n");
            }

            if (plan != null && !plan.isEmpty()) {
                sb.append("Detailed observations:\n");
                for (PlanStep step : plan) {
                    sb.append("Step ").append(step.getStepNumber())
                            .append(" [").append(step.getToolName()).append("]: ");
                    sb.append(step.isSuccess() ? "OK" : "FAIL").append(" - ");
                    sb.append(step.getObservation() != null ? step.getObservation() : "no observation");
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("Based on the observations, can the user question be answered completely and accurately?\n");
            sb.append("Reply with exactly one word:\n");
            sb.append("- 'sufficient' if the question can be answered\n");
            sb.append("- 'replan' if more information is needed\n");
            return sb.toString();
        }
    }

    /**
     * 结束节点：根据所有观察结果生成最终答案。
     */
    private class EndNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "endNode";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String query = state.getVariable(KEY_QUERY);
            List<PlanStep> plan = state.getVariable(KEY_PLAN);

            StringBuilder sb = new StringBuilder();
            sb.append("Based on the following observations, answer the user question concisely.\n\n");
            sb.append("User question: ").append(query).append("\n\n");

            if (plan != null && !plan.isEmpty()) {
                sb.append("Observations:\n");
                for (PlanStep step : plan) {
                    sb.append("Step ").append(step.getStepNumber())
                            .append(" [").append(step.getToolName()).append("]: ");
                    sb.append(step.getObservation() != null ? step.getObservation() : "no result");
                    sb.append("\n");
                }
            } else {
                sb.append("No tool observations available. Answer based on your knowledge.\n");
            }

            sb.append("\nFinal Answer:");

            try {
                String finalAnswer = callLlm(ctx, sb.toString());
                return state.completed(finalAnswer);
            } catch (Exception e) {
                log.error("Failed to generate final answer", e);
                String fallback = "Failed to generate final answer: " + e.getMessage();
                return state.completed(fallback);
            }
        }
    }

    /**
     * 调用 LLM，带简单超时控制。
     *
     * <p>使用 {@link CompletableFuture} 包装同步调用，避免 LLM 挂起导致整个图执行卡住。</p>
     */
    private String callLlm(NodeContext ctx, String promptText) throws Exception {
        ChatModel chatModel = ctx.getChatModel();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel is not available");
        }

        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            ChatResponse response = chatModel.call(prompt);
            return response.getResult().getOutput().getContent();
        });

        return future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 列出当前场景下所有可用工具的描述信息。
     *
     * <p>同时包含本地 ToolRegistry 和 MCP Gateway 中的工具，
     * 供规划 prompt 使用。</p>
     */
    private String listToolDescriptions(NodeContext ctx) {
        List<String> lines = new ArrayList<>();
        String tenantId = ctx.getTenantId();
        String sceneName = scene != null ? scene : ctx.getScene();

        McpGateway mcpGateway = ctx.getMcpGateway();
        if (mcpGateway != null) {
            for (var tool : mcpGateway.listTools(tenantId, sceneName)) {
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

    /**
     * 检查工具是否允许被当前租户/用户调用。
     *
     * <p>只做 GuardRail 风险等级校验，不替代具体业务权限判断。</p>
     */
    private boolean isToolAllowed(Tool tool,
                                  com.core.agent.guardrail.domain.GuardRail guardRail,
                                  String tenantId, String userId) {
        if (guardRail == null) {
            return true;
        }
        return guardRail.allow(tool, tenantId, userId);
    }
}
