package com.core.agent.strategy;

import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.infrastructure.PlanAndExecuteStrategy;
import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.context.application.ContextManager;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.memory.infrastructure.InMemoryMemoryManager;
import com.core.agent.memory.infrastructure.InMemoryMemoryStore;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanAndExecuteStrategy 测试。
 *
 * <p>验证 Plan-and-Execute 状态图的核心流程：</p>
 * <ol>
 *     <li>规划节点生成 JSON 步骤计划</li>
 *     <li>执行节点按顺序调用工具</li>
 *     <li>重新规划决策门根据执行结果路由</li>
 *     <li>结束节点生成最终答案</li>
 * </ol>
 */
class PlanAndExecuteStrategyTest {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    private ContextManager contextManager() {
        return new ContextManager(estimator);
    }

    private MemoryManager memoryManager() {
        return new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
    }

    /**
     * 测试标准 Plan-and-Execute 流程。
     *
     * <p>工具返回非空结果，决策门判断为"部分或全部成功"，直接路由到结束节点。</p>
     */
    @Test
    void shouldExecutePlanAndReturnFinalAnswer() {
        // 注册一个本地工具
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger toolCallCount = new AtomicInteger(0);
        registry.register(new Tool() {
            @Override
            public String name() {
                return "retriever";
            }

            @Override
            public String description() {
                return "retrieve relevant documents by query";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                toolCallCount.incrementAndGet();
                return "[doc-1001] relevant document for: " + input;
            }
        });

        // Mock ChatModel：只需要 2 次调用（规划 + 最终答案）
        ChatModel mockModel = new ChatModel() {
            private final AtomicInteger callCount = new AtomicInteger(0);

            @Override
            public ChatResponse call(Prompt prompt) {
                int count = callCount.incrementAndGet();
                String content;

                if (count == 1) {
                    // 第一次调用：规划节点请求生成计划
                    content = "[{" +
                            "\"stepNumber\": 1," +
                            "\"toolName\": \"retriever\"," +
                            "\"toolInput\": \"fire escape route\"," +
                            "\"purpose\": \"retrieve documents about fire escape routes\"" +
                            "}]";
                } else {
                    // 第二次调用：结束节点生成最终答案
                    content = "The fire escape route is on the north side.";
                }
                return buildResponse(content);
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

        MetricsTracker metrics = new MetricsTracker();

        // 构建 NodeContext
        NodeContext context = NodeContext.builder()
                .chatModel(mockModel)
                .toolRegistry(registry)
                .guardRail(new GuardRail())
                .metricsTracker(metrics)
                .memoryManager(memoryManager())
                .contextManager(contextManager())
                .maxIterations(5)
                .llmTimeoutSeconds(60)
                .toolTimeoutSeconds(30)
                .maxRetries(2)
                .enableReflection(false)
                .maxMemoryTokens(2000)
                .traceContext(NodeContext.Trace.builder()
                        .traceId("trace-plan-test")
                        .tenantId("tenant")
                        .userId("user")
                        .scene("rag")
                        .build())
                .build();

        // 编译并执行状态图
        PlanAndExecuteStrategy strategy = new PlanAndExecuteStrategy("rag");
        AgentState initialState = AgentState.initial("trace-plan-test", "tenant", "user", "rag")
                .withVariable("sessionId", "session-plan-test")
                .withVariable("query", "Where is the fire escape route?")
                .withCurrentNode("planNode");

        GraphResult result = strategy.compile().execute(initialState, context);

        // 验证
        assertTrue(result.isCompleted(), "Graph should complete successfully");
        assertEquals("The fire escape route is on the north side.", result.getFinalAnswer());
        assertEquals(1, toolCallCount.get(), "Retriever tool should be called exactly once");
    }

    /**
     * 测试重新规划流程。
     *
     * <p>第一次执行工具返回空结果，决策门判断为"计划假设错误"，触发 replan；
     * 第二次执行返回非空结果，直接结束。</p>
     */
    @Test
    void shouldReplanWhenAllResultsAreEmpty() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger toolCallCount = new AtomicInteger(0);
        registry.register(new Tool() {
            @Override
            public String name() {
                return "retriever";
            }

            @Override
            public String description() {
                return "retrieve relevant documents";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                int count = toolCallCount.incrementAndGet();
                // 第一次返回空，触发 replan；第二次返回非空结果
                return count == 1 ? "" : "relevant result after replan";
            }
        });

        ChatModel mockModel = new ChatModel() {
            private final AtomicInteger callCount = new AtomicInteger(0);

            @Override
            public ChatResponse call(Prompt prompt) {
                int count = callCount.incrementAndGet();
                String content;

                if (count == 1) {
                    // 第一次规划
                    content = "[{\"stepNumber\": 1, \"toolName\": \"retriever\", \"toolInput\": \"first query\", \"purpose\": \"first try\"}]";
                } else if (count == 2) {
                    // 重新规划
                    content = "[{\"stepNumber\": 1, \"toolName\": \"retriever\", \"toolInput\": \"second query\", \"purpose\": \"second try\"}]";
                } else {
                    // 最终答案
                    content = "Answer after replanning.";
                }
                return buildResponse(content);
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

        MetricsTracker metrics = new MetricsTracker();

        NodeContext context = NodeContext.builder()
                .chatModel(mockModel)
                .toolRegistry(registry)
                .guardRail(new GuardRail())
                .metricsTracker(metrics)
                .memoryManager(memoryManager())
                .contextManager(contextManager())
                .maxIterations(5)
                .llmTimeoutSeconds(60)
                .toolTimeoutSeconds(30)
                .maxRetries(2)
                .enableReflection(false)
                .maxMemoryTokens(2000)
                .traceContext(NodeContext.Trace.builder()
                        .traceId("trace-replan-test")
                        .tenantId("tenant")
                        .userId("user")
                        .scene("rag")
                        .build())
                .build();

        PlanAndExecuteStrategy strategy = new PlanAndExecuteStrategy("rag");
        AgentState initialState = AgentState.initial("trace-replan-test", "tenant", "user", "rag")
                .withVariable("sessionId", "session-replan-test")
                .withVariable("query", "Find relevant documents")
                .withCurrentNode("planNode");

        GraphResult result = strategy.compile().execute(initialState, context);

        assertTrue(result.isCompleted(), "Graph should complete after replan");
        assertEquals("Answer after replanning.", result.getFinalAnswer());
        assertEquals(2, toolCallCount.get(), "Tool should be called twice (original + replan)");
    }

    /**
     * 测试部分步骤成功时不触发重新规划。
     *
     * <p>只要有一个步骤返回了非空结果，就应该直接用已有结果回答，
     * 而不是为了失败的步骤重新规划。</p>
     */
    @Test
    void shouldNotReplanWhenPartialStepsSucceed() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "retriever";
            }

            @Override
            public String description() {
                return "retrieve relevant documents";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                // 模拟部分成功：只有特定输入能查到结果
                return input.contains("good") ? "good result" : "";
            }
        });

        ChatModel mockModel = new ChatModel() {
            private final AtomicInteger callCount = new AtomicInteger(0);

            @Override
            public ChatResponse call(Prompt prompt) {
                int count = callCount.incrementAndGet();
                String content;

                if (count == 1) {
                    // 规划两个步骤：一个会成功，一个会失败
                    content = "[" +
                            "{\"stepNumber\": 1, \"toolName\": \"retriever\", \"toolInput\": \"good query\", \"purpose\": \"should succeed\"}," +
                            "{\"stepNumber\": 2, \"toolName\": \"retriever\", \"toolInput\": \"bad query\", \"purpose\": \"will return empty\"}" +
                            "]";
                } else {
                    content = "Final answer based on partial results.";
                }
                return buildResponse(content);
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

        MetricsTracker metrics = new MetricsTracker();

        NodeContext context = NodeContext.builder()
                .chatModel(mockModel)
                .toolRegistry(registry)
                .guardRail(new GuardRail())
                .metricsTracker(metrics)
                .memoryManager(memoryManager())
                .contextManager(contextManager())
                .maxIterations(5)
                .llmTimeoutSeconds(60)
                .toolTimeoutSeconds(30)
                .maxRetries(2)
                .enableReflection(false)
                .maxMemoryTokens(2000)
                .traceContext(NodeContext.Trace.builder()
                        .traceId("trace-partial-test")
                        .tenantId("tenant")
                        .userId("user")
                        .scene("rag")
                        .build())
                .build();

        PlanAndExecuteStrategy strategy = new PlanAndExecuteStrategy("rag");
        AgentState initialState = AgentState.initial("trace-partial-test", "tenant", "user", "rag")
                .withVariable("sessionId", "session-partial-test")
                .withVariable("query", "Mixed result query")
                .withCurrentNode("planNode");

        GraphResult result = strategy.compile().execute(initialState, context);

        assertTrue(result.isCompleted(), "Graph should complete without replan");
        assertEquals("Final answer based on partial results.", result.getFinalAnswer());
        // 验证只调用了 2 次 LLM：规划 + 最终答案，没有额外的 replan 规划
        assertEquals(2, (int) result.getFinalState().getVariable("llmCallCount"));
    }

    private ChatResponse buildResponse(String content) {
        AssistantMessage message = new AssistantMessage(content);
        Generation generation = new Generation(message);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .withUsage(new Usage() {
                    @Override
                    public Long getPromptTokens() {
                        return 10L;
                    }

                    @Override
                    public Long getGenerationTokens() {
                        return 5L;
                    }

                    @Override
                    public Long getTotalTokens() {
                        return 15L;
                    }
                })
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
