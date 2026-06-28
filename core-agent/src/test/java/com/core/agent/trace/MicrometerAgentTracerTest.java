package com.core.agent.trace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerAgentTracerTest {

    private SimpleMeterRegistry meterRegistry;
    private MicrometerAgentTracer tracer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tracer = new MicrometerAgentTracer(meterRegistry);
    }

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldRecordRequestEndMetrics() {
        TraceContextHolder.set("trace-1", "tenant-A", "user-001", "rag");

        tracer.recordRequestEnd("trace-1", 150, true);

        Counter counter = meterRegistry.find("agent.request.total").counter();
        Timer timer = meterRegistry.find("agent.request.duration").timer();
        assertEquals(1, counter.count());
        assertEquals(1, timer.count());
        assertTrue(timer.mean(TimeUnit.MILLISECONDS) >= 0);
    }

    @Test
    void shouldRecordToolCallMetrics() {
        tracer.recordToolCall("trace-1", "tenant-A", "retriever", 45, true);

        Counter counter = meterRegistry.find("agent.tool.call.total").counter();
        Timer timer = meterRegistry.find("agent.tool.call.duration").timer();
        assertEquals(1, counter.count());
        assertEquals(1, timer.count());
    }

    @Test
    void shouldRecordTokenUsage() {
        tracer.recordTokenUsage("trace-1", "tenant-A", 250);

        Counter counter = meterRegistry.find("agent.token.usage").counter();
        assertEquals(250, counter.count());
    }

    @Test
    void shouldRecordCacheHit() {
        tracer.recordCacheHit("trace-1", "tenant-A", "memory");

        Counter counter = meterRegistry.find("agent.cache.hit.total").counter();
        assertEquals(1, counter.count());
    }
}
