package com.core.agent.trace.infrastructure;
import com.core.agent.trace.domain.AgentTracer;

/**
 * 无操作追踪器 — 用于测试或关闭指标采集的场景。
 */
public enum NoOpAgentTracer implements AgentTracer {
    INSTANCE;

    @Override
    public void recordRequestStart(String traceId, String tenantId, String scene) {
    }

    @Override
    public void recordRequestEnd(String traceId, long latencyMs, boolean success) {
    }

    @Override
    public void recordToolCall(String traceId, String tenantId, String toolName,
                               long latencyMs, boolean success) {
    }

    @Override
    public void recordTokenUsage(String traceId, String tenantId, int tokens) {
    }

    @Override
    public void recordCacheHit(String traceId, String tenantId, String cacheType) {
    }
}
