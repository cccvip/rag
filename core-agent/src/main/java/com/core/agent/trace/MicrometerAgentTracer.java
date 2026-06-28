package com.core.agent.trace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Micrometer 的 Agent 追踪器实现。
 *
 * 指标命名：
 * - agent.request.total：请求总数（tag: tenant, scene, success）
 * - agent.request.duration：请求耗时（tag: tenant, scene）
 * - agent.tool.call.total：Tool 调用次数（tag: tenant, tool, success）
 * - agent.tool.call.duration：Tool 调用耗时（tag: tenant, tool）
 * - agent.token.usage：Token 消耗（tag: tenant）
 * - agent.cache.hit.total：缓存命中次数（tag: tenant, type）
 */
public class MicrometerAgentTracer implements AgentTracer {

    private final MeterRegistry meterRegistry;

    public MicrometerAgentTracer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordRequestStart(String traceId, String tenantId, String scene) {
        // 请求开始无需立即记录指标，可在结束时统一计数；
        // 此处保留接口入口，便于后续扩展 in-flight 统计。
    }

    @Override
    public void recordRequestEnd(String traceId, long latencyMs, boolean success) {
        TraceContext ctx = TraceContextHolder.get();
        String tenantId = ctx == null ? null : ctx.getTenantId();
        String scene = ctx == null ? null : ctx.getScene();
        Tags tags = Tags.of(
                "tenant", nvl(tenantId),
                "scene", nvl(scene),
                "success", String.valueOf(success));
        Counter.builder("agent.request.total")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        Timer.builder("agent.request.duration")
                .tags(tags)
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordToolCall(String traceId, String tenantId, String toolName,
                               long latencyMs, boolean success) {
        Tags tags = Tags.of("tenant", nvl(tenantId), "tool", nvl(toolName), "success", String.valueOf(success));
        Counter.builder("agent.tool.call.total")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        Timer.builder("agent.tool.call.duration")
                .tags(tags)
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordTokenUsage(String traceId, String tenantId, int tokens) {
        Counter.builder("agent.token.usage")
                .tag("tenant", nvl(tenantId))
                .register(meterRegistry)
                .increment(tokens);
    }

    @Override
    public void recordCacheHit(String traceId, String tenantId, String cacheType) {
        Counter.builder("agent.cache.hit.total")
                .tags("tenant", nvl(tenantId), "type", nvl(cacheType))
                .register(meterRegistry)
                .increment();
    }

    private String nvl(String value) {
        return value == null ? "unknown" : value;
    }
}
