package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentMessage;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.agent.strategy.domain.PlanPromptBuilder;
import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolCallResult;
import com.core.agent.tool.domain.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plan-and-Execute 执行策略。
 *
 * <p>与 ReAct "边想边做" 不同，Plan-and-Execute 会先让 LLM 生成一份完整的步骤计划，
 * 再按顺序执行每个步骤，最后根据执行结果决定结束或重新规划。</p>
 *
 * <p>本实现遵循 Ranjan Kumar《Why Your AI Agent Finishes Tasks But Fails the Goal》
 * 一文中提出的设计原则：</p>
 * <ul>
 *     <li><b>重新规划是最后手段</b>：只有计划假设被证明错误时才 replan，
 *         单步失败用局部结果兜底，不把 replan 当错误恢复</li>
 *     <li><b>保留已完成任务</b>：replan 时只重写未执行步骤，已完成的 observation 保留</li>
 *     <li><b>预算硬上限</b>：replan 次数、LLM 调用次数、step 数都有上限</li>
 *     <li><b>在自然里程碑判断</b>：不在每个步骤后判断，而是在整轮执行完成后统一决策</li>
 * </ul>
 *
 * <p>这种状态图形态更适合：</p>
 * <ul>
 *     <li>多跳检索：RAG 场景下需要先后查询多个不同来源</li>
 *     <li>确定性流程：步骤之间依赖关系清晰，不希望 LLM 每一步都重新决策</li>
 *     <li>成本敏感场景：减少 LLM 调用次数</li>
 * </ul>
 *
 * <p>prompt 文本通过 {@link PlanPromptBuilder} 注入，本类默认使用 {@link DefaultPlanPromptBuilder}。
 * 业务场景（如 RAG）可以自定义 builder，把特定输出格式、引用规范、拒答话术等隔离在策略之外。</p>
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
     * 状态变量 key：LLM 调用次数。
     *
     * <p>用于预算控制，避免单次请求无限调用 LLM。</p>
     */
    private static final String KEY_LLM_CALL_COUNT = "llmCallCount";

    /**
     * 状态变量 key：执行结果摘要。
     */
    private static final String KEY_EXECUTION_SUMMARY = "executionSummary";

    /**
     * 最大允许重新规划次数。
     *
     * <p>参照生产实践中的硬上限原则，防止目标漂移和无限循环。</p>
     */
    private static final int MAX_REPLAN = 2;

    /**
     * 单次请求最大 LLM 调用次数。
     *
     * <p>Plan（1）+ End（1）+ 每轮 replan 的 Plan（1）= 最多 3 次。</p>
     */
    private static final int MAX_LLM_CALLS = 5;

    /**
     * 状态图最大执行步数。
     */
    private static final int MAX_GRAPH_STEPS = 20;

    /**
     * LLM 调用超时时间（秒）。
     */
    private static final int LLM_TIMEOUT_SECONDS = 60;

    private final String scene;

    private final PlanPromptBuilder promptBuilder;

    public PlanAndExecuteStrategy(String scene) {
        this(scene, new DefaultPlanPromptBuilder());
    }

    public PlanAndExecuteStrategy(String scene, PlanPromptBuilder promptBuilder) {
        this.scene = scene;
        this.promptBuilder = promptBuilder != null ? promptBuilder : new DefaultPlanPromptBuilder();
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
     * START -> planNode -> executeNode -> replanGateNode
     *                          ^                  |
     *                          |                  v
     *                          └──── 需要重新规划 ──┘
     *                                             |
     *                                             v
     *                                          endNode
     * </pre>
     */
    @Override
    public AgentGraph<NodeContext> compile() {
        return AgentGraph.<NodeContext>builder()
                .startNode("planNode")
                .addNode("planNode", new PlanNode())
                .addNode("executeNode", new ExecuteNode())
                .addNode("replanGateNode", new ReplanGateNode())
                .addNode("endNode", new EndNode())
                // 规划完成后进入执行节点
                .addEdge("planNode", "executeNode")
                // 执行完成后进入重新规划决策门
                .addEdge("executeNode", "replanGateNode")
                // 决策门认为需要重新规划时，回到 planNode
                .addConditionalEdge("replanGateNode", "planNode",
                        state -> Boolean.TRUE.equals(state.getVariable("needReplan")))
                // 决策门认为可以结束时，进入 endNode
                .addConditionalEdge("replanGateNode", "endNode",
                        state -> !Boolean.TRUE.equals(state.getVariable("needReplan")))
                .endNode("endNode")
                .maxSteps(MAX_GRAPH_STEPS)
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
            int replanCount = getReplanCount(state);
            int llmCallCount = getLlmCallCount(state);

            if (query == null) {
                return state.error("Missing query in state");
            }

            // 预算检查：LLM 调用次数上限
            if (llmCallCount >= MAX_LLM_CALLS) {
                log.warn("LLM call budget exhausted, forcing completion");
                return state.withVariable("needReplan", false)
                        .withVariable("budgetExceeded", true)
                        .withMessage(AgentMessage.system("LLM call budget exhausted."));
            }

            log.debug("Planning for query: {}, replanCount: {}", query, replanCount);

            // 构造规划 prompt
            String planPrompt = promptBuilder.buildPlanPrompt(query, previousPlan, ctx);

            try {
                String llmOutput = callLlm(ctx, planPrompt);
                List<PlanStep> plan = parsePlan(llmOutput);

                // 记录指标
                MetricsTracker metrics = ctx.getMetricsTracker();
                if (metrics != null) {
                    if (replanCount == 0) {
                        metrics.recordPlan();
                    } else {
                        metrics.recordReplan();
                    }
                }

                if (plan.isEmpty()) {
                    // LLM 没有生成任何步骤，直接让结束节点基于已有信息回答
                    return state.withVariable(KEY_PLAN, new ArrayList<PlanStep>())
                            .withVariable(KEY_CURRENT_STEP, 0)
                            .withVariable(KEY_REPLAN_COUNT, replanCount + 1)
                            .withVariable(KEY_LLM_CALL_COUNT, llmCallCount + 1)
                            .withVariable("needReplan", false)
                            .withMessage(AgentMessage.thought("No plan steps generated, will answer directly."));
                }

                return state.withVariable(KEY_PLAN, plan)
                        .withVariable(KEY_CURRENT_STEP, 0)
                        .withVariable(KEY_REPLAN_COUNT, replanCount + 1)
                        .withVariable(KEY_LLM_CALL_COUNT, llmCallCount + 1)
                        .withVariable("needReplan", false)
                        .withMessage(AgentMessage.thought("Plan generated with " + plan.size() + " steps."));

            } catch (Exception e) {
                log.error("Failed to generate plan", e);
                return state.error("Plan generation failed: " + e.getMessage());
            }
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
     *
     * <p>注意：本节点只做执行，不做重新规划判断。
     * 单步失败不会导致整个任务失败，而是把失败信息作为 observation 交给决策门处理。</p>
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
                // 没有计划步骤，直接跳过执行进入决策门
                return state.withVariable(KEY_EXECUTION_SUMMARY, "No steps to execute.");
            }

            log.debug("Executing plan with {} steps", plan.size());

            List<PlanStep> executedPlan = new ArrayList<>();
            StringBuilder summary = new StringBuilder();

            for (PlanStep step : plan) {
                PlanStep executed = executeStep(step, ctx);
                executedPlan.add(executed);

                // 记录检索召回的文档 ID，用于最终答案的引用校验
                if (executed.isSuccess() && executed.getObservation() != null
                        && ctx.getMetricsTracker() != null) {
                    ctx.getMetricsTracker().recordRetrievedDocs(executed.getObservation());
                }

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
                    .withVariable(KEY_EXECUTION_SUMMARY, summary.toString())
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
                    if (ctx.getGuardRail() != null && !ctx.getGuardRail().allow(tool, tenantId, ctx.getUserId())) {
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
     * 重新规划决策门：根据执行结果决定是否重新规划。
     *
     * <p>核心设计原则：</p>
     * <ul>
     *     <li><b>不因为单步失败就 replan</b>：部分步骤成功时，用已有结果直接回答</li>
     *     <li><b>计划假设错误时才 replan</b>：所有步骤都失败，或所有结果都为空</li>
     *     <li><b>预算耗尽时强制结束</b>：避免无限循环</li>
     * </ul>
     *
     * <p>这个节点是确定性的，不需要 LLM 调用，从而把 Plan-and-Execute 的
     * 标准 LLM 调用次数降到 2 次（Plan + End）。</p>
     */
    private class ReplanGateNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "replanGateNode";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            List<PlanStep> plan = state.getVariable(KEY_PLAN);
            int replanCount = getReplanCount(state);

            // 没有计划步骤，直接结束
            if (plan == null || plan.isEmpty()) {
                log.debug("No plan steps, route to end node");
                return state.withVariable("needReplan", false)
                        .withVariable("replanReason", "no_plan");
            }

            // 预算检查：replan 次数上限
            if (replanCount > MAX_REPLAN) {
                log.warn("Max replan count reached, forcing completion");
                return state.withVariable("needReplan", false)
                        .withVariable("replanReason", "max_replan_exceeded");
            }

            long successCount = plan.stream().filter(PlanStep::isSuccess).count();
            long nonEmptyCount = plan.stream()
                    .filter(s -> s.isSuccess() && s.getObservation() != null && !s.getObservation().isBlank())
                    .count();

            // 所有步骤都成功：直接结束
            if (successCount == plan.size() && nonEmptyCount == plan.size()) {
                log.debug("All steps succeeded with non-empty results, route to end node");
                return state.withVariable("needReplan", false)
                        .withVariable("replanReason", "all_success");
            }

            // 部分步骤成功：用已有结果回答，不 replan
            // 这符合"不把 replan 当错误恢复"的原则
            if (nonEmptyCount > 0) {
                log.debug("Partial success with {} non-empty results, route to end node", nonEmptyCount);
                return state.withVariable("needReplan", false)
                        .withVariable("replanReason", "partial_success");
            }

            // 所有步骤都失败，或所有成功结果都为空：计划假设可能错误，需要 replan
            log.warn("All steps failed or returned empty, need replan. replanCount={}", replanCount);
            return state.withVariable("needReplan", true)
                    .withVariable("replanReason", successCount == 0 ? "all_failed" : "all_empty");
        }
    }

    /**
     * 结束节点：根据所有观察结果生成最终答案。
     *
     * <p>本节点把最终答案生成委托给注入的 {@link PlanPromptBuilder}，
     * 因此输出格式由具体的 builder 决定。默认 builder 只要求包含
     * {@code Confidence} 和 {@code Final Answer}；业务场景可以通过自定义 builder
     * 增加 {@code Citations: [doc-xxxx]} 等字段。</p>
     *
     * <p>输出后会做两层校验：</p>
     * <ol>
     *     <li>引用校验：如果 LLM 输出包含 {@code [doc-xxxx]}，必须来自实际召回的文档</li>
     *     <li>可信度调整：如果引用无效或观察结果不足，自动降级为 LOW</li>
     * </ol>
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
            int llmCallCount = getLlmCallCount(state);

            // 预算检查
            if (llmCallCount >= MAX_LLM_CALLS) {
                String budgetMessage = "LLM call budget exhausted. Best-effort answer based on available observations.";
                return state.completed(budgetMessage)
                        .withVariable("answerConfidence", "LOW")
                        .withVariable("answerCitations", "")
                        .withVariable("answerSuccess", false);
            }

            String prompt = promptBuilder.buildFinalAnswerPrompt(query, plan);

            try {
                String llmOutput = callLlm(ctx, prompt);
                FinalAnswerResult parsed = parseFinalAnswer(llmOutput);

                // 引用校验
                Set<String> retrievedDocIds = ctx.getMetricsTracker() != null
                        ? ctx.getMetricsTracker().getRetrievedDocIds()
                        : Set.of();
                CitationValidation validation = validateCitations(parsed.getCitations(), retrievedDocIds);

                // 如果引用无效或观察结果不足，强制降级可信度
                String confidence = adjustConfidence(parsed.getConfidence(), validation, plan);

                String finalAnswer = parsed.getFinalAnswer();

                // 高风险场景：引用无效或可信度 LOW 时，给答案加安全前缀
                if ("LOW".equals(confidence)) {
                    finalAnswer = "[可信度：低] " + finalAnswer;
                } else if (!validation.isValid()) {
                    finalAnswer = "[引用待核实] " + finalAnswer;
                }

                boolean success = !"LOW".equals(confidence);
                return state.completed(finalAnswer)
                        .withVariable(KEY_LLM_CALL_COUNT, llmCallCount + 1)
                        .withVariable("answerConfidence", confidence)
                        .withVariable("answerCitations", String.join(", ", parsed.getCitations()))
                        .withVariable("invalidCitations", String.join(", ", validation.getInvalidCitations()))
                        .withVariable("answerSuccess", success);

            } catch (Exception e) {
                log.error("Failed to generate final answer", e);
                String fallback = "Failed to generate final answer: " + e.getMessage();
                return state.completed(fallback)
                        .withVariable("answerConfidence", "LOW")
                        .withVariable("answerCitations", "")
                        .withVariable("answerSuccess", false);
            }
        }

        /**
         * 解析 LLM 输出的结构化最终答案。
         */
        private FinalAnswerResult parseFinalAnswer(String llmOutput) {
            String confidence = "LOW";
            List<String> citations = new ArrayList<>();
            String finalAnswer = llmOutput;

            // 按行解析
            String[] lines = llmOutput.split("\n");
            StringBuilder answerBuilder = new StringBuilder();
            boolean inAnswer = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Confidence:")) {
                    confidence = trimmed.substring("Confidence:".length()).trim().toUpperCase();
                } else if (trimmed.startsWith("Citations:")) {
                    String citationLine = trimmed.substring("Citations:".length()).trim();
                    citations = extractCitations(citationLine);
                } else if (trimmed.startsWith("Final Answer:")) {
                    inAnswer = true;
                    answerBuilder.append(trimmed.substring("Final Answer:".length()).trim());
                } else if (inAnswer) {
                    answerBuilder.append("\n").append(trimmed);
                }
            }

            if (answerBuilder.length() > 0) {
                finalAnswer = answerBuilder.toString().trim();
            }

            // 规范化 confidence
            if (!confidence.equals("HIGH") && !confidence.equals("MEDIUM") && !confidence.equals("LOW")) {
                confidence = "LOW";
            }

            return new FinalAnswerResult(confidence, citations, finalAnswer);
        }

        /**
         * 从字符串中提取 [doc-xxxx] 引用。
         */
        private List<String> extractCitations(String text) {
            List<String> result = new ArrayList<>();
            if (text == null || text.isBlank() || text.equalsIgnoreCase("none")) {
                return result;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[doc-([^\\]]+)\\]")
                    .matcher(text);
            while (matcher.find()) {
                result.add("[doc-" + matcher.group(1) + "]");
            }
            return result;
        }

        /**
         * 校验引用是否来自实际召回的文档。
         */
        private CitationValidation validateCitations(List<String> citations, Set<String> retrievedDocIds) {
            List<String> invalid = new ArrayList<>();
            for (String citation : citations) {
                String docId = citation.replace("[doc-", "").replace("]", "");
                if (!retrievedDocIds.contains(docId)) {
                    invalid.add(citation);
                }
            }
            return new CitationValidation(invalid.isEmpty(), invalid);
        }

        /**
         * 根据引用校验和观察结果调整可信度。
         */
        private String adjustConfidence(String originalConfidence, CitationValidation validation, List<PlanStep> plan) {
            // 如果有无效引用，直接降级为 LOW
            if (!validation.isValid()) {
                return "LOW";
            }

            // 如果没有观察结果，最高只能 MEDIUM
            if (plan == null || plan.isEmpty()) {
                return "LOW";
            }

            long successCount = plan.stream().filter(PlanStep::isSuccess).count();
            long nonEmptyCount = plan.stream()
                    .filter(s -> s.isSuccess() && s.getObservation() != null && !s.getObservation().isBlank())
                    .count();

            // 没有任何成功非空结果，强制 LOW
            if (nonEmptyCount == 0) {
                return "LOW";
            }

            // 部分失败但未影响结果，最高 MEDIUM
            if (successCount < plan.size()) {
                if ("HIGH".equals(originalConfidence)) {
                    return "MEDIUM";
                }
            }

            return originalConfidence;
        }
    }

    /**
     * 最终答案解析结果。
     */
    private static class FinalAnswerResult {
        private final String confidence;
        private final List<String> citations;
        private final String finalAnswer;

        FinalAnswerResult(String confidence, List<String> citations, String finalAnswer) {
            this.confidence = confidence;
            this.citations = citations;
            this.finalAnswer = finalAnswer;
        }

        String getConfidence() {
            return confidence;
        }

        List<String> getCitations() {
            return citations;
        }

        String getFinalAnswer() {
            return finalAnswer;
        }
    }

    /**
     * 引用校验结果。
     */
    private static class CitationValidation {
        private final boolean valid;
        private final List<String> invalidCitations;

        CitationValidation(boolean valid, List<String> invalidCitations) {
            this.valid = valid;
            this.invalidCitations = invalidCitations;
        }

        boolean isValid() {
            return valid;
        }

        List<String> getInvalidCitations() {
            return invalidCitations;
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
     * 从状态中获取重新规划次数，默认 0。
     */
    private int getReplanCount(AgentState state) {
        Integer count = state.getVariable(KEY_REPLAN_COUNT);
        return count == null ? 0 : count;
    }

    /**
     * 从状态中获取 LLM 调用次数，默认 0。
     */
    private int getLlmCallCount(AgentState state) {
        Integer count = state.getVariable(KEY_LLM_CALL_COUNT);
        return count == null ? 0 : count;
    }
}
