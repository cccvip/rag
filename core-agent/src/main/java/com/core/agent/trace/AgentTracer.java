package com.core.agent.trace;

/**
 * Agent 调用链追踪器。
 *
 * 负责记录请求生命周期、Tool 调用、Token 消耗、缓存命中等指标。
 * 生产环境使用 {@link MicrometerAgentTracer} 对接 Prometheus，
 * 测试或本地可使用 {@link NoOpAgentTracer}。
 */
public interface AgentTracer {

    /**
     * 请求开始。
     */
    void recordRequestStart(String traceId, String tenantId, String scene);

    /**
     * 请求结束。
     */
    void recordRequestEnd(String traceId, long latencyMs, boolean success);

    /**
     * Tool 调用。
     */
    void recordToolCall(String traceId, String tenantId, String toolName,
                        long latencyMs, boolean success);

    /**
     * Token 使用。
     */
    void recordTokenUsage(String traceId, String tenantId, int tokens);

    /**
     * 缓存命中。
     */
    void recordCacheHit(String traceId, String tenantId, String cacheType);

    /**
     * 创建无操作实现。
     */
    static AgentTracer noOp() {
        return NoOpAgentTracer.INSTANCE;
    }
}
