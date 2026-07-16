package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentMessage;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.context.application.ContextManager;
import com.core.agent.context.domain.ContextStrategy;
import com.core.agent.context.domain.MessageBlock;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.memory.domain.MemoryMessage;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tenant.domain.AgentCallRecord;
import com.core.agent.tenant.domain.TenantCheckResult;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolDefinition;
import com.core.agent.tool.domain.ToolRegistry;
import com.core.agent.trace.domain.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ReAct 执行策略。
 *
 * <p>把原有 {@link com.core.agent.agent.domain.Agent#run} 的 ReAct 逻辑
 * 装进状态图引擎。对外行为保持不变，但内部具备状态图能力（可观测、可恢复）。</p>
 */
public class ReactStrategy implements ExecutionStrategy<NodeContext> {

    private static final Logger log = LoggerFactory.getLogger(ReactStrategy.class);

    private final ContextStrategy contextStrategy;
    private final String scene;

    public ReactStrategy(ContextStrategy contextStrategy, String scene) {
        this.contextStrategy = contextStrategy;
        this.scene = scene;
    }

    @Override
    public String name() {
        return "react";
    }

    @Override
    public AgentGraph<NodeContext> compile() {
        return AgentGraph.<NodeContext>builder()
                .startNode("reactLoop")
                .addNode("reactLoop", new ReactLoopNode())
                .addNode("end", new EndNode())
                .addEdge("reactLoop", "end")
                .endNode("end")
                .maxSteps(10)
                .build();
    }

    /**
     * ReAct 循环节点：一次性完成完整 ReAct 循环。
     *
     * <p>阶段一先保持逻辑内聚，避免一次拆得太细导致行为变化；
     * 阶段二再拆分为更细粒度的子图节点。</p>
     */
    private class ReactLoopNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "reactLoop";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            long requestStart = System.currentTimeMillis();
            long totalTokens = 0;
            long toolCallCount = 0;
            String sessionId = state.getVariable("sessionId");
            String query = state.getVariable("query");
            String traceId = ctx.getTraceId();
            String tenantId = ctx.getTenantId();
            String userId = ctx.getUserId();
            String sceneName = scene != null ? scene : ctx.getScene();

            if (sessionId == null || query == null) {
                return state.error("Missing sessionId or query in state metadata");
            }

            try {
                // 0. 租户检查
                if (ctx.getTenantCtrl() != null) {
                    TenantCheckResult check = ctx.getTenantCtrl().check(tenantId);
                    if (!check.isAllowed()) {
                        String denied = "Tenant check failed: " + check.getReason();
                        log.warn(denied);
                        return state.completed(denied);
                    }
                }

                // 1. 保存用户问题
                ctx.getMemoryManager().save(sessionId, "user", query);

                MetricsTracker metrics = ctx.getMetricsTracker();

                String systemPrompt = buildSystemPrompt(ctx);

                // 2. 加载历史
                List<MemoryMessage> history = ctx.getMemoryManager().getHistory(sessionId, ctx.getMaxMemoryTokens());
                List<MessageBlock> contextBlocks = toMessageBlocks(history);

                String lastAction = null;

                for (int i = 0; i < ctx.getMaxIterations(); i++) {
                    if (metrics != null) {
                        metrics.recordStep();
                    }

                    // 3. 组装上下文
                    String userContext = ctx.getContextManager().assemble(
                            systemPrompt, contextBlocks, contextStrategy, ctx.getMaxMemoryTokens());

                    // 4. 调用 LLM
                    long llmStart = System.currentTimeMillis();
                    Prompt prompt = new Prompt(List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userContext)
                    ));

                    ChatResponse response;
                    String llmOutput;
                    try {
                        response = callLlmWithTimeoutAndRetry(ctx, prompt);
                        llmOutput = response.getResult().getOutput().getContent();
                        Usage usage = response.getMetadata().getUsage();
                        Long total = usage.getTotalTokens();
                        totalTokens += (total != null ? total : 0);
                        if (metrics != null) {
                            metrics.recordLlmCall(usage.getPromptTokens(), usage.getGenerationTokens(), total,
                                    System.currentTimeMillis() - llmStart);
                        }
                        if (ctx.getTracer() != null) {
                            ctx.getTracer().recordTokenUsage(traceId, tenantId,
                                    total != null ? total.intValue() : 0);
                        }
                    } catch (Exception e) {
                        String errorMsg = "LLM call failed: " + getRootCauseMessage(e);
                        ctx.getMemoryManager().save(sessionId, "assistant", errorMsg);
                        if (metrics != null) {
                            metrics.recordLlmCall(0L, 0L, 0L, System.currentTimeMillis() - llmStart);
                            metrics.setTaskSuccess(false);
                        }
                        return state.completed(errorMsg)
                                .withVariable("answerConfidence", "LOW")
                                .withVariable("answerCitations", java.util.Collections.emptyList())
                                .withVariable("answerSuccess", false);
                    }

                    AgentState stepState = state
                            .withMessage(AgentMessage.thought(llmOutput))
                            .withVariable("lastLlmOutput", llmOutput);

                    // 5. 检查是否最终答案
                    if (llmOutput.contains("Final Answer:")) {
                        String finalAnswer = llmOutput.substring(
                                llmOutput.indexOf("Final Answer:") + 13).trim();
                        java.util.List<String> citations = com.core.agent.agent.domain.AgentResult.extractCitations(finalAnswer);
                        String confidence = citations.isEmpty() ? "MEDIUM" : "HIGH";
                        if (metrics != null) {
                            metrics.computeCitationAccuracy(finalAnswer);
                            metrics.setTaskSuccess(true);
                        }
                        ctx.getMemoryManager().save(sessionId, "assistant", finalAnswer);

                        recordUsage(ctx, tenantId, sessionId, totalTokens,
                                System.currentTimeMillis() - requestStart, toolCallCount);
                        return stepState.completed(finalAnswer)
                                .withVariable("answerConfidence", confidence)
                                .withVariable("answerCitations", citations)
                                .withVariable("answerSuccess", true);
                    }

                    // 6. 解析 Thought / Action / Action Input
                    String thought = extractLine(llmOutput, "Thought:");
                    String action = extractLine(llmOutput, "Action:");
                    String actionInput = extractLine(llmOutput, "Action Input:");

                    // 7. 查找并执行工具
                    ToolCallContext toolCtx = resolveTool(action, ctx);
                    String observation;
                    boolean stepSuccess;
                    boolean blocked = false;

                    long toolStart = System.currentTimeMillis();
                    if (!toolCtx.found()) {
                        observation = "Error: tool '" + action + "' not found.";
                        stepSuccess = false;
                    } else {
                        if (toolCtx.riskLevel() == RiskLevel.HIGH || toolCtx.riskLevel() == RiskLevel.CRITICAL) {
                            if (metrics != null) {
                                metrics.recordHighRiskAttempt();
                            }
                        }
                        if (!toolCtx.isAllowed(ctx.getGuardRail(), tenantId, userId)) {
                            observation = "Blocked by GuardRail: tool '" + action + "' is " + toolCtx.riskLevel() + ".";
                            stepSuccess = false;
                            blocked = true;
                        } else {
                            try {
                                observation = toolCtx.execute(actionInput, tenantId, sceneName, ctx.getToolTimeoutSeconds());
                                stepSuccess = !observation.startsWith("Error");
                                toolCallCount++;
                                if (metrics != null && toolCtx.isRetrieverLike()) {
                                    metrics.recordRetrievedDocs(observation);
                                }
                            } catch (Exception e) {
                                observation = "Error: tool execution failed: " + e.getMessage();
                                stepSuccess = false;
                            }
                        }
                    }
                    long toolLatency = System.currentTimeMillis() - toolStart;

                    if (metrics != null) {
                        metrics.recordToolCall(action, actionInput, observation, stepSuccess, toolLatency, blocked);
                    }
                    if (ctx.getTracer() != null) {
                        ctx.getTracer().recordToolCall(traceId, tenantId, action, toolLatency, stepSuccess);
                    }

                    // 8. 评判与反思
                    String evaluation = evaluateStep(toolCtx, action, observation, stepSuccess, blocked, lastAction);
                    String reflection = null;
                    if (ctx.isEnableReflection() && !evaluation.startsWith("OK")) {
                        reflection = reflect(ctx, thought, action, actionInput, observation, evaluation);
                    }

                    // 9. 保存 ReAct 步骤到记忆
                    saveReActStep(ctx, sessionId, thought, action, actionInput,
                            observation, evaluation, reflection, i + 1);

                    // 10. 更新 contextBlocks
                    contextBlocks.add(MessageBlock.thought(llmOutput, ctx.getContextManager().estimate(llmOutput)));
                    contextBlocks.add(MessageBlock.toolResult(observation, ctx.getContextManager().estimate(observation)));
                    contextBlocks.add(MessageBlock.systemMeta(evaluation, ctx.getContextManager().estimate(evaluation)));
                    if (reflection != null) {
                        contextBlocks.add(MessageBlock.systemMeta(reflection, ctx.getContextManager().estimate(reflection)));
                        ctx.getMemoryManager().save(sessionId, "system", reflection);
                    }

                    lastAction = action;
                }

                // 到达最大迭代次数
                String failureMessage = "Reached max iterations without final answer.";
                ctx.getMemoryManager().save(sessionId, "assistant", failureMessage);
                if (metrics != null) {
                    metrics.setTaskSuccess(false);
                }
                recordUsage(ctx, tenantId, sessionId, totalTokens,
                        System.currentTimeMillis() - requestStart, toolCallCount);
                return state.completed(failureMessage)
                        .withVariable("answerConfidence", "LOW")
                        .withVariable("answerCitations", java.util.Collections.emptyList())
                        .withVariable("answerSuccess", false);

            } finally {
                TraceContextHolder.clear();
            }
        }

        private String buildSystemPrompt(NodeContext ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append("You are a helpful assistant that solves problems by using tools.\n");
            sb.append("Think step by step. For each step, output exactly in this format:\n\n");
            sb.append("Thought: [your reasoning about what to do next]\n");
            sb.append("Action: [tool name]\n");
            sb.append("Action Input: [input for the tool]\n\n");
            sb.append("When you have enough information to answer the user, output:\n");
            sb.append("Final Answer: [your final answer]\n\n");
            sb.append("After each Observation, an Evaluation will tell you whether the result is OK, FAIL, BLOCKED, or WARN. ");
            if (ctx.isEnableReflection()) {
                sb.append("If the Evaluation is not OK, a Reflection will explain what went wrong and how to fix it. ");
                sb.append("Take the Evaluation and Reflection into account when deciding the next step. ");
            } else {
                sb.append("Take the Evaluation into account when deciding the next step. ");
            }
            sb.append("If you see a repeated action warning, choose a different tool or proceed to Final Answer.\n\n");
            sb.append("Available tools:\n");
            for (String toolLine : listToolDescriptions(ctx)) {
                sb.append(toolLine).append("\n");
            }
            sb.append("\nWhen responding to follow-up questions, use the conversation history to resolve pronouns and avoid repeating retrieval when possible.\n");
            return sb.toString();
        }

        private List<String> listToolDescriptions(NodeContext ctx) {
            List<String> lines = new ArrayList<>();
            McpGateway mcpGateway = ctx.getMcpGateway();
            ToolRegistry registry = ctx.getToolRegistry();
            String tenantId = ctx.getTenantId();
            String sceneName = scene != null ? scene : ctx.getScene();

            if (mcpGateway != null) {
                for (ToolDefinition tool : mcpGateway.listTools(tenantId, sceneName)) {
                    lines.add("- " + tool.getName()
                            + "(" + tool.getRiskLevel() + ")"
                            + ": " + tool.getDescription());
                }
            }
            if (registry != null) {
                for (Tool tool : registry.all()) {
                    lines.add("- " + tool.name()
                            + "(" + tool.riskLevel() + ")"
                            + ": " + tool.description());
                }
            }
            return lines;
        }

        private void saveReActStep(NodeContext ctx, String sessionId, String thought, String action,
                                   String actionInput, String observation, String evaluation,
                                   String reflection, int step) {
            StringBuilder sb = new StringBuilder();
            sb.append("[ReAct Step ").append(step).append("]\n");
            if (thought != null && !thought.isEmpty()) {
                sb.append("Thought: ").append(thought).append("\n");
            }
            if (action != null && !action.isEmpty()) {
                sb.append("Action: ").append(action).append("\n");
            }
            if (actionInput != null && !actionInput.isEmpty()) {
                sb.append("Action Input: ").append(actionInput).append("\n");
            }
            sb.append("Observation: ").append(observation).append("\n");
            sb.append("Evaluation: ").append(evaluation).append("\n");
            if (reflection != null && !reflection.isEmpty()) {
                sb.append("Reflection: ").append(reflection);
            }
            ctx.getMemoryManager().save(sessionId, "observation", sb.toString());
        }

        private String reflect(NodeContext ctx, String thought, String action, String actionInput,
                               String observation, String evaluation) {
            ChatModel chatModel = ctx.getChatModel();
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are reviewing the latest step of a ReAct agent. ");
            prompt.append("Identify what went wrong and suggest how to fix it in the next step.\n\n");
            prompt.append("Previous Thought: ").append(thought).append("\n");
            prompt.append("Action Taken: ").append(action).append("\n");
            prompt.append("Action Input: ").append(actionInput).append("\n");
            prompt.append("Observation: ").append(observation).append("\n");
            prompt.append("Evaluation: ").append(evaluation).append("\n\n");
            prompt.append("Output exactly in this format:\n");
            prompt.append("Reflection: [what was wrong and how to correct it]");

            Prompt reflectionPrompt = new Prompt(List.of(new UserMessage(prompt.toString())));
            try {
                ChatResponse response = callLlmWithTimeoutAndRetry(ctx, reflectionPrompt);
                String output = response.getResult().getOutput().getContent();
                return extractLine(output, "Reflection:");
            } catch (Exception e) {
                return "Reflection unavailable due to LLM error: " + e.getMessage()
                        + ". Fallback: review the Evaluation and choose a different Action.";
            }
        }

        private String evaluateStep(ToolCallContext toolCtx, String action, String observation,
                                    boolean success, boolean blocked, String lastAction) {
            if (!toolCtx.found()) {
                return "FAIL: tool '" + action + "' not found. Please choose a valid tool.";
            }
            if (blocked) {
                return "BLOCKED: tool '" + action + "' is " + toolCtx.riskLevel()
                        + ". Try an alternative with lower risk.";
            }
            if (!success) {
                return "FAIL: execution error. Consider retrying with different input.";
            }
            if (action.equals(lastAction)) {
                return "WARN: repeated action '" + action
                        + "'. Consider a different strategy to avoid loops.";
            }
            if (toolCtx.isRetrieverLike()
                    && !observation.matches(".*\\[doc-[^\\]]+\\].*")) {
                return "WARN: no documents retrieved. Try rephrasing the query.";
            }
            return "OK: result accepted.";
        }

        private ChatResponse callLlmWithTimeoutAndRetry(NodeContext ctx, Prompt prompt) throws Exception {
            ChatModel chatModel = ctx.getChatModel();
            int maxRetries = ctx.getMaxRetries();
            int timeoutSeconds = ctx.getLlmTimeoutSeconds();
            Exception lastException = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    return CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
                            .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                            .join();
                } catch (Exception e) {
                    Throwable cause = (e instanceof java.util.concurrent.CompletionException) ? e.getCause() : e;
                    String reason = cause.getMessage();
                    if (cause instanceof TimeoutException) {
                        reason = "LLM call timed out after " + timeoutSeconds + " seconds";
                    }
                    lastException = new Exception(reason, cause);
                    if (attempt < maxRetries) {
                        log.warn("LLM call attempt {} failed: {}", attempt + 1, reason);
                    }
                }
            }
            throw new Exception("LLM call failed after " + (maxRetries + 1) + " attempts", lastException);
        }

        private ToolCallContext resolveTool(String action, NodeContext ctx) {
            McpGateway mcpGateway = ctx.getMcpGateway();
            ToolRegistry registry = ctx.getToolRegistry();
            String tenantId = ctx.getTenantId();
            String sceneName = scene != null ? scene : ctx.getScene();

            if (mcpGateway != null) {
                ToolDefinition tool = mcpGateway.listTools(tenantId, sceneName).stream()
                        .filter(t -> t.getName().equals(action))
                        .findFirst()
                        .orElse(null);
                if (tool != null) {
                    return new ToolCallContext(tool, mcpGateway);
                }
            }
            if (registry != null) {
                Tool localTool = registry.get(action);
                if (localTool != null) {
                    return new ToolCallContext(localTool);
                }
            }
            return new ToolCallContext(null);
        }

        private List<MessageBlock> toMessageBlocks(List<MemoryMessage> history) {
            List<MessageBlock> blocks = new ArrayList<>();
            for (MemoryMessage msg : history) {
                MessageBlock block = switch (msg.getRole()) {
                    case "user" -> MessageBlock.userQuestion(msg.getContent(), msg.getTokenCount());
                    case "assistant" -> MessageBlock.assistantAnswer(msg.getContent(), msg.getTokenCount());
                    case "observation" -> MessageBlock.toolResult(msg.getContent(), msg.getTokenCount());
                    case "system" -> MessageBlock.systemMeta(msg.getContent(), msg.getTokenCount());
                    default -> MessageBlock.systemMeta(msg.getContent(), msg.getTokenCount());
                };
                blocks.add(block);
            }
            return blocks;
        }

        private String extractLine(String text, String prefix) {
            if (text == null) {
                return "";
            }
            int start = text.indexOf(prefix);
            if (start < 0) {
                return "";
            }
            start += prefix.length();
            int end = text.indexOf("\n", start);
            if (end < 0) {
                end = text.length();
            }
            return text.substring(start, end).trim();
        }

        private String getRootCauseMessage(Throwable throwable) {
            Throwable current = throwable;
            String lastMessage = null;
            while (current != null && current.getCause() != current) {
                if (current.getMessage() != null && !current.getMessage().isEmpty()) {
                    lastMessage = current.getMessage();
                }
                if (current.getCause() == null) {
                    break;
                }
                current = current.getCause();
            }
            if (lastMessage != null) {
                return lastMessage;
            }
            return current != null && current.getMessage() != null
                    ? current.getMessage() : current.getClass().getSimpleName();
        }

        private void recordUsage(NodeContext ctx, String tenantId, String sessionId,
                                 long totalTokens, long latencyMs, long toolCallCount) {
            if (ctx.getTenantCtrl() != null) {
                ctx.getTenantCtrl().recordUsage(tenantId,
                        new AgentCallRecord(sessionId, totalTokens, latencyMs, toolCallCount));
            }
        }
    }

    /**
     * 终止节点：透传状态。
     */
    private static class EndNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "end";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            return state;
        }
    }

    /**
     * 统一本地 Tool 与 MCP Gateway 工具的执行方式。
     */
    private static class ToolCallContext {
        private final Tool localTool;
        private final ToolDefinition gatewayTool;
        private final McpGateway gateway;

        ToolCallContext(Tool localTool) {
            this.localTool = localTool;
            this.gatewayTool = null;
            this.gateway = null;
        }

        ToolCallContext(ToolDefinition gatewayTool, McpGateway gateway) {
            this.localTool = null;
            this.gatewayTool = gatewayTool;
            this.gateway = gateway;
        }

        boolean found() {
            return localTool != null || gatewayTool != null;
        }

        RiskLevel riskLevel() {
            if (localTool != null) {
                return localTool.riskLevel();
            }
            return gatewayTool != null ? gatewayTool.getRiskLevel() : RiskLevel.LOW;
        }

        boolean isAllowed(GuardRail guardRail, String tenantId, String userId) {
            if (localTool != null) {
                return guardRail.allow(localTool, tenantId, userId);
            }
            return gatewayTool != null && guardRail.allow(gatewayTool, tenantId, userId);
        }

        String execute(String input, String tenantId, String scene, int timeoutSeconds) throws Exception {
            if (localTool != null) {
                return executeWithTimeout(() -> localTool.execute(input), timeoutSeconds);
            }
            return executeWithTimeout(() -> {
                var result = gateway.call(gatewayTool.getName(), input, tenantId, scene);
                if (!result.isSuccess()) {
                    return "Error: " + result.getError();
                }
                return result.getData();
            }, timeoutSeconds);
        }

        private String executeWithTimeout(java.util.concurrent.Callable<String> callable, int timeoutSeconds) throws Exception {
            return CompletableFuture.supplyAsync(() -> {
                        try {
                            return callable.call();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();
        }

        boolean isRetrieverLike() {
            String name = localTool != null ? localTool.name() : gatewayTool.getName();
            return name.contains("retriever") || name.contains("search") || name.contains("retrieve");
        }
    }
}
