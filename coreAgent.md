# CoreAgent 设计方案

## 背景

面试中需要清晰表达：在 Spring AI 框架之上，我们封装了 CoreAgent 平台层，解决"在业务场景中可靠地调 LLM"的问题。本方案将 CoreAgent 的架构、核心模块、以及两个业务场景的接入方式梳理清楚，作为面试回答的底层支撑。

---

## 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                      业务场景层（接入方）                       │
│                                                              │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐   │
│   │  运维自愈Agent │    │  RAG问答Agent │    │  未来新场景... │   │
│   │  ToolSet: ops │    │  ToolSet: rag │    │  ToolSet: xxx │   │
│   │  Prompt: ops  │    │  Prompt: rag  │    │  Prompt: xxx  │   │
│   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘   │
│          │                   │                   │            │
├──────────┼───────────────────┼───────────────────┼────────────┤
│          ▼                   ▼                   ▼            │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                    CoreAgent 平台层                      │  │
│  │                                                         │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌──────────────────┐  │  │
│  │  │ ToolRegistry│ │ PreProcessor│ │ ContextManager   │  │  │
│  │  │ 工具注册发现  │ │ 返回值预处理  │ │ 上下文窗口管理    │  │  │
│  │  └─────────────┘ └─────────────┘ └──────────────────┘  │  │
│  │                                                         │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌──────────────────┐  │  │
│  │  │ GuardRail   │ │ TenantCtrl  │ │ AgentTracer      │  │  │
│  │  │ 安全护栏     │ │ 租户管控     │ │ 调用链追踪        │  │  │
│  │  └─────────────┘ └─────────────┘ └──────────────────┘  │  │
│  │                                                         │  │
│  │  ┌──────────────────────────────────────────────────┐   │  │
│  │  │              AgentExecutor (ReAct引擎)             │   │  │
│  │  │   Thought → Action → Observation → 循环/终止       │   │  │
│  │  └──────────────────────────────────────────────────┘   │  │
│  └─────────────────────────────────────────────────────────┘  │
│                              │                                │
├──────────────────────────────┼────────────────────────────────┤
│                              ▼                                │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                  Spring AI 框架层                        │  │
│  │   Function Calling │ ChatClient │ Embedding │ Memory    │  │
│  └─────────────────────────────────────────────────────────┘  │
│                              │                                │
├──────────────────────────────┼────────────────────────────────┤
│                              ▼                                │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                   基础设施层                              │  │
│  │   LLM(Qwen本地) │ Qdrant │ ES │ Redis │ Prometheus      │  │
│  └─────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## 核心模块设计

### 1. ToolRegistry — 工具注册中心

**解决的问题**：业务方需要一种标准化的方式来注册工具，而不是直接写 Spring AI 的 Function Bean。

```java
/**
 * 工具定义 — 业务方只需实现这个接口
 */
public interface CoreTool {
    /** 工具名称，对应 Function Calling 的 function name */
    String name();

    /** 工具描述，LLM 靠这个决定是否调用 */
    String description();

    /** 入参 JSON Schema */
    JsonNode inputSchema();

    /** 执行逻辑 */
    ToolResult execute(JsonNode params, ToolContext ctx);
}

/**
 * 注册中心 — 启动时扫描所有 CoreTool 实例，注册到 Spring AI
 */
@Component
public class ToolRegistry {
    private final Map<String, CoreTool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolMeta> metaMap = new ConcurrentHashMap<>();

    public void register(CoreTool tool, ToolMeta meta) {
        tools.put(tool.name(), tool);
        metaMap.put(tool.name(), meta);  // 包含租户可见性、风险等级等元数据
    }

    /** 按场景获取工具集 — 不同业务看到不同的工具 */
    public List<CoreTool> getToolsByScene(String scene) {
        return tools.values().stream()
            .filter(t -> metaMap.get(t.name()).visibleTo(scene))
            .collect(Collectors.toList());
    }
}

/**
 * 工具元数据 — 平台管控维度
 */
@Data
public class ToolMeta {
    private String scene;          // 所属场景: ops / rag / common
    private RiskLevel riskLevel;   // LOW / MEDIUM / HIGH
    private boolean needConfirm;   // 高风险操作是否需要人工确认
    private int timeoutMs;         // 单次调用超时
    private int maxRetry;          // 最大重试次数
    private String tenantScope;    // all / specific tenants
}
```

**设计要点**：
- 业务方只关心 `CoreTool` 接口，不碰 Spring AI 底层
- `ToolMeta` 承载平台管控能力（风险等级、租户可见性、超时配置）
- 按场景隔离工具集，运维 Agent 看不到 RAG 工具，反之亦然

---

### 2. PreProcessor — 工具返回值预处理

**解决的问题**：工具原始返回值（日志、指标、文档）可能有几万 Token，直接塞进 context window 会撑爆或稀释关键信息。

**关键设计**：预处理逻辑跟业务强相关（日志要聚合去重，指标要提取趋势，文档要截断摘要），不可能用一套通用逻辑。所以平台层只定义接口和路由，业务层注册具体实现。

```
平台层（CoreAgent 提供）               业务层（接入方实现）
┌──────────────────────────┐      ┌───────────────────────────┐
│ ResultPreProcessor 接口   │      │ LogPreProcessor           │
│ PreProcessorChain 路由    │──→   │   supports("log_query")   │
│   按 toolName 匹配处理器  │      │   聚合去重、错误模式提取    │
│   无匹配时透传原始结果    │      ├───────────────────────────┤
│                          │      │ MetricPreProcessor        │
│                          │      │   supports("metric_query") │
│                          │      │   趋势提取、异常标注        │
│                          │      ├───────────────────────────┤
│                          │      │ DocPreProcessor           │
│                          │      │   supports("retrieve")     │
│                          │      │   截断摘要、引用提取        │
└──────────────────────────┘      └───────────────────────────┘
```

#### 平台层 — 接口定义 + 路由

```java
/**
 * 预处理器接口 — 业务方实现此接口
 */
public interface ResultPreProcessor {
    /** 是否能处理该工具的返回值 */
    boolean supports(String toolName);

    /** 预处理：压缩、聚合、提取关键信息 */
    String process(ToolResult raw, PreProcessContext ctx);
}

/**
 * 预处理链 — 平台提供的路由机制
 * 按 toolName 匹配到对应处理器，无匹配时透传原始结果
 */
@Component
public class PreProcessorChain {
    private final List<ResultPreProcessor> processors;

    public PreProcessorChain(List<ResultPreProcessor> processors) {
        this.processors = processors;
    }

    public String process(String toolName, ToolResult raw, PreProcessContext ctx) {
        return processors.stream()
            .filter(p -> p.supports(toolName))
            .findFirst()
            .map(p -> p.process(raw, ctx))
            .orElse(raw.getData());  // 无处理器 → 透传，不阻塞流程
    }
}
```

#### 业务层 — 具体实现（示例）

```java
/**
 * 日志预处理器 — 运维场景业务实现
 * 聚合去重，提取错误模式，原始 5000 行 → ~20 行摘要
 */
@Component
public class LogPreProcessor implements ResultPreProcessor {
    @Override
    public boolean supports(String toolName) {
        return "log_query".equals(toolName);
    }

    @Override
    public String process(ToolResult raw, PreProcessContext ctx) {
        List<LogLine> lines = parseLogs(raw.getData());
        Map<String, List<LogLine>> grouped = lines.stream()
            .collect(Collectors.groupingBy(LogLine::errorPattern));

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(lines.size()).append(" 条日志，")
          .append(grouped.size()).append(" 种错误模式:\n\n");

        grouped.forEach((pattern, group) -> {
            sb.append("【").append(group.size()).append("次】")
              .append(pattern).append("\n")
              .append("  最近: ").append(group.get(group.size()-1).getMessage())
              .append("\n\n");
        });
        return sb.toString();
    }
}

/**
 * 指标预处理器 — 运维场景业务实现
 * 提取当前值、5分钟趋势、异常标注
 */
@Component
public class MetricPreProcessor implements ResultPreProcessor {
    @Override
    public boolean supports(String toolName) {
        return "metric_query".equals(toolName);
    }

    @Override
    public String process(ToolResult raw, PreProcessContext ctx) {
        MetricData data = parseMetrics(raw.getData());
        return String.format(
            "指标: %s\n当前值: %s\n5min趋势: %s (%.1f%%)\n状态: %s",
            data.getName(), data.getCurrentValue(),
            data.getTrend(), data.getTrendPercent(),
            data.isAlerting() ? "⚠ 异常" : "正常");
    }
}
```

**总结**：
- **平台层做什么**：定义 `ResultPreProcessor` 接口 + `PreProcessorChain` 路由机制
- **业务层做什么**：实现具体的预处理逻辑（日志聚合、指标趋势、文档截断）
- **扩展方式**：新业务场景只需实现接口 + `@Component` 注入，平台层自动路由

---

### 3. ContextManager — 上下文窗口管理

**解决的问题**：多轮推理过程中 context window 会持续增长，需要控制总量并决定哪些信息保留、哪些丢弃。

**关键设计**：Token 计数和裁剪机制是通用的（平台层），但"什么优先保留"不同场景策略不同（业务层）。

- RAG 场景：检索到的文档片段需要保留更久（回答要引用来源）
- 运维场景：最近几轮日志摘要比旧的 Thought 更重要
- 多轮对话：用户原始问题始终保留，中间推理可以压缩

```
平台层                              业务层
┌─────────────────────────┐     ┌──────────────────────────┐
│ ContextManager          │     │ ContextStrategy 接口      │
│ - Token 计数             │     │ - budgetRatio() 预算比例  │
│ - 预算分配框架            │←──  │ - priority() 内容优先级   │
│ - 溢出裁剪机制           │     │ - trimPolicy() 裁剪策略   │
│                         │     │                          │
│ 提供默认实现 DefaultCtx  │     │ 运维: 日志>Thought>文档    │
│ 业务可覆盖               │     │ RAG:  文档>Thought>日志    │
└─────────────────────────┘     └──────────────────────────┘
```

#### 平台层 — ContextManager + 默认策略

```java
/**
 * 内容优先级策略 — 业务方实现此接口决定裁剪顺序
 */
public interface ContextStrategy {
    /** Token 预算分配比例 [system, toolResults, thoughts] */
    default BudgetRatio budgetRatio() {
        return new BudgetRatio(0.2, 0.5, 0.3);
    }

    /** 内容优先级：数字越小越优先保留 */
    int priority(MessageBlock block);

    /** 裁剪策略：超预算时按优先级从低到高裁剪 */
    default List<MessageBlock> trim(List<MessageBlock> blocks, int overBudget) {
        return blocks.stream()
            .sorted(Comparator.comparingInt(this::priority).reversed())
            .collect(Collectors.toList());
    }
}

/**
 * 上下文管理器 — 平台通用，使用业务方提供的策略
 */
@Component
public class ContextManager {
    private final int maxTokens;  // 如 4096

    public ChatMessage buildContext(AgentSession session, ContextStrategy strategy) {
        List<MessageBlock> blocks = collectBlocks(session);
        int totalTokens = countTokens(blocks);

        if (totalTokens > maxTokens) {
            blocks = strategy.trim(blocks, totalTokens - maxTokens);
        }
        return toChatMessage(blocks);
    }

    private List<MessageBlock> collectBlocks(AgentSession session) {
        List<MessageBlock> blocks = new ArrayList<>();
        blocks.add(MessageBlock.fixed(session.getSystemPrompt()));
        for (ToolCallRecord r : session.getToolCalls()) {
            blocks.add(MessageBlock.toolSummary(r.getToolName(), r.getPreprocessedResult()));
        }
        for (ThoughtStep step : session.getThoughts()) {
            blocks.add(MessageBlock.thought(step.getContent()));
        }
        return blocks;
    }
}
```

#### 业务层 — 不同场景的策略实现

```java
/**
 * 运维场景策略 — 工具结果（日志/指标）优先保留
 * 原因：运维诊断依赖最新的日志和指标，推理历史可以压缩
 */
@Component
public class OpsContextStrategy implements ContextStrategy {
    @Override
    public int priority(MessageBlock block) {
        return switch (block.getType()) {
            case SYSTEM_PROMPT  -> 0;  // 最高优先，永不裁剪
            case TOOL_RESULT    -> 1;  // 日志/指标是诊断关键
            case USER_QUESTION  -> 2;  // 用户原始问题
            case THOUGHT        -> 3;  // 推理历史可压缩
        };
    }
}

/**
 * RAG 场景策略 — 检索文档优先保留
 * 原因：回答需要引用文档来源，文档片段丢掉就无法溯源
 */
@Component
public class RAGContextStrategy implements ContextStrategy {
    @Override
    public int priority(MessageBlock block) {
        return switch (block.getType()) {
            case SYSTEM_PROMPT  -> 0;
            case USER_QUESTION  -> 1;  // 用户问题优先
            case TOOL_RESULT    -> 2;  // 检索文档保留（需要引用）
            case THOUGHT        -> 3;  // 推理过程可压缩
        };
    }
}
```

**总结**：
- **平台层做什么**：Token 计数、预算分配框架、溢出裁剪机制
- **业务层做什么**：决定什么优先保留（`ContextStrategy`）
- **扩展方式**：新业务场景实现 `ContextStrategy` + `@Component` 注入

---

### 4. GuardRail — 安全护栏

**解决的问题**：LLM 可能做出危险决策（重启服务、删数据），需要在执行前拦截。

**关键设计**：不同业务场景的风险判定差异很大——运维场景"重启服务"是 HIGH，RAG 场景根本没有 HIGH 风险操作。所以平台层提供检查框架 + 频率限制 + 审批服务，业务层定义风险判定规则。

```
平台层（CoreAgent 提供）               业务层（接入方实现）
┌──────────────────────────┐      ┌───────────────────────────┐
│ GuardRail 检查框架        │      │ RiskRule 接口             │
│ - 执行前拦截机制          │      │ - 风险等级判定逻辑         │
│ - 频率限制器（Redis）     │      │ - 自定义拦截规则           │
│ - 人工确认服务            │←──   │                           │
│ - 审计日志记录            │      │ 运维: 重启/配置变更=HIGH   │
│                          │      │ RAG:  无HIGH操作,全部LOW   │
│ ToolMeta.riskLevel 基础   │      │ 未来: 可扩展自定义规则     │
└──────────────────────────┘      └───────────────────────────┘
```

#### 平台层 — 检查框架 + 基础设施

```java
/**
 * 风险判定规则 — 业务方实现此接口
 */
public interface RiskRule {
    /** 该规则是否适用于当前工具 */
    boolean supports(String toolName);

    /** 判定风险等级（可覆盖 ToolMeta 中的静态配置） */
    RiskLevel evaluate(CoreTool tool, JsonNode params, ToolContext ctx);

    /** 是否需要人工确认（可基于上下文动态判断） */
    default boolean needConfirm(CoreTool tool, JsonNode params, ToolContext ctx) {
        return evaluate(tool, params, ctx).ordinal() >= RiskLevel.HIGH.ordinal();
    }
}

/**
 * 安全护栏 — 平台提供的检查框架
 * 执行顺序：频率限制 → 风险判定 → 人工确认 → 审计记录
 */
@Component
public class GuardRail {
    private final List<RiskRule> riskRules;
    private final RateLimiter rateLimiter;
    private final HumanConfirmService confirmService;
    private final AuditLogger auditLogger;

    public GuardResult checkBeforeExecute(CoreTool tool, JsonNode params,
                                           ToolContext ctx) {
        ToolMeta meta = ctx.getToolMeta();

        // 1. 频率限制 — 平台通用（同一工具短时间内不可重复调用）
        if (!rateLimiter.tryAcquire(tool.name(), ctx.getTenantId())) {
            return GuardResult.blocked("工具调用频率超限");
        }

        // 2. 风险判定 — 优先使用业务规则，降级到 ToolMeta 静态配置
        RiskLevel level = riskRules.stream()
            .filter(r -> r.supports(tool.name()))
            .findFirst()
            .map(r -> r.evaluate(tool, params, ctx))
            .orElse(meta.getRiskLevel());

        // 3. CRITICAL → 直接拦截，不开放给 Agent
        if (level == RiskLevel.CRITICAL) {
            auditLogger.logBlocked(tool, params, ctx, "CRITICAL 操作不开放");
            return GuardResult.blocked("该操作不开放给 Agent 自主决策");
        }

        // 4. HIGH → 人工确认
        if (level == RiskLevel.HIGH) {
            boolean approved = confirmService.requestConfirm(
                tool.name(), params.toString(), ctx.getTenantId());
            if (!approved) {
                return GuardResult.blocked("操作被人工拒绝");
            }
        }

        // 5. 审计日志 — MEDIUM 及以上都记录
        if (level.ordinal() >= RiskLevel.MEDIUM.ordinal()) {
            auditLogger.log(tool, params, ctx, level);
        }

        return GuardResult.allowed();
    }
}
```

#### 业务层 — 风险规则实现

```java
/**
 * 运维场景风险规则
 * 重启、配置变更、扩容等操作定义为 HIGH
 */
@Component
public class OpsRiskRule implements RiskRule {
    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
        "service_restart", "config_change", "scale_up", "scale_down"
    );

    @Override
    public boolean supports(String toolName) {
        return HIGH_RISK_TOOLS.contains(toolName);
    }

    @Override
    public RiskLevel evaluate(CoreTool tool, JsonNode params, ToolContext ctx) {
        // 生产环境 + 重启操作 → HIGH；测试环境 → MEDIUM
        if (isProduction(ctx) && "service_restart".equals(tool.name())) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.MEDIUM;
    }
}

/**
 * RAG 场景风险规则
 * 检索和问答全部是 LOW，无高风险操作
 */
@Component
public class RAGRiskRule implements RiskRule {
    @Override
    public boolean supports(String toolName) {
        // RAG 场景所有工具都是查询类，无风险
        return "dense_retrieve".equals(toolName)
            || "sparse_retrieve".equals(toolName)
            || "rerank".equals(toolName);
    }

    @Override
    public RiskLevel evaluate(CoreTool tool, JsonNode params, ToolContext ctx) {
        return RiskLevel.LOW;  // 检索操作无风险
    }
}
```

**安全策略分级**：

| 风险等级 | 示例操作 | 平台策略 |
|---------|---------|---------|
| LOW | 查询日志、检索知识 | 直接执行 |
| MEDIUM | 查询指标、发送通知 | 执行 + 审计日志 |
| HIGH | 修改配置、重启服务 | 人工确认后执行 |
| CRITICAL | 删除数据、权限变更 | 不开放给 Agent |

**总结**：
- **平台层做什么**：检查框架、频率限制器、人工确认服务、审计日志
- **业务层做什么**：风险判定规则（`RiskRule`），定义哪些操作是 HIGH/CRITICAL
- **扩展方式**：新业务场景实现 `RiskRule` + `@Component` 注入

---

### 5. TenantCtrl — 租户管控（纯平台层）

**解决的问题**：多租户环境下，防止某个租户的 Agent 调用耗尽共享资源。

**不需要拆分的原因**：Token 配额、QPS 限流、成本追踪是平台基础设施，跟业务场景无关。不同租户的配额差异通过配置解决，不是业务逻辑。

```java
/**
 * 租户管控 — 平台通用组件
 * 配额值从数据库/配置中心读取，按租户套餐区分
 */
@Component
public class TenantCtrl {
    private final RedisTemplate<String, String> redis;
    private final TenantQuotaConfig quotaConfig;  // 租户配额配置

    /**
     * 调用前检查 — Token 配额 + QPS 限流
     */
    public TenantCheckResult check(String tenantId) {
        // 1. Token 配额（月度）
        long usedTokens = getMonthlyTokenUsage(tenantId);
        long quota = quotaConfig.getQuota(tenantId);  // 按租户套餐读取
        if (usedTokens >= quota) {
            return TenantCheckResult.exceeded("Token 配额已用完");
        }

        // 2. QPS 限流（令牌桶）
        if (!tryAcquireToken("agent:qps:" + tenantId)) {
            return TenantCheckResult.exceeded("QPS 超限");
        }

        return TenantCheckResult.allowed();
    }

    /**
     * 调用后记录 — 成本追踪
     */
    public void recordUsage(String tenantId, AgentCallRecord record) {
        metrics.record("agent_call", Map.of(
            "tenant", tenantId,
            "tokens", record.getTotalTokens(),
            "latency_ms", record.getLatencyMs(),
            "tool_calls", record.getToolCallCount()
        ));
    }
}
```

---

### 6. AgentTracer — 调用链追踪（纯平台层）

**解决的问题**：Agent 推理是多步的，出问题时需要知道"LLM 在第几步做了什么决策"。

**不需要拆分的原因**：Trace 数据模型和 Prometheus 接入是通用的可观测性基础设施，跟业务场景无关。

```java
/**
 * 追踪结构 — 平台通用
 */
@Data
public class AgentTrace {
    private String traceId;
    private String tenantId;
    private String scene;            // ops / rag（用于 Prometheus label 区分）
    private long startTime;
    private long endTime;

    private List<TraceStep> steps;
    private int totalTokens;
    private int totalToolCalls;
    private String finalAnswer;
    private boolean hitCache;
}

@Data
public class TraceStep {
    private int stepNo;
    private String type;             // THOUGHT / ACTION / OBSERVATION
    private String thought;
    private String toolName;
    private String toolInput;
    private String toolOutput;       // 预处理后
    private long latencyMs;
    private int tokensUsed;
}
```

**接入 Prometheus + Grafana**：
- `agent_call_total{tenant, scene, status}` — 调用总量
- `agent_latency_seconds{tenant, scene, quantile}` — 延迟分位数
- `agent_token_usage_total{tenant}` — Token 消耗
- `agent_tool_call_total{tool_name}` — 各工具调用频次

---

### 7. AgentExecutor — ReAct 推理引擎

**核心执行流程**，串联上述所有模块：

```java
@Component
public class AgentExecutor {
    private final ToolRegistry toolRegistry;
    private final PreProcessorChain preProcessor;
    private final ContextManager contextManager;
    private final Map<String, ContextStrategy> contextStrategies;  // 按场景注入
    private final GuardRail guardRail;
    private final TenantCtrl tenantCtrl;
    private final AgentTracer tracer;
    private final ChatClient chatClient;  // Spring AI

    public AgentResponse execute(AgentRequest request) {
        // 0. 租户检查
        TenantCheckResult check = tenantCtrl.check(request.getTenantId());
        if (!check.isAllowed()) return AgentResponse.quotaExceeded(check);

        // 1. 初始化追踪
        AgentTrace trace = tracer.startTrace(request);

        // 2. 获取场景工具集 + 上下文策略
        List<CoreTool> tools = toolRegistry.getToolsByScene(request.getScene());
        ContextStrategy ctxStrategy = contextStrategies.getOrDefault(
            request.getScene(), new DefaultContextStrategy());

        // 3. ReAct 循环
        AgentSession session = new AgentSession(request, tools);
        while (!session.isFinished() && session.getStepCount() < maxSteps) {

            // 3a. 构建上下文（使用场景对应的策略）
            ChatMessage context = contextManager.buildContext(session, ctxStrategy);

            // 3b. 调用 LLM → Thought + Action
            ChatResponse llmResponse = chatClient.prompt()
                .system(session.getSystemPrompt())
                .user(context.getContent())
                .functions(tools.stream().map(CoreTool::name).toList())
                .call();

            ThoughtAction ta = parseThoughtAction(llmResponse);

            trace.recordStep(session.getStepCount(), "THOUGHT", ta.getThought());

            if (ta.hasAction()) {
                // 3c. 安全检查
                CoreTool tool = toolRegistry.get(ta.getActionTool());
                GuardResult guard = guardRail.checkBeforeExecute(
                    tool, ta.getActionParams(), session.getContext());

                if (!guard.isAllowed()) {
                    trace.recordStep(session.getStepCount(), "BLOCKED",
                        guard.getReason());
                    session.addObservation("操作被安全策略拦截: " + guard.getReason());
                    continue;
                }

                // 3d. 执行工具
                ToolResult raw = tool.execute(ta.getActionParams(),
                    session.getContext());

                // 3e. 预处理返回值
                String processed = preProcessor.process(tool.name(), raw);

                trace.recordStep(session.getStepCount(), "ACTION",
                    tool.name(), processed);

                // 3f. 写入 Observation
                session.addObservation(processed);
            } else {
                // 无 Action → LLM 认为推理完成，输出最终答案
                session.setFinalAnswer(ta.getThought());
                session.setFinished(true);
            }
        }

        // 4. 收尾
        tracer.endTrace(trace);
        tenantCtrl.recordUsage(request.getTenantId(), session.toRecord());

        return AgentResponse.of(session.getFinalAnswer(), trace);
    }
}
```

---

## 业务场景接入示例

### 场景一：运维自愈 Agent

```java
// 注册运维工具集
@Component
public class OpsToolSet {
    @PostConstruct
    public void register(ToolRegistry registry) {
        registry.register(new LogQueryTool(),
            ToolMeta.builder().scene("ops").riskLevel(LOW).timeoutMs(5000).build());
        registry.register(new MetricQueryTool(),
            ToolMeta.builder().scene("ops").riskLevel(LOW).timeoutMs(3000).build());
        registry.register(new ServiceRestartTool(),
            ToolMeta.builder().scene("ops").riskLevel(HIGH).needConfirm(true).build());
        registry.register(new ConfigChangeTool(),
            ToolMeta.builder().scene("ops").riskLevel(HIGH).needConfirm(true).build());
    }
}

// System Prompt（运维场景专用）
String OPS_PROMPT = """
    你是一个运维诊断助手。你的职责是分析告警信息，定位根因，给出修复建议。
    规则：
    1. 先查日志和指标，再做判断，不要猜测
    2. 高风险操作（重启、配置变更）必须说明理由并等待确认
    3. 最终输出格式：根因分析 → 修复方案 → 预期效果
    """;
```

### 场景二：RAG 问答 Agent

```java
@Component
public class RAGToolSet {
    @PostConstruct
    public void register(ToolRegistry registry) {
        registry.register(new DenseRetrievalTool(),   // Qdrant 向量检索
            ToolMeta.builder().scene("rag").riskLevel(LOW).timeoutMs(1000).build());
        registry.register(new SparseRetrievalTool(),  // ES BM25 检索
            ToolMeta.builder().scene("rag").riskLevel(LOW).timeoutMs(1000).build());
        registry.register(new RerankTool(),           // 精排
            ToolMeta.builder().scene("rag").riskLevel(LOW).timeoutMs(500).build());
    }
}

String RAG_PROMPT = """
    你是一个安全知识问答助手。根据检索到的文档回答用户问题。
    规则：
    1. 只基于检索到的文档回答，不要编造
    2. 引用文档时标注来源
    3. 如果检索结果不足以回答，明确说明"未找到相关文档"
    """;
```

### 场景三：运营数据查询 Agent

运营人员用自然语言查询 SaaS 客户的业务数据，不需要写 SQL、不需要登录多个系统。

```java
@Component
public class AnalyticsToolSet {
    @PostConstruct
    public void register(ToolRegistry registry) {
        registry.register(new VisitorQueryTool(),      // 到访人数查询
            ToolMeta.builder().scene("analytics").riskLevel(LOW).timeoutMs(3000).build());
        registry.register(new TokenUsageQueryTool(),   // Token 用量查询
            ToolMeta.builder().scene("analytics").riskLevel(LOW).timeoutMs(2000).build());
        registry.register(new DataUsageQueryTool(),    // 数据用量查询
            ToolMeta.builder().scene("analytics").riskLevel(LOW).timeoutMs(2000).build());
        registry.register(new AlertStatsTool(),        // 告警统计
            ToolMeta.builder().scene("analytics").riskLevel(LOW).timeoutMs(2000).build());
        registry.register(new ReportExportTool(),      // 报表导出
            ToolMeta.builder().scene("analytics").riskLevel(MEDIUM).timeoutMs(10000).build());
    }
}

String ANALYTICS_PROMPT = """
    你是一个运营数据查询助手。运营人员会用自然语言问你业务数据，你需要调用工具查询并用清晰的格式回答。
    规则：
    1. 只查询，不修改任何数据
    2. 回答要有数字、有对比、有趋势（如"今日到访 1200 人，较昨日 +15%"）
    3. 如果查询结果为空，明确说"该时间段无数据"
    4. 支持追问（如"那上周呢"、"按租户分呢"）
    """;
```

**典型交互**：
```
运营: "今天到访人数多少？"
Agent: Thought → 调用 VisitorQueryTool(today) → Observation: 1200人
Agent: "今日到访 1200 人，较昨日 +15%，较上周同期 +8%。"

运营: "哪个租户贡献最多？"
Agent: Thought → 调用 VisitorQueryTool(today, groupBy=tenant) → Observation: {A: 450, B: 320...}
Agent: "Top 3 租户：A园区 450人(37.5%)、B工厂 320人(26.7%)、C学校 210人(17.5%)。"
```

**接入 CoreAgent 只需**：
1. 实现 `CoreTool` 接口（5 个查询工具）
2. 实现 `ResultPreProcessor`（数字格式化 + 趋势对比）
3. 实现 `ContextStrategy`（保留最近查询结果，支持追问）
4. 实现 `RiskRule`（全部 LOW，只读查询）
5. 注册 Prompt

**不需要改平台层代码**。

---

## 与流程引擎的对比

| 维度 | 流程引擎 | CoreAgent |
|------|---------|-----------|
| 任务确定性 | 确定性流程（DAG） | 非确定性推理（LLM 决策） |
| 调度方式 | 分片调度器，实例级串行 | ReAct 循环，步骤级串行 |
| 节点执行 | 业务 Handler | LLM + Function Calling |
| 状态持久化 | 主键更新 + 乐观锁 | AgentTrace + 调用链日志 |
| 租户隔离 | 五层隔离（限流→分片→分层→配额→熔断） | Token 配额 + QPS 限流 + 工具可见性 |
| 容错 | 超时告警 + 人工介入 | 安全护栏 + 人工确认 + 重试上限 |

**共同点**：本质都是**任务调度与编排**，只是流程引擎编排的是确定性节点，CoreAgent 编排的是 LLM 推理步骤。

---

## 平台层 vs 业务层总览

| 模块 | 归属 | 平台层提供 | 业务层实现 |
|------|------|-----------|-----------|
| **ToolRegistry** | 平台+业务 | 注册中心、按场景路由 | 具体工具实现（`CoreTool`）+ 工具元数据（`ToolMeta`） |
| **PreProcessor** | 平台+业务 | 接口定义、路由链（`PreProcessorChain`） | 具体预处理逻辑（日志聚合、指标趋势、文档截断） |
| **ContextManager** | 平台+业务 | Token 计数、预算分配框架、裁剪机制 | 优先级策略（`ContextStrategy`）：什么内容优先保留 |
| **GuardRail** | 平台+业务 | 检查框架、频率限制、人工确认服务、审计日志 | 风险判定规则（`RiskRule`）：哪些操作是 HIGH/CRITICAL |
| **TenantCtrl** | **纯平台** | Token 配额、QPS 限流、成本追踪 | 无需实现（配额差异通过配置解决） |
| **AgentTracer** | **纯平台** | Trace 数据模型、Prometheus 接入 | 无需实现（scene label 区分业务） |
| **AgentExecutor** | **纯平台** | ReAct 推理引擎，串联所有模块 | 无需实现 |

**核心原则**：平台层解决"怎么调 LLM"，业务层解决"用什么工具、什么策略、什么风险等级"。新业务场景接入只需实现 4 个接口：`CoreTool`、`ResultPreProcessor`、`ContextStrategy`、`RiskRule`。

---

## 面试回答要点

1. **定位清晰**：不是造框架，是在 Spring AI 之上做业务集成层
2. **复用与隔离**：7 个模块中 4 个需要业务实现接口，3 个纯平台复用
3. **扩展模式**：新业务场景实现 4 个接口（`CoreTool`、`ResultPreProcessor`、`ContextStrategy`、`RiskRule`）+ `@Component` 注入即可
4. **安全优先**：分级管控，高风险操作人工确认，LLM 不碰"改数据"
5. **可观测**：每步推理都有 Trace，接入 Prometheus，成本可追踪
6. **与流程引擎呼应**：本质都是调度编排，只是从确定性 DAG 到非确定性 LLM

---

## 优化点：AgentExecutor 可插拔执行策略

当前 AgentExecutor 绑定 ReAct 模式，但不同业务场景适合不同推理模式。优化方向：将 AgentExecutor 改为策略路由器，支持多种执行策略。

| 推理模式 | 适用场景 | 特点 |
|---------|---------|------|
| **ReAct** | 运维诊断、RAG 问答 | 线性推理，逐步探索，适合"不知道下一步该干嘛" |
| **Plan-and-Execute** | 复杂任务分解 | 先规划再执行，适合"任务明确但步骤多" |
| **Multi-Agent** | Retriever→Reranker→Writer | 多 Agent 串行/并行，适合"子任务职责差异大" |

优化后 AgentExecutor 变为路由器，通过 `AgentExecutionStrategy` 接口支持可插拔策略，业务方按场景选择推理模式。
