package com.core.agent.bootstrap;

import com.core.agent.agent.domain.Agent;
import com.core.agent.checkpoint.application.CheckpointService;
import com.core.agent.checkpoint.domain.CheckpointStore;
import com.core.agent.checkpoint.infrastructure.CheckpointMapper;
import com.core.agent.checkpoint.infrastructure.MyBatisPlusCheckpointStore;
import com.core.agent.context.application.ContextManager;
import com.core.agent.context.domain.ContextStrategy;
import com.core.agent.context.infrastructure.RagContextStrategy;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.A2aGateway;
import com.core.agent.multiagent.infrastructure.HttpA2aGateway;
import com.core.agent.multiagent.infrastructure.InMemoryAgentRegistry;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.memory.application.SharedMemoryManager;
import com.core.agent.memory.domain.MemoryStore;
import com.core.agent.memory.domain.SharedMemoryStore;
import com.core.agent.memory.infrastructure.InMemorySharedMemoryStore;
import com.core.agent.memory.infrastructure.InMemoryMemoryManager;
import com.core.agent.memory.infrastructure.InMemoryMemoryStore;
import com.core.agent.memory.infrastructure.MyBatisPlusSharedMemoryStore;
import com.core.agent.memory.infrastructure.SharedMemoryMapper;
import com.core.agent.tenant.application.QuotaStore;
import com.core.agent.tenant.application.RateLimiter;
import com.core.agent.tenant.application.UsageStore;
import com.core.agent.tenant.domain.TenantCtrl;
import com.core.agent.tenant.domain.TenantQuota;
import com.core.agent.tenant.infrastructure.InMemoryQuotaStore;
import com.core.agent.tenant.infrastructure.InMemoryTokenBucketRateLimiter;
import com.core.agent.tenant.infrastructure.InMemoryUsageStore;
import com.core.agent.rag.infrastructure.VectorRetrieverTool;
import com.core.agent.tool.domain.ToolRegistry;
import com.core.agent.trace.domain.AgentTracer;
import com.core.agent.trace.infrastructure.MicrometerAgentTracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;

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
@MapperScan({
        "com.core.agent.checkpoint.infrastructure",
        "com.core.agent.memory.infrastructure"
})
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
    public CheckpointStore checkpointStore(CheckpointMapper checkpointMapper, ObjectMapper objectMapper) {
        return new MyBatisPlusCheckpointStore(checkpointMapper, objectMapper);
    }

    @Bean
    public CheckpointService checkpointService(CheckpointStore checkpointStore) {
        return new CheckpointService(checkpointStore);
    }

    @Bean
    public SharedMemoryStore sharedMemoryStore(SharedMemoryMapper sharedMemoryMapper) {
        return new MyBatisPlusSharedMemoryStore(sharedMemoryMapper);
    }

    @Bean
    public SharedMemoryManager sharedMemoryManager(SharedMemoryStore sharedMemoryStore) {
        return new SharedMemoryManager(sharedMemoryStore);
    }

    @Bean
    public AgentRegistry agentRegistry() {
        return new InMemoryAgentRegistry();
    }

    @Bean
    public ToolRegistry toolRegistry(VectorStore vectorStore,
                                     @Value("${rag.retriever.top-k:5}") int topK) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new VectorRetrieverTool("dense_retrieve", vectorStore, topK));
        return registry;
    }

    @Bean
    public ContextStrategy contextStrategy() {
        return new RagContextStrategy();
    }

    @Bean
    public Agent agent(ChatModel chatModel,
                       ToolRegistry toolRegistry,
                       GuardRail guardRail,
                       MetricsTracker metricsTracker,
                       MemoryManager memoryManager,
                       ContextManager contextManager,
                       ContextStrategy contextStrategy,
                       TenantCtrl tenantCtrl,
                       AgentTracer agentTracer,
                       McpGateway mcpGateway,
                       SharedMemoryManager sharedMemoryManager,
                       AgentRegistry agentRegistry,
                       A2aGateway a2aGateway) {
        return new Agent(chatModel, toolRegistry, guardRail, metricsTracker, memoryManager,
                contextManager, contextStrategy, 5,
                "tenant-A", "user-001", 2000,
                60, 30, 2, true,
                tenantCtrl, agentTracer, "rag", mcpGateway,
                sharedMemoryManager, agentRegistry, a2aGateway, null);
    }

    @Bean
    public A2aGateway a2aGateway(ObjectMapper objectMapper) {
        return new HttpA2aGateway(objectMapper);
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
            com.core.agent.agent.domain.AgentResult result = agent.runWithResult(sessionId, "rag", query);

            log.info("\n=== Final Answer ===\n{}", result.getAnswer());
            log.info("=== Confidence: {} | Citations: {} ===", result.getConfidence(), result.getCitations());
            metrics.printReport();

            String followUp = "What should I do after evacuating?";
            com.core.agent.agent.domain.AgentResult followUpResult = agent.runWithResult(sessionId, "rag", followUp);
            log.info("\n=== Follow-up Answer ===\n{}", followUpResult.getAnswer());
            log.info("=== Confidence: {} | Citations: {} ===", followUpResult.getConfidence(), followUpResult.getCitations());

            log.info("\n=== Tenant Usage ===\n{}", tenantCtrl.getCurrentMonthUsage("tenant-A"));
        };
    }
}
