# Multi-Agent 架构设计

## 背景

CoreAgent 的单 Agent ReAct 模式在简单问答场景下够用，但企业级场景需要更复杂的协作模式：
- **并行执行**：多个子任务同时进行，降低总延迟
- **职责隔离**：每个 Agent 专注一个领域，prompt 更简洁
- **容错能力**：单个 Agent 失败不影响整体
- **可观测性**：追踪每个 Agent 的执行过程

本文档基于 CoreAgent 平台层扩展，设计 Multi-Agent 架构。

---

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         业务场景层                                │
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │  应急响应编排  │    │  RAG问答编排  │    │  运维诊断编排  │       │
│  │  Workflow:    │    │  Workflow:    │    │  Workflow:    │       │
│  │  retrieve →   │    │  retrieve →   │    │  diagnose →   │       │
│  │  analyze →    │    │  rerank →     │    │  fix →        │       │
│  │  respond      │    │  write        │    │  verify       │       │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘       │
│         │                   │                   │                │
├─────────┼───────────────────┼───────────────────┼────────────────┤
│         ▼                   ▼                   ▼                │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                   Orchestrator 编排层                        │ │
│  │                                                             │ │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐   │ │
│  │  │ AgentRegistry│ │ WorkflowEngine│ │ MessageBus         │   │ │
│  │  │ Agent注册发现 │ │ 工作流引擎    │ │ 消息总线            │   │ │
│  │  └─────────────┘ └─────────────┘ └─────────────────────┘   │ │
│  │                                                             │ │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐   │ │
│  │  │ AgentPool   │ │ ContextPool  │ │ ErrorHandler        │   │ │
│  │  │ Agent实例池  │ │ 上下文共享池  │ │ 错误处理与降级       │   │ │
│  │  └─────────────┘ └─────────────┘ └─────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              │                                   │
├──────────────────────────────┼───────────────────────────────────┤
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                   Agent 实例层                               │ │
│  │                                                             │ │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │ │
│  │  │ RetrieverAgent│ │ AnalyzerAgent│ │ WriterAgent  │        │ │
│  │  │ 专注检索       │ │ 专注分析      │ │ 专注生成      │        │ │
│  │  │ Tools:        │ │ Tools:       │ │ Tools:       │        │ │
│  │  │  dense_retrieve│ │  entity_extract│ │  summarize  │        │ │
│  │  │  sparse_retrieve│ │  risk_assess │ │  format      │        │ │
│  │  └──────────────┘ └──────────────┘ └──────────────┘        │ │
│  │                                                             │ │
│  │  每个 Agent 继承 CoreAgent 平台层能力：                       │ │
│  │  - ToolRegistry (自己的工具子集)                              │ │
│  │  - GuardRail (自己的风险规则)                                 │ │
│  │  - ContextManager (自己的上下文策略)                          │ │
│  │  - AgentTracer (统一追踪)                                    │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              │                                   │
├──────────────────────────────┼───────────────────────────────────┤
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                   CoreAgent 平台层（复用）                    │ │
│  │   ToolRegistry │ PreProcessor │ ContextManager │ GuardRail  │ │
│  │   TenantCtrl   │ AgentTracer  │ AgentExecutor (ReAct引擎)   │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心模块设计

### 1. AgentRegistry — Agent 注册中心

**解决的问题**：像管理微服务一样管理 Agent，每个 Agent 有自己的元数据、工具集、配置。

```java
/**
 * Agent 定义 — 业务方实现此接口
 */
public interface CoreAgent {
    /** Agent 唯一标识 */
    String agentId();

    /** Agent 描述，用于编排时的语义匹配 */
    String description();

    /** 该 Agent 擅长的任务类型 */
    List<String> capabilities();

    /** 执行逻辑（内部使用 ReAct 或其他策略） */
    AgentResponse execute(AgentRequest request, AgentContext context);
}

/**
 * Agent 元数据 — 平台管控维度
 */
@Data
public class AgentMeta {
    private String agentId;           // retriever-agent
    private String scene;             // rag / ops / analytics
    private List<String> tools;       // 该 Agent 可用的工具列表
    private RiskLevel maxRiskLevel;   // 允许的最高风险等级
    private int timeoutMs;            // 单次执行超时
    private int maxRetry;             // 最大重试次数
    private int maxConcurrency;       // 最大并发数
    private String fallbackAgent;     // 降级 Agent
    private Map<String, Object> config; // Agent 专属配置
}

/**
 * Agent 注册中心 — 管理所有 Agent 实例
 */
@Component
public class AgentRegistry {
    private final Map<String, CoreAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentMeta> metaMap = new ConcurrentHashMap<>();

    public void register(CoreAgent agent, AgentMeta meta) {
        agents.put(agent.agentId(), agent);
        metaMap.put(agent.agentId(), meta);
    }

    /** 按能力查找 Agent */
    public List<CoreAgent> findByCapability(String capability) {
        return agents.values().stream()
            .filter(a -> metaMap.get(a.agentId()).getScene().equals(capability))
            .collect(Collectors.toList());
    }

    /** 按 ID 获取 Agent */
    public CoreAgent getAgent(String agentId) {
        return agents.get(agentId);
    }
}
```

---

### 2. WorkflowEngine — 工作流引擎

**解决的问题**：定义多个 Agent 的协作关系，支持串行、并行、条件分支。

```java
/**
 * 工作流定义 — 描述 Agent 之间的协作关系
 */
@Data
public class WorkflowDefinition {
    private String workflowId;
    private String name;
    private List<WorkflowNode> nodes;
    private List<WorkflowEdge> edges;
}

/**
 * 工作流节点 — 一个 Agent 调用
 */
@Data
public class WorkflowNode {
    private String nodeId;
    private String agentId;           // 执行该节点的 Agent
    private Map<String, Object> inputMapping;  // 输入参数映射
    private String outputVariable;    // 输出变量名
    private int timeoutMs;
    private int maxRetry;
    private ExecutionMode mode;       // SEQUENTIAL / PARALLEL
}

/**
 * 工作流边 — 节点之间的依赖关系
 */
@Data
public class WorkflowEdge {
    private String fromNode;
    private String toNode;
    private String condition;         // 条件表达式（可选）
}

/**
 * 执行模式
 */
public enum ExecutionMode {
    SEQUENTIAL,  // 串行：等待前一个节点完成
    PARALLEL     // 并行：与其他节点同时执行
}
```

#### 工作流引擎实现

```java
/**
 * 工作流引擎 — 解析并执行工作流定义
 */
@Component
public class WorkflowEngine {
    private final AgentRegistry agentRegistry;
    private final MessageBus messageBus;
    private final AgentTracer tracer;

    /**
     * 执行工作流
     */
    public WorkflowResult execute(WorkflowDefinition workflow,
                                   WorkflowContext context) {
        AgentTrace trace = tracer.startWorkflowTrace(workflow.getWorkflowId());

        try {
            // 1. 构建执行计划（拓扑排序）
            ExecutionPlan plan = buildExecutionPlan(workflow);

            // 2. 按计划执行
            Map<String, NodeResult> results = new HashMap<>();
            for (ExecutionStage stage : plan.getStages()) {
                if (stage.isParallel()) {
                    // 并行执行该阶段的所有节点
                    List<CompletableFuture<NodeResult>> futures = stage.getNodes()
                        .stream()
                        .map(node -> executeNodeAsync(node, context, results))
                        .collect(Collectors.toList());

                    // 等待所有节点完成
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                    // 收集结果
                    for (CompletableFuture<NodeResult> future : futures) {
                        NodeResult result = future.join();
                        results.put(result.getNodeId(), result);
                    }
                } else {
                    // 串行执行
                    for (WorkflowNode node : stage.getNodes()) {
                        NodeResult result = executeNode(node, context, results);
                        results.put(node.getNodeId(), result);

                        // 检查是否需要提前终止
                        if (result.isFailed() && !node.isContinueOnError()) {
                            return WorkflowResult.failed(result.getError());
                        }
                    }
                }
            }

            // 3. 汇总结果
            return WorkflowResult.success(results);

        } catch (Exception e) {
            tracer.recordError(trace, e);
            return WorkflowResult.failed(e.getMessage());
        } finally {
            tracer.endTrace(trace);
        }
    }

    /**
     * 执行单个节点
     */
    private NodeResult executeNode(WorkflowNode node, WorkflowContext context,
                                    Map<String, NodeResult> previousResults) {
        // 1. 构建 Agent 请求
        AgentRequest request = buildRequest(node, context, previousResults);

        // 2. 获取 Agent
        CoreAgent agent = agentRegistry.getAgent(node.getAgentId());

        // 3. 执行（带超时和重试）
        int retryCount = 0;
        while (retryCount <= node.getMaxRetry()) {
            try {
                AgentResponse response = agent.execute(request, context);

                // 4. 发布结果到消息总线
                messageBus.publish(node.getOutputVariable(), response);

                return NodeResult.success(node.getNodeId(), response);

            } catch (TimeoutException e) {
                retryCount++;
                if (retryCount > node.getMaxRetry()) {
                    return NodeResult.timeout(node.getNodeId());
                }
            } catch (Exception e) {
                return NodeResult.failed(node.getNodeId(), e.getMessage());
            }
        }

        return NodeResult.failed(node.getNodeId(), "Max retry exceeded");
    }

    /**
     * 异步执行节点（用于并行）
     */
    private CompletableFuture<NodeResult> executeNodeAsync(WorkflowNode node,
                                                            WorkflowContext context,
                                                            Map<String, NodeResult> previousResults) {
        return CompletableFuture.supplyAsync(
            () -> executeNode(node, context, previousResults),
            executorService
        );
    }
}
```

---

### 3. MessageBus — 消息总线

**解决的问题**：Agent 之间如何传递数据，支持发布-订阅模式。

```java
/**
 * 消息总线 — Agent 之间通信
 */
@Component
public class MessageBus {
    private final Map<String, List<MessageSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Object> messageStore = new ConcurrentHashMap<>();

    /**
     * 发布消息
     */
    public void publish(String topic, Object message) {
        messageStore.put(topic, message);

        List<MessageSubscriber> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers != null) {
            for (MessageSubscriber subscriber : topicSubscribers) {
                try {
                    subscriber.onMessage(topic, message);
                } catch (Exception e) {
                    // 记录错误但不阻塞其他订阅者
                    log.error("Subscriber error: {}", subscriber.getId(), e);
                }
            }
        }
    }

    /**
     * 订阅消息
     */
    public void subscribe(String topic, MessageSubscriber subscriber) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                   .add(subscriber);
    }

    /**
     * 获取最新消息
     */
    public <T> T getLatest(String topic, Class<T> type) {
        Object message = messageStore.get(topic);
        return type.cast(message);
    }
}

/**
 * 消息订阅者接口
 */
public interface MessageSubscriber {
    String getId();
    void onMessage(String topic, Object message);
}
```

---

### 4. AgentPool — Agent 实例池

**解决的问题**：Agent 实例复用，避免重复创建。

```java
/**
 * Agent 实例池 — 管理 Agent 实例的生命周期
 */
@Component
public class AgentPool {
    private final Map<String, BlockingQueue<CoreAgent>> pools = new ConcurrentHashMap<>();
    private final AgentFactory agentFactory;

    /**
     * 获取 Agent 实例
     */
    public CoreAgent acquire(String agentId, int timeoutMs) throws TimeoutException {
        BlockingQueue<CoreAgent> pool = pools.get(agentId);
        if (pool == null) {
            // 首次使用，创建池
            pool = new LinkedBlockingQueue<>();
            pools.put(agentId, pool);
        }

        CoreAgent agent = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (agent == null) {
            // 池为空，创建新实例
            agent = agentFactory.create(agentId);
        }

        return agent;
    }

    /**
     * 归还 Agent 实例
     */
    public void release(String agentId, CoreAgent agent) {
        BlockingQueue<CoreAgent> pool = pools.get(agentId);
        if (pool != null) {
            pool.offer(agent);
        }
    }
}
```

---

### 5. ContextPool — 上下文共享池

**解决的问题**：多个 Agent 如何共享上下文信息，避免重复传递。

```java
/**
 * 上下文共享池 — 多 Agent 共享信息
 */
@Component
public class ContextPool {
    private final Map<String, SharedContext> contexts = new ConcurrentHashMap<>();

    /**
     * 创建共享上下文
     */
    public SharedContext createContext(String workflowId) {
        SharedContext context = new SharedContext(workflowId);
        contexts.put(workflowId, context);
        return context;
    }

    /**
     * 获取共享上下文
     */
    public SharedContext getContext(String workflowId) {
        return contexts.get(workflowId);
    }

    /**
     * 清理上下文（工作流结束后）
     */
    public void removeContext(String workflowId) {
        contexts.remove(workflowId);
    }
}

/**
 * 共享上下文 — 存储多个 Agent 共享的数据
 */
@Data
public class SharedContext {
    private String workflowId;
    private Map<String, Object> sharedData = new ConcurrentHashMap<>();
    private List<AgentMessage> messageHistory = new CopyOnWriteArrayList<>();

    /**
     * 写入共享数据
     */
    public void put(String key, Object value) {
        sharedData.put(key, value);
    }

    /**
     * 读取共享数据
     */
    public <T> T get(String key, Class<T> type) {
        return type.cast(sharedData.get(key));
    }

    /**
     * 记录消息
     */
    public void addMessage(AgentMessage message) {
        messageHistory.add(message);
    }
}

/**
 * Agent 消息
 */
@Data
public class AgentMessage {
    private String fromAgent;
    private String toAgent;
    private String messageType;  // REQUEST / RESPONSE / ERROR
    private Object content;
    private long timestamp;
}
```

---

### 6. AgentTracer — 调用链追踪

**解决的问题**：Multi-Agent 场景下，多个 Agent 并行执行，需要追踪每个 Agent 的执行过程。

**技术选型**：基于 **Micrometer + Prometheus + Grafana**（已有监控栈），通过 **ThreadLocal + MDC** 传递 traceId。

#### TraceContext 传递机制

```java
/**
 * 链路上下文 — 通过 ThreadLocal 传递
 */
@Data
public class TraceContext {
    private String traceId;           // 全局唯一，UUID
    private String tenantId;          // 租户 ID
    private String scene;             // 场景
    private String workflowId;        // 工作流 ID（Multi-Agent 场景）
    private String agentId;           // 当前 Agent ID
    private long startTime;
    private Map<String, Object> attributes = new HashMap<>();
}

/**
 * ThreadLocal 持有者
 */
public class TraceContextHolder {
    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
        MDC.put("traceId", ctx.getTraceId());
        MDC.put("tenantId", ctx.getTenantId());
        MDC.put("workflowId", ctx.getWorkflowId());
        MDC.put("agentId", ctx.getAgentId());
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
        MDC.clear();
    }
}
```

#### AgentTracer 实现

```java
/**
 * Agent 追踪器 — 支持 Multi-Agent 场景
 */
@Component
public class AgentTracer {
    private final MeterRegistry meterRegistry;

    /**
     * 开始 Workflow Trace
     */
    public TraceContext startWorkflowTrace(String tenantId, String scene,
                                            String workflowId) {
        String traceId = UUID.randomUUID().toString();

        TraceContext ctx = TraceContext.create(traceId, tenantId, scene);
        ctx.setWorkflowId(workflowId);
        TraceContextHolder.set(ctx);

        // 记录工作流指标
        meterRegistry.counter("workflow.request.total",
            "tenant", tenantId,
            "workflow", workflowId
        ).increment();

        return ctx;
    }

    /**
     * 开始 Agent Span
     */
    public Timer.Sample startAgentSpan(String agentId) {
        // 更新当前上下文的 agentId
        TraceContext ctx = TraceContextHolder.get();
        if (ctx != null) {
            ctx.setAgentId(agentId);
            MDC.put("agentId", agentId);
        }

        return Timer.start(meterRegistry);
    }

    /**
     * 结束 Agent Span
     */
    public void endAgentSpan(Timer.Sample sample, String agentId, String status) {
        sample.stop(meterRegistry.timer("agent.execute.duration",
            "agent", agentId,
            "status", status
        ));
    }

    /**
     * 记录 Tool 调用
     */
    public void recordToolCall(String toolName, String agentId,
                                long durationMs, String status) {
        meterRegistry.counter("agent.tool.call.total",
            "tool", toolName,
            "agent", agentId,
            "status", status
        ).increment();

        meterRegistry.timer("agent.tool.call.duration",
            "tool", toolName,
            "agent", agentId
        ).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录并行执行
     */
    public void recordParallelExecution(String workflowId, int parallelCount) {
        meterRegistry.gauge("workflow.parallel.count",
            Tags.of("workflow", workflowId),
            parallelCount);
    }
}
```

#### 并行执行时传递 traceId

```java
/**
 * 可追踪的线程池 — Multi-Agent 并行执行
 */
@Component
public class TraceableExecutorService {
    private final ExecutorService delegate;

    public <T> Future<T> submit(Callable<T> task) {
        TraceContext parentCtx = TraceContextHolder.get();

        return delegate.submit(() -> {
            try {
                if (parentCtx != null) {
                    TraceContextHolder.set(parentCtx);
                }
                return task.call();
            } finally {
                TraceContextHolder.clear();
            }
        });
    }

    public <T> List<T> invokeAll(List<Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = tasks.stream()
            .map(this::submit)
            .collect(Collectors.toList());

        return futures.stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toList());
    }
}
```

#### WorkflowEngine 集成

```java
@Component
public class WorkflowEngine {
    private final AgentTracer tracer;
    private final TraceableExecutorService executor;

    public WorkflowResult execute(WorkflowDefinition workflow, WorkflowContext context) {
        // 1. 开始 Workflow Trace
        TraceContext traceCtx = tracer.startWorkflowTrace(
            context.getTenantId(), context.getScene(), workflow.getWorkflowId());

        try {
            for (ExecutionStage stage : plan.getStages()) {
                if (stage.isParallel()) {
                    // 2. 记录并行执行
                    tracer.recordParallelExecution(
                        workflow.getWorkflowId(), stage.getNodes().size());

                    // 3. 并行执行 — traceId 自动传递
                    List<Callable<NodeResult>> tasks = stage.getNodes().stream()
                        .map(node -> (Callable<NodeResult>) () -> {
                            // 开始 Agent Span
                            Timer.Sample sample = tracer.startAgentSpan(node.getAgentId());
                            try {
                                NodeResult result = executeNode(node, context);
                                tracer.endAgentSpan(sample, node.getAgentId(), "SUCCESS");
                                return result;
                            } catch (Exception e) {
                                tracer.endAgentSpan(sample, node.getAgentId(), "FAILED");
                                throw e;
                            }
                        })
                        .collect(Collectors.toList());

                    executor.invokeAll(tasks);

                } else {
                    // 4. 串行执行
                    for (WorkflowNode node : stage.getNodes()) {
                        Timer.Sample sample = tracer.startAgentSpan(node.getAgentId());
                        try {
                            executeNode(node, context);
                            tracer.endAgentSpan(sample, node.getAgentId(), "SUCCESS");
                        } catch (Exception e) {
                            tracer.endAgentSpan(sample, node.getAgentId(), "FAILED");
                            throw e;
                        }
                    }
                }
            }

            return WorkflowResult.success();

        } finally {
            TraceContextHolder.clear();
        }
    }
}
```

#### 日志输出示例

```
2024-01-15 10:30:45 [pool-1-thread-1] [a1b2c3d4] [tenant-001] [emergency-response] [retriever-agent] INFO WorkflowEngine - Agent execution started
2024-01-15 10:30:45 [pool-1-thread-2] [a1b2c3d4] [tenant-001] [emergency-response] [floor-agent] INFO WorkflowEngine - Agent execution started
2024-01-15 10:30:45 [pool-1-thread-3] [a1b2c3d4] [tenant-001] [emergency-response] [equipment-agent] INFO WorkflowEngine - Agent execution started
2024-01-15 10:30:46 [pool-1-thread-1] [a1b2c3d4] [tenant-001] [emergency-response] [retriever-agent] INFO ToolExecutor - Tool dense_retrieve called, duration=150ms
```

#### Prometheus 指标

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `workflow.request.total` | Counter | tenant, workflow | 工作流请求总量 |
| `workflow.parallel.count` | Gauge | workflow | 并行 Agent 数量 |
| `agent.execute.duration` | Timer | agent, status | Agent 执行耗时 |
| `agent.tool.call.total` | Counter | tool, agent, status | Tool 调用次数 |
| `agent.tool.call.duration` | Timer | tool, agent | Tool 调用耗时 |

---

### 7. ErrorHandler — 错误处理与降级

**解决的问题**：单个 Agent 失败时，如何降级处理，不影响整体工作流。

```java
/**
 * 错误处理器 — Agent 失败时的降级策略
 */
@Component
public class ErrorHandler {

    /**
     * 处理 Agent 执行错误
     */
    public ErrorResponse handle(String agentId, Exception error,
                                 ErrorHandlerContext context) {
        AgentMeta meta = context.getAgentMeta(agentId);

        // 1. 检查是否有降级 Agent
        if (meta.getFallbackAgent() != null) {
            CoreAgent fallbackAgent = context.getAgent(meta.getFallbackAgent());
            if (fallbackAgent != null) {
                try {
                    AgentResponse response = fallbackAgent.execute(
                        context.getRequest(), context.getContext());
                    return ErrorResponse.fallback(response);
                } catch (Exception fallbackError) {
                    // 降级也失败了
                }
            }
        }

        // 2. 检查是否可以重试
        if (context.getRetryCount() < meta.getMaxRetry()) {
            return ErrorResponse.retry();
        }

        // 3. 检查是否可以跳过（非关键节点）
        if (context.isOptionalNode()) {
            return ErrorResponse.skip();
        }

        // 4. 返回错误
        return ErrorResponse.fail(error.getMessage());
    }
}

/**
 * 错误响应
 */
@Data
public class ErrorResponse {
    private ErrorAction action;  // RETRY / FALLBACK / SKIP / FAIL
    private String message;
    private AgentResponse fallbackResponse;
}
```

---

## 业务场景接入示例

### 场景一：应急响应工作流

```java
/**
 * 应急响应工作流定义
 */
@Component
public class EmergencyResponseWorkflow {

    @PostConstruct
    public void register(WorkflowRegistry registry) {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
            .workflowId("emergency-response")
            .name("应急响应工作流")
            .nodes(List.of(
                // 并行执行：检索文档 + 查楼层信息 + 查消防设备
                WorkflowNode.builder()
                    .nodeId("retrieve-docs")
                    .agentId("retriever-agent")
                    .mode(PARALLEL)
                    .outputVariable("docs")
                    .build(),
                WorkflowNode.builder()
                    .nodeId("query-floor")
                    .agentId("floor-agent")
                    .mode(PARALLEL)
                    .outputVariable("floorInfo")
                    .build(),
                WorkflowNode.builder()
                    .nodeId("query-equipment")
                    .agentId("equipment-agent")
                    .mode(PARALLEL)
                    .outputVariable("equipmentInfo")
                    .build(),

                // 串行执行：分析风险
                WorkflowNode.builder()
                    .nodeId("analyze-risk")
                    .agentId("analyzer-agent")
                    .mode(SEQUENTIAL)
                    .inputMapping(Map.of(
                        "docs", "${docs}",
                        "floorInfo", "${floorInfo}",
                        "equipmentInfo", "${equipmentInfo}"
                    ))
                    .outputVariable("riskAnalysis")
                    .build(),

                // 串行执行：生成响应
                WorkflowNode.builder()
                    .nodeId("generate-response")
                    .agentId("writer-agent")
                    .mode(SEQUENTIAL)
                    .inputMappping(Map.of(
                        "riskAnalysis", "${riskAnalysis}",
                        "query", "${originalQuery}"
                    ))
                    .outputVariable("finalResponse")
                    .build()
            ))
            .build();

        registry.register(workflow);
    }
}
```

**执行流程**：

```
原始问题: "3楼东区火灾，应该怎么办？"

并行阶段（同时执行，总耗时 = max(单个耗时)）:
├─ retriever-agent: 检索应急预案文档 → docs
├─ floor-agent: 查询3楼东区信息 → floorInfo
└─ equipment-agent: 查询消防设备位置 → equipmentInfo

串行阶段（依赖上一阶段结果）:
├─ analyzer-agent: 分析风险等级 + 推荐逃生路线 → riskAnalysis
└─ writer-agent: 生成最终响应 → finalResponse

最终输出:
"3楼东区发生火灾，风险等级：高。
 建议逃生路线：东侧楼梯 → 1楼大厅 → 室外集合点。
 附近消防设备：灭火器在3楼东区走廊尽头，消防栓在楼梯口。
 应急联系人：张经理 138xxxx1234。"
```

---

### 场景二：RAG 问答工作流

```java
/**
 * RAG 问答工作流定义
 */
@Component
public class RAGQuestionAnswerWorkflow {

    @PostConstruct
    public void register(WorkflowRegistry registry) {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
            .workflowId("rag-qa")
            .name("RAG 问答工作流")
            .nodes(List.of(
                // 串行：检索 → 精排 → 生成
                WorkflowNode.builder()
                    .nodeId("retrieve")
                    .agentId("retriever-agent")
                    .mode(SEQUENTIAL)
                    .outputVariable("rawChunks")
                    .build(),
                WorkflowNode.builder()
                    .nodeId("rerank")
                    .agentId("reranker-agent")
                    .mode(SEQUENTIAL)
                    .inputMapping(Map.of("chunks", "${rawChunks}"))
                    .outputVariable("rankedChunks")
                    .build(),
                WorkflowNode.builder()
                    .nodeId("write")
                    .agentId("writer-agent")
                    .mode(SEQUENTIAL)
                    .inputMapping(Map.of(
                        "chunks", "${rankedChunks}",
                        "query", "${originalQuery}"
                    ))
                    .outputVariable("answer")
                    .build()
            ))
            .build();

        registry.register(workflow);
    }
}
```

---

### 场景三：运维诊断工作流

```java
/**
 * 运维诊断工作流定义
 */
@Component
public class OpsDiagnosisWorkflow {

    @PostConstruct
    public void register(WorkflowRegistry registry) {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
            .workflowId("ops-diagnosis")
            .name("运维诊断工作流")
            .nodes(List.of(
                // 并行：查日志 + 查指标 + 查配置
                WorkflowNode.builder()
                    .nodeId("query-logs")
                    .agentId("log-agent")
                    .mode(PARALLEL)
                    .outputVariable("logs")
                    .build(),
                WorkflowNode.builder()
                    .nodeId("query-metrics")
                    .agentId("metric-agent")
                    .mode(PARALLEL)
                    .outputVariable("metrics")
                    .build(),
                WorkflowNode.builder()
                    .nodeId("query-config")
                    .agentId("config-agent")
                    .mode(PARALLEL)
                    .outputVariable("config")
                    .build(),

                // 串行：诊断根因
                WorkflowNode.builder()
                    .nodeId("diagnose")
                    .agentId("diagnoser-agent")
                    .mode(SEQUENTIAL)
                    .inputMapping(Map.of(
                        "logs", "${logs}",
                        "metrics", "${metrics}",
                        "config", "${config}"
                    ))
                    .outputVariable("diagnosis")
                    .build(),

                // 串行：生成修复方案
                WorkflowNode.builder()
                    .nodeId("suggest-fix")
                    .agentId("fixer-agent")
                    .mode(SEQUENTIAL)
                    .inputMapping(Map.of("diagnosis", "${diagnosis}"))
                    .outputVariable("fixPlan")
                    .build(),

                // 可选：执行修复（需要人工确认）
                WorkflowNode.builder()
                    .nodeId("execute-fix")
                    .agentId("executor-agent")
                    .mode(SEQUENTIAL)
                    .inputMapping(Map.of("fixPlan", "${fixPlan}"))
                    .outputVariable("fixResult")
                    .continueOnError(true)  // 修复失败不影响整体
                    .build()
            ))
            .build();

        registry.register(workflow);
    }
}
```

---

## 平台层 vs 业务层总览

| 模块 | 归属 | 平台层提供 | 业务层实现 |
|------|------|-----------|-----------|
| **AgentRegistry** | 平台+业务 | 注册中心、按能力路由 | 具体 Agent 实现（`CoreAgent`）+ Agent 元数据（`AgentMeta`） |
| **WorkflowEngine** | 平台+业务 | 工作流解析、执行引擎 | 具体工作流定义（`WorkflowDefinition`） |
| **MessageBus** | **纯平台** | 发布-订阅机制、消息存储 | 无需实现 |
| **AgentPool** | **纯平台** | 实例池管理、生命周期 | 无需实现 |
| **ContextPool** | **纯平台** | 共享上下文管理 | 无需实现 |
| **ErrorHandler** | 平台+业务 | 错误处理框架 | 降级策略（可选实现） |

---

## 与单 Agent ReAct 的对比

| 维度 | 单 Agent ReAct | Multi-Agent |
|------|---------------|-------------|
| 复杂度 | 低 | 高 |
| 并行能力 | 无 | 有（WorkflowEngine 支持） |
| 上下文压力 | 高（全部塞一起） | 低（职责隔离） |
| 可控性 | 低 | 高（工作流可视化） |
| 容错能力 | 低（单点故障） | 高（降级 + 重试） |
| 可观测性 | 中（单链路） | 高（多链路追踪） |
| 适用场景 | 简单问答 | 复杂协作 |

---

## 演进路径

```
Phase 1: 单 Agent ReAct（当前）
    - 简单问答场景
    - 工具 < 10 个
    - 延迟要求 < 1s

Phase 2: Plan-and-Execute（过渡）
    - 复杂任务分解
    - 步骤 > 5 个
    - 需要用户确认计划

Phase 3: Multi-Agent（目标）
    - 多职责协作
    - 并行执行需求
    - 容错和降级需求

Phase 4: Agent 编排平台（远期）
    - 可视化工作流编辑器
    - 动态 Agent 注册
    - 多租户 Agent 市场
```

---

## 面试回答模板

**Q: 为什么需要 Multi-Agent？单 Agent ReAct 不够用吗？**

> "单 Agent ReAct 在简单场景够用，但企业级场景有三个问题：串行瓶颈、上下文膨胀、职责混乱。Multi-Agent 通过职责隔离和并行执行解决这些问题。我们的演进路径是：先用 ReAct 跑通业务，再用 Plan-and-Execute 支持复杂任务，最后用 Multi-Agent 实现并行协作。CoreAgent 的 `AgentExecutionStrategy` 接口就是为了支持这个演进。"

**Q: Multi-Agent 如何保证数据一致性？**

> "三个机制：第一，MessageBus 提供消息传递，Agent 之间不直接调用；第二，ContextPool 提供共享上下文，避免重复传递；第三，WorkflowEngine 保证执行顺序，依赖关系明确。"

**Q: 单个 Agent 失败怎么办？**

> "ErrorHandler 提供三级降级：重试 → 降级 Agent → 跳过。非关键节点可以跳过，关键节点有降级 Agent 兜底。"

---

## 总结

Multi-Agent 架构的核心设计原则：

1. **职责隔离**：每个 Agent 专注一个领域，prompt 更简洁
2. **并行执行**：WorkflowEngine 支持节点并行，降低总延迟
3. **容错降级**：ErrorHandler 提供重试、降级、跳过策略
4. **可观测性**：AgentTracer 追踪每个 Agent 的执行过程
5. **平台复用**：继承 CoreAgent 平台层能力，不重复造轮子

新业务场景接入只需：
1. 实现 `CoreAgent` 接口（具体 Agent 逻辑）
2. 定义 `WorkflowDefinition`（Agent 协作关系）
3. 注册到 `AgentRegistry` 和 `WorkflowRegistry`

**不需要改平台层代码**。
