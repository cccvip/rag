package com.core.agent.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 线程级追踪上下文持有者。
 *
 * 与 MDC 联动，保证日志中统一携带 traceId / tenantId / scene。
 */
public class TraceContextHolder {

    private static final String TRACE_ID = "traceId";
    private static final String TENANT_ID = "tenantId";
    private static final String USER_ID = "userId";
    private static final String SCENE = "scene";

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    /**
     * 初始化追踪上下文；traceId 为空时自动生成 UUID。
     */
    public static TraceContext set(String traceId, String tenantId, String userId, String scene) {
        TraceContext ctx = TraceContext.builder()
                .traceId(traceId == null || traceId.isEmpty() ? generateTraceId() : traceId)
                .tenantId(tenantId)
                .userId(userId)
                .scene(scene)
                .startTimeMs(System.currentTimeMillis())
                .build();
        CONTEXT.set(ctx);
        putMdc(ctx);
        return ctx;
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
        MDC.clear();
    }

    public static String getTraceId() {
        TraceContext ctx = CONTEXT.get();
        return ctx == null ? MDC.get(TRACE_ID) : ctx.getTraceId();
    }

    private static void putMdc(TraceContext ctx) {
        MDC.put(TRACE_ID, ctx.getTraceId());
        if (ctx.getTenantId() != null) {
            MDC.put(TENANT_ID, ctx.getTenantId());
        }
        if (ctx.getUserId() != null) {
            MDC.put(USER_ID, ctx.getUserId());
        }
        if (ctx.getScene() != null) {
            MDC.put(SCENE, ctx.getScene());
        }
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
