package com.core.agent;

import com.core.agent.context.ContextManager;
import com.core.agent.context.RagContextStrategy;
import com.core.agent.memory.InMemoryMemoryManager;
import com.core.agent.memory.InMemoryMemoryStore;
import com.core.agent.memory.MemoryManager;
import com.core.agent.memory.MemoryStore;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Agent 入口类，基于 Spring AI + DeepSeek（OpenAI 兼容协议）。
 */
public class AgentApp {

    public static void main(String[] args) throws Exception {
        String apiKey = "sk-04ee721a4599449e8a5cea8a1e9bde6d";

        // 配置 Spring AI：baseUrl 指向 DeepSeek，复用 OpenAI 协议
        OpenAiApi openAiApi = new OpenAiApi("https://api.deepseek.com", apiKey);
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .withModel("deepseek-v4-pro")
                .withTemperature(0.7)
                .build();
        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, chatOptions);

        // 注册工具，带上风险等级和清晰的 API 式描述
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "retriever";
            }

            @Override
            public String description() {
                return "Retrieve relevant emergency safety documents from the knowledge base by keyword or question. " +
                       "Use this FIRST when the user asks about any safety procedure, regulation, or emergency response. " +
                       "Input: a plain text search query, e.g. 'chemical spill response procedure'. " +
                       "Output: a list of candidate documents in the format '[doc-id] document title/summary; [doc-id] document title/summary; ...'";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                // 模拟检索结果，doc-id 格式用于后续引用准确率计算
                return "[doc-1001] Chemical spill response guide; " +
                       "[doc-1002] Factory evacuation procedures; " +
                       "[doc-1003] Hazardous material handling checklist.";
            }
        });

        registry.register(new Tool() {
            @Override
            public String name() {
                return "reranker";
            }

            @Override
            public String description() {
                return "Rerank retrieved documents by relevance to the user's specific question. " +
                       "Use this AFTER retriever when more than 2 documents are returned, to select the most relevant ones. " +
                       "Input: JSON object with fields 'query' (string) and 'doc_ids' (list of strings), " +
                       "e.g. {\"query\": \"chemical spill response\", \"doc_ids\": [\"1001\", \"1002\", \"1003\"]}. " +
                       "Output: reranked doc_ids with relevance scores, e.g. '[doc-1001](0.95); [doc-1002](0.72); [doc-1003](0.45)'";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                return "[doc-1001](0.95); [doc-1002](0.72); [doc-1003](0.45)";
            }
        });

        registry.register(new Tool() {
            @Override
            public String name() {
                return "writer";
            }

            @Override
            public String description() {
                return "Generate the final answer with citations based on the selected relevant documents. " +
                       "Use this ONLY AFTER you have retrieved and reranked documents. Do not call this before retrieval. " +
                       "Input: JSON object with fields 'query' (string) and 'doc_ids' (list of selected strings), " +
                       "e.g. {\"query\": \"chemical spill response\", \"doc_ids\": [\"1001\", \"1002\"]}. " +
                       "Output: a complete, concise answer with inline [doc-id] citations, e.g. 'First step is evacuation [doc-1002], then ...'";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.MEDIUM;
            }

            @Override
            public String execute(String input) {
                return "Draft answer generated based on selected documents.";
            }
        });

        registry.register(new Tool() {
            @Override
            public String name() {
                return "auto_repair";
            }

            @Override
            public String description() {
                return "Automatically execute a system repair action without human confirmation. " +
                       "Use this only for self-healing scenarios when a critical system failure is detected. " +
                       "Input: a plain text description of the failure, e.g. 'disk full on server-01'. " +
                       "Output: the repair execution result.";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.CRITICAL;
            }

            @Override
            public String execute(String input) {
                return "Repair executed.";
            }
        });

        // 初始化内存版记忆模块（原型）
        MemoryStore memoryStore = new InMemoryMemoryStore();
        TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();
        MemoryManager memoryManager = new InMemoryMemoryManager(memoryStore, tokenCountEstimator, 2000);

        // 初始化上下文管理器：RAG 场景优先保留检索文档
        ContextManager contextManager = new ContextManager(tokenCountEstimator);
        RagContextStrategy contextStrategy = new RagContextStrategy();

        GuardRail guardRail = new GuardRail();
        MetricsTracker metrics = new MetricsTracker();

        // 初始化租户管控（内存版）
        QuotaStore quotaStore = new InMemoryQuotaStore();
        UsageStore usageStore = new InMemoryUsageStore();
        RateLimiter rateLimiter = new InMemoryTokenBucketRateLimiter(quotaStore);
        TenantCtrl tenantCtrl = new TenantCtrl(quotaStore, usageStore, rateLimiter);
        // 为 tenant-A 配置套餐：月度 100 万 Token，QPS 上限 10
        quotaStore.saveQuota(new TenantQuota("tenant-A", "PRO", 1_000_000, 10, 8000));

        // 初始化 Micrometer 追踪器（本地使用 SimpleMeterRegistry）
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentTracer tracer = new MicrometerAgentTracer(meterRegistry);

        // 模拟多租户场景：租户 A 的普通用户
        Agent agent = new Agent(chatModel, registry, guardRail, metrics, memoryManager,
                contextManager, contextStrategy, 5,
                "tenant-A", "user-001", 2000,
                60, 30, 2, true,
                tenantCtrl, tracer, "rag");

        String sessionId = "session-001";
        String query = "What is the emergency procedure for chemical spill?";
        String answer = agent.run(sessionId, "rag", query);

        System.out.println("\n=== Final Answer ===\n" + answer);
        metrics.printReport();

        // 演示多轮对话：第二问依赖第一问的记忆
        String followUp = "What should I do after evacuating?";
        String followUpAnswer = agent.run(sessionId, "rag", followUp);
        System.out.println("\n=== Follow-up Answer ===\n" + followUpAnswer);

        // 打印租户用量
        System.out.println("\n=== Tenant Usage ===\n" + tenantCtrl.getCurrentMonthUsage("tenant-A"));
    }
}
