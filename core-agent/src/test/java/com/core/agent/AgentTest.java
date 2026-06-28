package com.core.agent;

import com.core.agent.context.ContextManager;
import com.core.agent.context.DefaultContextStrategy;
import com.core.agent.memory.InMemoryMemoryManager;
import com.core.agent.memory.InMemoryMemoryStore;
import com.core.agent.memory.MemoryManager;
import com.core.agent.memory.MemoryMessage;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 行为测试，使用 Mock ChatModel 验证 Reflection、GuardRail、Memory 集成。
 */
class AgentTest {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    private ContextManager contextManager() {
        return new ContextManager(estimator);
    }

    @Test
    void shouldReflectWhenToolNotFound() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "retriever";
            }

            @Override
            public String description() {
                return "retrieve docs";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                return "[doc-1001] doc";
            }
        });

        MemoryManager memoryManager = new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
        MetricsTracker metrics = new MetricsTracker();

        // Mock ChatModel：第一次返回非法工具，触发 Reflection；第二次返回最终答案
        ChatModel mockModel = new ChatModel() {
            private int callCount = 0;

            @Override
            public ChatResponse call(Prompt prompt) {
                callCount++;
                String content;
                if (callCount == 1) {
                    content = "Thought: I will call a tool.\nAction: unknown_tool\nAction Input: test";
                } else if (callCount == 2) {
                    content = "Reflection: The tool 'unknown_tool' does not exist. I should use 'retriever'.";
                } else {
                    content = "Final Answer: answer after reflection";
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

        Agent agent = new Agent(mockModel, registry, new GuardRail(), metrics,
                memoryManager, contextManager(), 5);
        String answer = agent.run("session-reflect", "test query");

        assertEquals("answer after reflection", answer);

        List<MemoryMessage> history = memoryManager.getHistory("session-reflect", 2000);
        boolean hasReflection = history.stream()
                .anyMatch(m -> m.getRole().equals("system") && m.getContent().contains("unknown_tool"));
        assertTrue(hasReflection, "Reflection should be saved to memory");
    }

    @Test
    void shouldHandleLlmTimeout() throws Exception {
        MemoryManager memoryManager = new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
        MetricsTracker metrics = new MetricsTracker();

        ChatModel slowModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    Thread.sleep(3000); // 模拟超慢 LLM
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return buildResponse("Final Answer: should not reach here");
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

        Agent agent = new Agent(slowModel, new ToolRegistry(), new GuardRail(), metrics,
                memoryManager, contextManager(), new DefaultContextStrategy(), 3,
                "tenant", "user", 2000, 1, 30, 0);
        String answer = agent.run("session-llm-timeout", "test");

        assertTrue(answer.contains("LLM call failed"), "Should return LLM failure message");
        assertTrue(answer.contains("timed out") || answer.contains("timed out"),
                "Failure should mention timeout");
    }

    @Test
    void shouldHandleToolTimeout() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "slow_tool";
            }

            @Override
            public String description() {
                return "slow tool";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }
        });

        MemoryManager memoryManager = new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
        MetricsTracker metrics = new MetricsTracker();

        ChatModel mockModel = new ChatModel() {
            private int callCount = 0;

            @Override
            public ChatResponse call(Prompt prompt) {
                callCount++;
                if (callCount == 1) {
                    return buildResponse("Thought: call slow tool\nAction: slow_tool\nAction Input: test");
                }
                return buildResponse("Final Answer: recovered");
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

        Agent agent = new Agent(mockModel, registry, new GuardRail(), metrics,
                memoryManager, contextManager(), new DefaultContextStrategy(), 3,
                "tenant", "user", 2000, 60, 1, 0);
        String answer = agent.run("session-tool-timeout", "test");

        assertEquals("recovered", answer);
        List<MemoryMessage> history = memoryManager.getHistory("session-tool-timeout", 2000);
        boolean hasToolError = history.stream()
                .anyMatch(m -> m.getContent().contains("tool execution failed"));
        assertTrue(hasToolError, "Tool timeout error should be saved to memory");
    }

    @Test
    void shouldSkipReflectionWhenDisabled() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "retriever";
            }

            @Override
            public String description() {
                return "retrieve docs";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                return "[doc-1001] doc";
            }
        });

        MemoryManager memoryManager = new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
        MetricsTracker metrics = new MetricsTracker();

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        ChatModel mockModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    return buildResponse("Thought: call wrong tool\nAction: unknown_tool\nAction Input: test");
                }
                // 如果 Reflection 被触发，这里会多一次调用；禁用后应该直接返回 Final Answer
                return buildResponse("Final Answer: answer without reflection");
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

        Agent agent = new Agent(mockModel, registry, new GuardRail(), metrics,
                memoryManager, contextManager(), new DefaultContextStrategy(), 3,
                "tenant", "user", 2000, 60, 30, 0, false);
        String answer = agent.run("session-no-reflect", "test");

        assertEquals("answer without reflection", answer);
        assertEquals(2, callCount.get(), "Reflection disabled should not trigger extra LLM call");
    }

    @Test
    void shouldHandleLlmException() throws Exception {
        MemoryManager memoryManager = new InMemoryMemoryManager(new InMemoryMemoryStore(), estimator, 2000);
        MetricsTracker metrics = new MetricsTracker();

        ChatModel failingModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new RuntimeException("API rate limit exceeded");
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

        Agent agent = new Agent(failingModel, new ToolRegistry(), new GuardRail(), metrics,
                memoryManager, contextManager(), new DefaultContextStrategy(), 3,
                "tenant", "user", 2000, 60, 30, 1);
        String answer = agent.run("session-llm-error", "test");

        assertTrue(answer.contains("LLM call failed"), "Should return LLM failure message");
        assertTrue(answer.contains("API rate limit exceeded"), "Should include original error");
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
