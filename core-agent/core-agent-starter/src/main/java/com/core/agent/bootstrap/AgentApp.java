package com.core.agent.bootstrap;

import com.core.agent.agent.domain.Agent;
import com.core.agent.context.application.ContextManager;
import com.core.agent.context.infrastructure.RagContextStrategy;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.memory.domain.MemoryStore;
import com.core.agent.memory.infrastructure.InMemoryMemoryManager;
import com.core.agent.memory.infrastructure.InMemoryMemoryStore;
import com.core.agent.tenant.application.QuotaStore;
import com.core.agent.tenant.application.RateLimiter;
import com.core.agent.tenant.application.UsageStore;
import com.core.agent.tenant.domain.TenantCtrl;
import com.core.agent.tenant.domain.TenantQuota;
import com.core.agent.tenant.infrastructure.InMemoryQuotaStore;
import com.core.agent.tenant.infrastructure.InMemoryTokenBucketRateLimiter;
import com.core.agent.tenant.infrastructure.InMemoryUsageStore;
import com.core.agent.tool.domain.ToolRegistry;
import com.core.agent.trace.domain.AgentTracer;
import com.core.agent.trace.infrastructure.MicrometerAgentTracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * CoreAgent Spring Boot 启动入口。
 *
 * <p>启动后对外暴露：</p>
 * <ul>
 *     <li>MCP Gateway REST 端点：POST /mcp/tools/list、POST /mcp/tools/call</li>
 *     <li>演示 Agent 通过 MCP Gateway 调用远端业务服务工具</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.core.agent")
public class AgentApp {

    private static final Logger log = LoggerFactory.getLogger(AgentApp.class);

    public static void main(String[] args) {
        SpringApplication.run(AgentApp.class, args);
    }

    @Bean
    public TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean
    public MemoryManager memoryManager(TokenCountEstimator tokenCountEstimator) {
        MemoryStore memoryStore = new InMemoryMemoryStore();
        return new InMemoryMemoryManager(memoryStore, tokenCountEstimator, 2000);
    }

    @Bean
    public ContextManager contextManager(TokenCountEstimator tokenCountEstimator) {
        return new ContextManager(tokenCountEstimator);
    }

    @Bean
    public GuardRail guardRail() {
        return new GuardRail();
    }

    @Bean
    public MetricsTracker metricsTracker() {
        return new MetricsTracker();
    }

    @Bean
    public TenantCtrl tenantCtrl() {
        QuotaStore quotaStore = new InMemoryQuotaStore();
        UsageStore usageStore = new InMemoryUsageStore();
        RateLimiter rateLimiter = new InMemoryTokenBucketRateLimiter(quotaStore);
        TenantCtrl tenantCtrl = new TenantCtrl(quotaStore, usageStore, rateLimiter);
        quotaStore.saveQuota(new TenantQuota("tenant-A", "PRO", 1_000_000, 10, 8000));
        return tenantCtrl;
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public AgentTracer agentTracer(MeterRegistry meterRegistry) {
        return new MicrometerAgentTracer(meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.demo.enabled", havingValue = "true")
    public CommandLineRunner demoRunner(ChatModel chatModel,
                                        McpGateway mcpGateway,
                                        GuardRail guardRail,
                                        MetricsTracker metrics,
                                        MemoryManager memoryManager,
                                        ContextManager contextManager,
                                        TenantCtrl tenantCtrl,
                                        AgentTracer tracer,
                                        ObjectMapper objectMapper) {
        return args -> {
            RagContextStrategy contextStrategy = new RagContextStrategy();

            Agent agent = new Agent(chatModel, new ToolRegistry(), guardRail, metrics, memoryManager,
                    contextManager, contextStrategy, 5,
                    "tenant-A", "user-001", 2000,
                    60, 30, 2, true,
                    tenantCtrl, tracer, "rag", mcpGateway);

            String sessionId = "session-001";
            String query = "What is the emergency procedure for chemical spill?";
            String answer = agent.run(sessionId, "rag", query);

            log.info("\n=== Final Answer ===\n{}", answer);
            metrics.printReport();

            String followUp = "What should I do after evacuating?";
            String followUpAnswer = agent.run(sessionId, "rag", followUp);
            log.info("\n=== Follow-up Answer ===\n{}", followUpAnswer);

            log.info("\n=== Tenant Usage ===\n{}", tenantCtrl.getCurrentMonthUsage("tenant-A"));
        };
    }
}
