package com.core.agent.agent.domain;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.infrastructure.ReactStrategy;
import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.context.application.ContextManager;
import com.core.agent.context.domain.ContextStrategy;
import com.core.agent.context.infrastructure.DefaultContextStrategy;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.tenant.domain.TenantCtrl;
import com.core.agent.tool.domain.ToolRegistry;
import com.core.agent.trace.domain.AgentTracer;
import com.core.agent.trace.domain.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Agent 入口类。
 *
 * <p>CoreAgent 2.0 中，Agent 负责把用户请求转化为状态图执行上下文，
 * 并委托给 {@link AgentGraph} 执行。对外接口保持与 1.0 兼容。</p>
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final org.springframework.ai.chat.model.ChatModel chatModel;
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
    private final McpGateway mcpGateway;

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 int maxIterations) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                new DefaultContextStrategy(), maxIterations,
                "default-tenant", "default-user", 2000, 60, 30, 2, true);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                new DefaultContextStrategy(), maxIterations,
                tenantId, userId, maxMemoryTokens, 60, 30, 2, true);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations,
                tenantId, userId, maxMemoryTokens, 60, 30, 2, true);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations,
                tenantId, userId, maxMemoryTokens, llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, true);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
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

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection,
                 TenantCtrl tenantCtrl, AgentTracer tracer, String scene) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations, tenantId, userId, maxMemoryTokens,
                llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, enableReflection,
                tenantCtrl, tracer, scene, null);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection,
                 TenantCtrl tenantCtrl, AgentTracer tracer, String scene,
                 McpGateway mcpGateway) {
        this.chatModel = chatModel;
        this.registry = registry;
        this.guardRail = guardRail;
        this.metrics = metrics;
        this.memoryManager = memoryManager;
        this.contextManager = contextManager;
        this.contextStrategy = contextStrategy != null ? contextStrategy : new DefaultContextStrategy();
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
        this.mcpGateway = mcpGateway;
    }

    /**
     * 执行 Agent 循环，直到 LLM 给出 Final Answer 或达到最大迭代次数。
     */
    public String run(String sessionId, String query) throws Exception {
        return run(sessionId, scene, query);
    }

    /**
     * 执行 Agent 循环，支持指定业务场景。
     */
    public String run(String sessionId, String scene, String query) throws Exception {
        String traceId = TraceContextHolder.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        TraceContextHolder.set(traceId, tenantId, userId, scene);
        tracer.recordRequestStart(traceId, tenantId, scene);

        try {
            // 构建初始状态
            AgentState initialState = AgentState.initial(traceId, tenantId, userId, scene)
                    .withVariable("sessionId", sessionId)
                    .withVariable("query", query)
                    .withCurrentNode("reactLoop");

            // 构建节点执行上下文
            NodeContext ctx = buildNodeContext(scene);

            // 编译并执行 ReAct 状态图
            ReactStrategy strategy = new ReactStrategy(contextStrategy, scene);
            AgentGraph<NodeContext> graph = strategy.compile();
            GraphResult result = graph.execute(initialState, ctx);

            // 处理结果
            if (result.isCompleted()) {
                return result.getFinalAnswer();
            }
            if (result.isAwaitingApproval()) {
                return "Awaiting human approval. Checkpoint: " + result.getCheckpointToken();
            }
            if (result.getErrorMessage() != null) {
                return "Agent execution failed: " + result.getErrorMessage();
            }
            return "Agent execution halted without final answer.";

        } finally {
            TraceContextHolder.clear();
        }
    }

    private NodeContext buildNodeContext(String scene) {
        NodeContext.Trace trace = NodeContext.Trace.builder()
                .traceId(TraceContextHolder.getTraceId())
                .tenantId(tenantId)
                .userId(userId)
                .scene(scene)
                .build();

        return NodeContext.builder()
                .chatModel(chatModel)
                .toolRegistry(registry)
                .mcpGateway(mcpGateway)
                .guardRail(guardRail)
                .memoryManager(memoryManager)
                .tenantCtrl(tenantCtrl)
                .tracer(tracer)
                .contextManager(contextManager)
                .metricsTracker(metrics)
                .maxIterations(maxIterations)
                .llmTimeoutSeconds(llmTimeoutSeconds)
                .toolTimeoutSeconds(toolTimeoutSeconds)
                .maxRetries(maxRetries)
                .enableReflection(enableReflection)
                .maxMemoryTokens(maxMemoryTokens)
                .traceContext(trace)
                .build();
    }
}
