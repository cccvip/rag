package com.core.agent.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextHolderTest {

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldGenerateTraceIdWhenNotProvided() {
        TraceContext ctx = TraceContextHolder.set(null, "tenant-A", "user-001", "rag");

        assertNotNull(ctx.getTraceId());
        assertFalse(ctx.getTraceId().isEmpty());
        assertEquals("tenant-A", ctx.getTenantId());
        assertEquals("user-001", ctx.getUserId());
        assertEquals("rag", ctx.getScene());
        assertTrue(ctx.getStartTimeMs() > 0);
    }

    @Test
    void shouldSyncToMdc() {
        TraceContextHolder.set("trace-123", "tenant-A", "user-001", "rag");

        assertEquals("trace-123", MDC.get("traceId"));
        assertEquals("tenant-A", MDC.get("tenantId"));
        assertEquals("user-001", MDC.get("userId"));
        assertEquals("rag", MDC.get("scene"));
    }

    @Test
    void shouldClearContext() {
        TraceContextHolder.set("trace-123", "tenant-A", "user-001", "rag");
        TraceContextHolder.clear();

        assertNull(TraceContextHolder.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void shouldGetTraceIdFromContext() {
        TraceContextHolder.set("trace-123", "tenant-A", "user-001", "rag");

        assertEquals("trace-123", TraceContextHolder.getTraceId());
    }
}
