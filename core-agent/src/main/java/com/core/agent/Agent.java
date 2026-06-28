package com.core.agent;

import com.core.agent.context.ContextManager;
import com.core.agent.context.ContextStrategy;
import com.core.agent.context.DefaultContextStrategy;
import com.core.agent.context.MessageBlock;
import com.core.agent.memory.MemoryManager;
import com.core.agent.memory.MemoryMessage;
import com.core.agent.tenant.AgentCallRecord;
import com.core.agent.tenant.TenantCheckResult;
import com.core.agent.tenant.TenantCtrl;
import com.core.agent.trace.AgentTracer;
import com.core.agent.trace.TraceContextHolder;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ReAct Agent 循环：驱动 Thought → Action → Observation。
 * 基于 Spring AI 的 ChatModel 调用 LLM，内置记忆、指标追踪和安全护栏。
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final ChatModel chatModel;
    private final ToolRegistry registry;
    private final GuardRail guardRail;
    private final MetricsTracker metrics;
    private final MemoryManager memoryManager;
    private final ContextManager contextManager;
    private final ContextStrategy contextStrategy;
    private final int maxIterations;
    private final String tenantId;
    private final String userId;
    private final int maxMemoryTokens;
    private final int llmTimeoutSeconds;
    private final int toolTimeoutSeconds;
    private final int maxRetries;
    private final boolean enableReflection;
    private final TenantCtrl tenantCtrl;
    private final AgentTracer tracer;
    private final String scene;
    private String lastAction;

    public Agent(ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 int maxIterations) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                new DefaultContextStrategy(), maxIterations,
                "default-tenant", "default-user", 2000, 60, 30, 2, true);
    }

    public Agent(ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                new DefaultContextStrategy(), maxIterations,
                tenantId, userId, maxMemoryTokens, 60, 30, 2, true);
    }

    public Agent(ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations,
                tenantId, userId, maxMemoryTokens, 60, 30, 2, true);
    }

    public Agent(ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations,
                tenantId, userId, maxMemoryTokens, llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, true);
    }

    public Agent(ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations, tenantId, userId, maxMemoryTokens,
                llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, enableReflection,
                null, AgentTracer.noOp(), "default");
    }

    public Agent(ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection,
                 TenantCtrl tenantCtrl, AgentTracer tracer, String scene) {
        this.chatModel = chatModel;
        this.registry = registry;
        this.guardRail = guardRail;
        this.metrics = metrics;
        this.memoryManager = memoryManager;
        this.contextManager = contextManager;
        this.contextStrategy = contextStrategy;
        this.maxIterations = maxIterations;
        this.tenantId = tenantId;
        this.userId = userId;
        this.maxMemoryTokens = maxMemoryTokens;
        this.llmTimeoutSeconds = llmTimeoutSeconds;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.maxRetries = maxRetries;
        this.enableReflection = enableReflection;
        this.tenantCtrl = tenantCtrl;
        this.tracer = tracer == null ? AgentTracer.noOp() : tracer;
        this.scene = scene == null ? "default" : scene;
    }

    /**
     * 执行 Agent 循环，直到 LLM 给出 Final Answer 或达到最大迭代次数。
     *
     * @param sessionId 会话 ID，用于 Memory 存取
     * @param query     用户问题
     */
    public String run(String sessionId, String query) throws Exception {
        return run(sessionId, scene, query);
    }

    /**
     * 执行 Agent 循环，支持指定业务场景。
     */
    public String run(String sessionId, String scene, String query) throws Exception {
        long requestStart = System.currentTimeMillis();
        long totalTokens = 0;
        long toolCallCount = 0;
        boolean success = false;
        String traceId = TraceContextHolder.getTraceId();
        TraceContextHolder.set(traceId, tenantId, userId, scene);
        tracer.recordRequestStart(traceId, tenantId, scene);

        try {
            // 0. 租户配额与限流检查
            if (tenantCtrl != null) {
                TenantCheckResult check = tenantCtrl.check(tenantId);
                if (!check.isAllowed()) {
                    String denied = "Tenant check failed: " + check.getReason();
                    log.warn(denied);
                    return denied;
                }
            }

            // 1. 保存用户问题到记忆
            memoryManager.save(sessionId, "user", query);

            String systemPrompt = buildSystemPrompt();

            // 2. 加载历史并转换为 MessageBlock
            List<MemoryMessage> history = memoryManager.getHistory(sessionId, maxMemoryTokens);
            List<MessageBlock> contextBlocks = toMessageBlocks(history);

            for (int i = 0; i < maxIterations; i++) {
                metrics.recordStep();

                // 3. 通过 ContextManager 按策略组装上下文
                String userContext = contextManager.assemble(
                        systemPrompt, contextBlocks, contextStrategy, maxMemoryTokens);

                // 使用 Spring AI 调用 LLM，带超时和重试
                long llmStart = System.currentTimeMillis();
                Prompt prompt = new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userContext)
                ));

                ChatResponse response;
                String llmOutput;
                try {
                    response = callLlmWithTimeoutAndRetry(prompt);
                    llmOutput = response.getResult().getOutput().getContent();
                    Usage usage = response.getMetadata().getUsage();
                    Long promptTokens = usage.getPromptTokens();
                    Long generationTokens = usage.getGenerationTokens();
                    Long total = usage.getTotalTokens();
                    totalTokens += (total != null ? total : 0);
                    metrics.recordLlmCall(promptTokens, generationTokens, total,
                            System.currentTimeMillis() - llmStart);
                    tracer.recordTokenUsage(traceId, tenantId, total != null ? total.intValue() : 0);
                } catch (Exception e) {
                    String errorMsg = "LLM call failed: " + getRootCauseMessage(e);
                    System.err.println(errorMsg);
                    metrics.recordLlmCall(0L, 0L, 0L, System.currentTimeMillis() - llmStart);
                    metrics.setTaskSuccess(false);
                    memoryManager.save(sessionId, "assistant", errorMsg);
                    return errorMsg;
                }

                System.out.println("--- LLM Response ---\n" + llmOutput + "\n");

                // 如果 LLM 直接给出最终答案，结束循环
                if (llmOutput.contains("Final Answer:")) {
                    String finalAnswer = llmOutput.substring(llmOutput.indexOf("Final Answer:") + 13).trim();
                    metrics.computeCitationAccuracy(finalAnswer);
                    metrics.setTaskSuccess(true);

                    // 保存最终答案到记忆
                    memoryManager.save(sessionId, "assistant", finalAnswer);
                    success = true;
                    return finalAnswer;
                }

                String thought = extractLine(llmOutput, "Thought:");
                String action = extractLine(llmOutput, "Action:");
                String actionInput = extractLine(llmOutput, "Action Input:");

                System.out.println("Parsed Thought: " + thought);
                System.out.println("Parsed Action: " + action + "(" + actionInput + ")");

                // 通过 ToolRegistry 查找工具
                Tool tool = registry.get(action);
                String observation;
                boolean stepSuccess;
                boolean blocked = false;

                long toolStart = System.currentTimeMillis();
                if (tool == null) {
                    observation = "Error: tool '" + action + "' not found.";
                    stepSuccess = false;
                } else {
                    // 高风险动作拦截
                    if (tool.riskLevel() == RiskLevel.HIGH || tool.riskLevel() == RiskLevel.CRITICAL) {
                        metrics.recordHighRiskAttempt();
                    }
                    if (!guardRail.allow(tool, tenantId, userId)) {
                        observation = "Blocked by GuardRail: tool '" + action + "' is " + tool.riskLevel() + ".";
                        stepSuccess = false;
                        blocked = true;
                    } else {
                        try {
                            observation = executeToolWithTimeout(tool, actionInput);
                            stepSuccess = !observation.startsWith("Error");
                            toolCallCount++;

                            // 如果是检索工具，记录召回文档用于引用准确率计算
                            if (tool.name().contains("retriever") || tool.name().contains("search")) {
                                metrics.recordRetrievedDocs(observation);
                            }
                        } catch (Exception e) {
                            observation = "Error: tool execution failed: " + e.getMessage();
                            stepSuccess = false;
                        }
                    }
                }
                long toolLatency = System.currentTimeMillis() - toolStart;

                metrics.recordToolCall(action, actionInput, observation, stepSuccess, toolLatency, blocked);
                tracer.recordToolCall(traceId, tenantId, action, toolLatency, stepSuccess);
                System.out.println("--- Observation ---\n" + observation + "\n");

                // 对中间结果进行评判
                String evaluation = evaluateStep(tool, action, observation, stepSuccess, blocked);
                System.out.println("--- Evaluation ---\n" + evaluation + "\n");

                // Reflection
                String reflection = null;
                if (enableReflection && !evaluation.startsWith("OK")) {
                    reflection = reflect(thought, action, actionInput, observation, evaluation);
                    System.out.println("--- Reflection ---\n" + reflection + "\n");
                }

                // 保存 ReAct 中间状态到记忆
                saveReActStep(sessionId, thought, action, actionInput, observation, evaluation,
                        reflection, i + 1);

                // 把本轮结果转为 MessageBlock
                contextBlocks.add(MessageBlock.thought(llmOutput,
                        contextManager.estimate(llmOutput)));
                contextBlocks.add(MessageBlock.toolResult(observation,
                        contextManager.estimate(observation)));
                contextBlocks.add(MessageBlock.systemMeta(evaluation,
                        contextManager.estimate(evaluation)));
                if (reflection != null) {
                    contextBlocks.add(MessageBlock.systemMeta(reflection,
                            contextManager.estimate(reflection)));
                    memoryManager.save(sessionId, "system", reflection);
                }

                lastAction = action;
            }

            metrics.setTaskSuccess(false);
            String failureMessage = "Reached max iterations without final answer.";
            memoryManager.save(sessionId, "assistant", failureMessage);
            return failureMessage;
        } finally {
            long latencyMs = System.currentTimeMillis() - requestStart;
            tracer.recordRequestEnd(traceId, latencyMs, success);
            if (tenantCtrl != null) {
                tenantCtrl.recordUsage(tenantId, new AgentCallRecord(sessionId, totalTokens, latencyMs, toolCallCount));
            }
            TraceContextHolder.clear();
        }
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant that solves problems by using tools.\n");
        sb.append("Think step by step. For each step, output exactly in this format:\n\n");
        sb.append("Thought: [your reasoning about what to do next]\n");
        sb.append("Action: [tool name]\n");
        sb.append("Action Input: [input for the tool]\n\n");
        sb.append("When you have enough information to answer the user, output:\n");
        sb.append("Final Answer: [your final answer]\n\n");
        sb.append("After each Observation, an Evaluation will tell you whether the result is OK, FAIL, BLOCKED, or WARN. ");
        if (enableReflection) {
            sb.append("If the Evaluation is not OK, a Reflection will explain what went wrong and how to fix it. ");
            sb.append("Take the Evaluation and Reflection into account when deciding the next step. ");
        } else {
            sb.append("Take the Evaluation into account when deciding the next step. ");
        }
        sb.append("If you see a repeated action warning, choose a different tool or proceed to Final Answer.\n\n");
        sb.append("Available tools:\n");
        for (Tool tool : registry.all()) {
            sb.append("- ").append(tool.name())
              .append("(").append(tool.riskLevel()).append(")")
              .append(": ").append(tool.description()).append("\n");
        }
        sb.append("\nWhen responding to follow-up questions, use the conversation history to resolve pronouns and avoid repeating retrieval when possible.\n");
        return sb.toString();
    }

    private void saveReActStep(String sessionId, String thought, String action,
                               String actionInput, String observation,
                               String evaluation, String reflection, int step) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ReAct Step ").append(step).append("]\n");
        if (!thought.isEmpty()) {
            sb.append("Thought: ").append(thought).append("\n");
        }
        if (!action.isEmpty()) {
            sb.append("Action: ").append(action).append("\n");
        }
        if (!actionInput.isEmpty()) {
            sb.append("Action Input: ").append(actionInput).append("\n");
        }
        sb.append("Observation: ").append(observation).append("\n");
        sb.append("Evaluation: ").append(evaluation).append("\n");
        if (reflection != null && !reflection.isEmpty()) {
            sb.append("Reflection: ").append(reflection);
        }
        memoryManager.save(sessionId, "observation", sb.toString());
    }

    /**
     * Reflection：让 LLM 自我检查上一步 ReAct，发现错误时给出修正建议。
     *
     * 触发条件：evaluateStep 结果不是 OK（出现 FAIL / BLOCKED / WARN）。
     * 输出格式要求 LLM 返回 "Reflection: [问题分析 + 修正建议]"。
     */
    private String reflect(String thought, String action, String actionInput,
                           String observation, String evaluation) {
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
            ChatResponse response = callLlmWithTimeoutAndRetry(reflectionPrompt);
            String output = response.getResult().getOutput().getContent();
            return extractLine(output, "Reflection:");
        } catch (Exception e) {
            return "Reflection unavailable due to LLM error: " + e.getMessage()
                    + ". Fallback: review the Evaluation and choose a different Action.";
        }
    }

    /**
     * 对每一步 ReAct 的中间结果做规则化评判。
     *
     * 评判维度：
     * - 工具是否存在
     * - 是否被 GuardRail 拦截
     * - 工具执行是否成功
     * - 是否出现重复动作
     * - 检索类工具是否召回空结果
     */
    private String evaluateStep(Tool tool, String action, String observation,
                                boolean success, boolean blocked) {
        if (tool == null) {
            return "FAIL: tool '" + action + "' not found. Please choose a valid tool.";
        }
        if (blocked) {
            return "BLOCKED: tool '" + action + "' is " + tool.riskLevel()
                    + ". Try an alternative with lower risk.";
        }
        if (!success) {
            return "FAIL: execution error. Consider retrying with different input.";
        }
        if (action.equals(lastAction)) {
            return "WARN: repeated action '" + action
                    + "'. Consider a different strategy to avoid loops.";
        }
        if ((tool.name().contains("retriever") || tool.name().contains("search"))
                && !observation.matches(".*\\[doc-[^\\]]+\\].*")) {
            return "WARN: no documents retrieved. Try rephrasing the query.";
        }
        return "OK: result accepted.";
    }

    /**
     * 把 MemoryMessage 历史转换为 MessageBlock，供 ContextManager 统一裁剪。
     */
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

    private String extractLine(String text, String prefix) {
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

    /**
     * 调用 LLM，支持超时和失败重试。
     */
    private ChatResponse callLlmWithTimeoutAndRetry(Prompt prompt) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
                        .orTimeout(llmTimeoutSeconds, TimeUnit.SECONDS)
                        .join();
            } catch (Exception e) {
                Throwable cause = (e instanceof java.util.concurrent.CompletionException) ? e.getCause() : e;
                String reason = cause.getMessage();
                if (cause instanceof TimeoutException) {
                    reason = "LLM call timed out after " + llmTimeoutSeconds + " seconds";
                }
                lastException = new Exception(reason, cause);
                System.err.println("LLM call attempt " + (attempt + 1) + " failed: " + reason);
                if (attempt < maxRetries) {
                    System.err.println("Retrying...");
                }
            }
        }
        throw new Exception("LLM call failed after " + (maxRetries + 1) + " attempts", lastException);
    }

    /**
     * 执行 Tool，支持超时。
     */
    private String executeToolWithTimeout(Tool tool, String input) throws Exception {
        return CompletableFuture.supplyAsync(() -> tool.execute(input))
                .orTimeout(toolTimeoutSeconds, TimeUnit.SECONDS)
                .join();
    }
}
