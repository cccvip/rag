package com.core.agent.agent.domain;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.agent.strategy.infrastructure.ReactStrategy;
import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.context.application.ContextManager;
import com.core.agent.context.domain.ContextStrategy;
import com.core.agent.context.infrastructure.DefaultContextStrategy;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.memory.application.SharedMemoryManager;
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
    private final SharedMemoryManager sharedMemoryManager;
    private final com.core.agent.multiagent.application.AgentRegistry agentRegistry;
    private final com.core.agent.multiagent.domain.A2aGateway a2aGateway;
    private final ExecutionStrategy<NodeContext> strategy;

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
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations, tenantId, userId, maxMemoryTokens,
                llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, enableReflection,
                tenantCtrl, tracer, scene, mcpGateway, null);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection,
                 TenantCtrl tenantCtrl, AgentTracer tracer, String scene,
                 McpGateway mcpGateway,
                 ExecutionStrategy<NodeContext> strategy) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations, tenantId, userId, maxMemoryTokens,
                llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, enableReflection,
                tenantCtrl, tracer, scene, mcpGateway, null, strategy);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection,
                 TenantCtrl tenantCtrl, AgentTracer tracer, String scene,
                 McpGateway mcpGateway,
                 SharedMemoryManager sharedMemoryManager,
                 ExecutionStrategy<NodeContext> strategy) {
        this(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations, tenantId, userId, maxMemoryTokens,
                llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, enableReflection,
                tenantCtrl, tracer, scene, mcpGateway, sharedMemoryManager, null, null, strategy);
    }

    public Agent(org.springframework.ai.chat.model.ChatModel chatModel, ToolRegistry registry,
                 GuardRail guardRail, MetricsTracker metrics,
                 MemoryManager memoryManager, ContextManager contextManager,
                 ContextStrategy contextStrategy, int maxIterations,
                 String tenantId, String userId, int maxMemoryTokens,
                 int llmTimeoutSeconds, int toolTimeoutSeconds, int maxRetries,
                 boolean enableReflection,
                 TenantCtrl tenantCtrl, AgentTracer tracer, String scene,
                 McpGateway mcpGateway,
                 SharedMemoryManager sharedMemoryManager,
                 com.core.agent.multiagent.application.AgentRegistry agentRegistry,
                 com.core.agent.multiagent.domain.A2aGateway a2aGateway,
                 ExecutionStrategy<NodeContext> strategy) {
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
        this.sharedMemoryManager = sharedMemoryManager;
        this.agentRegistry = agentRegistry;
        this.a2aGateway = a2aGateway;
        this.strategy = strategy != null ? strategy : new ReactStrategy(this.contextStrategy, this.scene);
    }

    /**
     * 返回使用指定执行策略的新 Agent 实例。
     *
     * <p>Agent 本身不可变，本方法通过构造新实例切换策略，原有配置全部保留。</p>
     */
    public Agent withStrategy(ExecutionStrategy<NodeContext> strategy) {
        return new Agent(chatModel, registry, guardRail, metrics, memoryManager, contextManager,
                contextStrategy, maxIterations, tenantId, userId, maxMemoryTokens,
                llmTimeoutSeconds, toolTimeoutSeconds, maxRetries, enableReflection,
                tenantCtrl, tracer, scene, mcpGateway, sharedMemoryManager,
                agentRegistry, a2aGateway, strategy);
    }

    /**
     * 执行 Agent 循环，直到 LLM 给出 Final Answer 或达到最大迭代次数。
     *
     * <p>返回纯文本答案；如需置信度、引用等元数据，请使用
     * {@link #runWithResult(String, String)}。</p>
     */
    public String run(String sessionId, String query) throws Exception {
        return run(sessionId, scene, query);
    }

    /**
     * 执行 Agent 循环，支持指定业务场景。
     *
     * <p>返回纯文本答案；如需置信度、引用等元数据，请使用
     * {@link #runWithResult(String, String, String)}。</p>
     */
    public String run(String sessionId, String scene, String query) throws Exception {
        return runWithResult(sessionId, scene, query).getAnswer();
    }

    /**
     * 执行 Agent 循环，返回包含置信度、引用、完成状态的完整结果。
     */
    public AgentResult runWithResult(String sessionId, String query) throws Exception {
        return runWithResult(sessionId, scene, query);
    }

    /**
     * 从给定初始状态执行 Agent，返回完整结果。
     */
    public AgentResult run(AgentState initialState) {
        String traceId = TraceContextHolder.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            TraceContextHolder.set(traceId, tenantId, userId, scene);
        }
        tracer.recordRequestStart(traceId, tenantId, scene);
        try {
            AgentGraph<NodeContext> graph = strategy.compile();
            AgentState state = initialState.copy();
            if (state.getCurrentNode() == null || state.getCurrentNode().isBlank()) {
                state = state.withCurrentNode(graph.getStartNode());
            }
            NodeContext ctx = buildNodeContext(scene);
            GraphResult result = graph.execute(state, ctx);
            return AgentResult.fromGraphResult(result);
        } finally {
            TraceContextHolder.clear();
        }
    }

    /**
     * 从 checkpoint 恢复执行。
     */
    public AgentResult resume(Checkpoint checkpoint) {
        AgentGraph<NodeContext> graph = strategy.compile();
        NodeContext ctx = buildNodeContext(scene);
        GraphResult result = graph.resume(checkpoint, ctx);
        return AgentResult.fromGraphResult(result);
    }

    /**
     * 执行 Agent 循环，支持指定业务场景，返回完整结果。
     */
    public AgentResult runWithResult(String sessionId, String scene, String query) throws Exception {
        String traceId = TraceContextHolder.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        TraceContextHolder.set(traceId, tenantId, userId, scene);
        tracer.recordRequestStart(traceId, tenantId, scene);

        try {
            // 编译当前策略对应的状态图
            AgentGraph<NodeContext> graph = strategy.compile();

            // 构建初始状态，当前节点设为策略的起始节点
            AgentState initialState = AgentState.initial(traceId, tenantId, userId, scene)
                    .withVariable("sessionId", sessionId)
                    .withVariable("query", query)
                    .withCurrentNode(graph.getStartNode());

            // 构建节点执行上下文
            NodeContext ctx = buildNodeContext(scene);

            // 执行状态图
            GraphResult result = graph.execute(initialState, ctx);

            return AgentResult.fromGraphResult(result);

        } finally {
            TraceContextHolder.clear();
        }
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getScene() {
        return scene;
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
                .sharedMemoryManager(sharedMemoryManager)
                .agentRegistry(agentRegistry)
                .a2aGateway(a2aGateway)
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
