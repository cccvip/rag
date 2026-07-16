package com.core.agent.agent.graph.domain;

import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.context.application.ContextManager;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.memory.application.SharedMemoryManager;
import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.A2aGateway;
import com.core.agent.tenant.domain.TenantCtrl;
import com.core.agent.tool.domain.ToolRegistry;
import com.core.agent.trace.domain.AgentTracer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 节点执行上下文。
 *
 * <p>封装节点执行时需要的所有基础设施，避免每个节点都持有大量依赖。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeContext {

    /** LLM 模型 */
    private ChatModel chatModel;

    /** 本地工具注册表 */
    private ToolRegistry toolRegistry;

    /** MCP Gateway */
    private McpGateway mcpGateway;

    /** 安全护栏 */
    private GuardRail guardRail;

    /** 记忆管理器 */
    private MemoryManager memoryManager;

    /** 跨 Agent 共享记忆管理器 */
    private SharedMemoryManager sharedMemoryManager;

    /** Agent Card 注册表 */
    private AgentRegistry agentRegistry;

    /** A2A 协议 Gateway */
    private A2aGateway a2aGateway;

    /** 租户管控 */
    private TenantCtrl tenantCtrl;

    /** 调用链追踪 */
    private AgentTracer tracer;

    /** 上下文管理器 */
    private ContextManager contextManager;

    /** 指标追踪器 */
    private MetricsTracker metricsTracker;

    /** 最大迭代次数 */
    private int maxIterations;

    /** LLM 调用超时（秒） */
    private int llmTimeoutSeconds;

    /** 工具调用超时（秒） */
    private int toolTimeoutSeconds;

    /** LLM 失败重试次数 */
    private int maxRetries;

    /** 是否开启 Reflection */
    private boolean enableReflection;

    /** 最大记忆 token 数 */
    private int maxMemoryTokens;

    /**
     * 获取当前 traceId。
     */
    public String getTraceId() {
        return traceContext != null ? traceContext.getTraceId() : null;
    }

    /**
     * 获取当前租户 ID。
     */
    public String getTenantId() {
        return traceContext != null ? traceContext.getTenantId() : null;
    }

    /**
     * 获取当前场景。
     */
    public String getScene() {
        return traceContext != null ? traceContext.getScene() : null;
    }

    /**
     * 获取当前用户 ID。
     */
    public String getUserId() {
        return traceContext != null ? traceContext.getUserId() : null;
    }

    /** 追踪上下文 */
    @Builder.Default
    private Trace traceContext = new Trace();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Trace {
        private String traceId;
        private String tenantId;
        private String userId;
        private String scene;
    }
}
