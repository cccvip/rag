package com.core.agent;

import com.core.agent.context.ContextManager;
import com.core.agent.context.DefaultContextStrategy;
import com.core.agent.memory.InMemoryMemoryManager;
import com.core.agent.memory.InMemoryMemoryStore;
import com.core.agent.memory.MemoryManager;
import com.core.agent.tenant.AgentCallRecord;
import com.core.agent.tenant.InMemoryQuotaStore;
import com.core.agent.tenant.InMemoryTokenBucketRateLimiter;
import com.core.agent.tenant.InMemoryUsageStore;
import com.core.agent.tenant.QuotaStore;
import com.core.agent.tenant.RateLimiter;
import com.core.agent.tenant.TenantCtrl;
import com.core.agent.tenant.TenantQuota;
import com.core.agent.tenant.UsageStore;
import com.core.agent.trace.AgentTracer;
import com.core.agent.trace.MicrometerAgentTracer;
import com.core.agent.trace.TraceContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Agent 与 TenantCtrl / AgentTracer 的集成。
 */
class AgentTenantIntegrationTest {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldDenyWhenTenantQuotaExceeded() throws Exception {
        QuotaStore quotaStore = new InMemoryQuotaStore();
        UsageStore usageStore = new InMemoryUsageStore();
        RateLimiter rateLimiter = new InMemoryTokenBucketRateLimiter(quotaStore);
        TenantCtrl tenantCtrl = new TenantCtrl(quotaStore, usageStore, rateLimiter);
        quotaStore.saveQuota(new TenantQuota("tenant-x", "FREE", 10, 100, 100));
        tenantCtrl.recordUsage("tenant-x", new AgentCallRecord("s-1", 10, 0, 0));

        Agent agent = buildAgent("tenant-x", tenantCtrl, AgentTracer.noOp());
        String answer = agent.run("session-x", "test");

        assertTrue(answer.contains("Tenant check failed"));
        assertEquals(10, tenantCtrl.getCurrentMonthUsage("tenant-x").getUsedTokens());
    }

    @Test
    void shouldRecordUsageWhenAllowed() throws Exception {
        QuotaStore quotaStore = new InMemoryQuotaStore();
        UsageStore usageStore = new InMemoryUsageStore();
        RateLimiter rateLimiter = new InMemoryTokenBucketRateLimiter(quotaStore);
        TenantCtrl tenantCtrl = new TenantCtrl(quotaStore, usageStore, rateLimiter);
        quotaStore.saveQuota(new TenantQuota("tenant-y", "PRO", 10000, 100, 1000));

        Agent agent = buildAgent("tenant-y", tenantCtrl, new MicrometerAgentTracer(new SimpleMeterRegistry()));
        String answer = agent.run("session-y", "rag", "test");

        assertEquals("final answer", answer);
        assertEquals(1, tenantCtrl.getCurrentMonthUsage("tenant-y").getCallCount());
        assertTrue(tenantCtrl.getCurrentMonthUsage("tenant-y").getUsedTokens() >= 3);
    }

    private Agent buildAgent(String tenantId, TenantCtrl tenantCtrl, AgentTracer tracer) {
        MemoryManager memoryManager = new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
        ContextManager contextManager = new ContextManager(estimator);

        ChatModel mockModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                Usage usage = new Usage() {
                    @Override
                    public Long getPromptTokens() { return 2L; }

                    @Override
                    public Long getGenerationTokens() { return 3L; }

                    @Override
                    public Long getTotalTokens() { return 5L; }
                };
                return new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("Final Answer: final answer"))),
                        ChatResponseMetadata.builder().withUsage(usage).build());
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.empty();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };

        return new Agent(mockModel, new ToolRegistry(), new GuardRail(), new MetricsTracker(),
                memoryManager, contextManager, new DefaultContextStrategy(), 5,
                tenantId, "user-" + tenantId, 2000,
                60, 30, 2, true,
                tenantCtrl, tracer, "rag");
    }
}
