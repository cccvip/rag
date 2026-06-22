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
│   │  Prompt: ops  │    │  Prompt: rag  │    │  Prompt: xxx  │   │
│   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘   │
│          │                   │                   │            │
├──────────┼───────────────────┼───────────────────┼────────────┤
│          ▼                   ▼                   ▼            │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                    CoreAgent 平台层                      │  │
│  │                                                         │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌──────────────────┐  │  │
│  │  │ MCP Gateway │ │ PreProcessor│ │ ContextManager   │  │  │
│  │  │ 工具注册发现  │ │ 返回值预处理  │ │ 上下文窗口管理    │  │  │
│  │  │ 协议转换     │ │             │ │                  │  │  │
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

### 1. MCP Gateway — 工具注册与协议转换

**解决的问题**：业务服务（运维、RAG、运营）只需要暴露普通 REST 接口，由 MCP Gateway 统一负责 MCP 协议转换、工具注册、租户隔离。业务服务零侵入。

**设计原则**：一个服务集成 MCP Gateway + CoreAgent，内部调用，延迟低，部署简单。

```
┌─────────────────────────────────────────────────────────────┐
│                      CoreAgent 服务（集成 MCP Gateway）       │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   MCP Gateway 模块                     │  │
│  │                                                       │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐ │  │
│  │  │ ToolRegistry│ │ ProtocolConv│ │ TenantIsolation │ │  │
│  │  │ 工具注册表   │ │ 协议转换器   │ │ 租户隔离过滤    │ │  │
│  │  └─────────────┘ └─────────────┘ └─────────────────┘ │  │
│  │                                                       │  │
│  │  对外暴露：/mcp/tools/list、/mcp/tools/call           │  │
│  └───────────────────────────────────────────────────────┘  │
│                              │                               │
│                              ▼                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   CoreAgent 模块                       │  │
│  │  AgentExecutor │ PreProcessor │ ContextManager │ ...   │  │
│  └───────────────────────────────────────────────────────┘  │
│                              │                               │
└──────────────────────────────┼───────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 业务服务 A   │    │ 业务服务 B   │    │ 业务服务 C   │
│ 运维服务     │    │ RAG 服务     │    │ 运营服务     │
│ 普通 REST    │    │ 普通 REST    │    │ 普通 REST    │
│ /api/log     │    │ /api/retrieve│    │ /api/visitor │
└──────────────┘    └──────────────┘    └──────────────┘
业务服务零侵入，只关心业务逻辑
```

**MCP Gateway 核心职责**：

| 职责 | 说明 |
|------|------|
| **工具注册** | 从配置加载工具定义，从 Nacos 解析服务地址 |
| **协议转换** | MCP 协议 ↔ 后端 REST 协议 |
| **租户隔离** | 按租户过滤工具列表，校验调用权限 |
| **路由转发** | 将 MCP 工具调用转发到对应的后端服务 |

**设计要点**：
- 业务服务零侵入，只暴露普通 REST 接口
- MCP Gateway 统一负责协议转换、工具注册、租户隔离
- 工具配置化，新增工具只需修改配置，不改代码
- 服务地址从 Nacos 动态解析

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

### 4.1 GuardRail 场景设计（应急安全 SaaS）

#### 场景 1：多租户数据隔离 GuardRail

**问题**：LLM 可能在回答中泄露 A 租户的数据给 B 租户

```java
/**
 * 租户隔离校验器 — RAG 场景专用
 * 确保检索结果和 LLM 输出不包含其他租户数据
 */
@Component
public class TenantIsolationGuardRail {

    public GuardResult check(String query, String response,
                             String tenantId, List<RetrievedChunk> chunks) {
        // 1. 检索结果二次校验
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.getTenantId().equals(tenantId)) {
                return GuardResult.blocked("数据隔离异常：检索到其他租户数据");
            }
        }

        // 2. LLM 输出校验 — 检查是否包含其他租户标识
        List<String> otherTenantIds = tenantService.getAllTenantIds();
        otherTenantIds.remove(tenantId);
        for (String otherId : otherTenantIds) {
            if (response.contains(otherId)) {
                return GuardResult.blocked("响应包含其他租户信息");
            }
        }

        // 3. 结构化数据校验 — 楼层/区域是否属于当前租户
        List<Entity> entities = entityExtractor.extract(response);
        for (Entity entity : entities) {
            if (!assetService.verifyOwnership(entity, tenantId)) {
                return GuardResult.blocked("资产归属异常");
            }
        }

        return GuardResult.allowed();
    }
}
```

**关键点**：LLM 输出后，必须校验返回内容是否属于当前租户。

---

#### 场景 2：应急信息准确性 GuardRail

**问题**：逃生通道、消防设备位置等关键信息不能出错

```java
/**
 * 应急信息校验器 — 关键信息走结构化库兜底
 * 不依赖 RAG 检索，直接查 MySQL 空间资产库
 */
@Component
public class EmergencyInfoGuardRail {

    // 关键信息白名单 — 必须从结构化库查，不走 RAG
    private static final Set<String> CRITICAL_FIELDS = Set.of(
        "逃生通道位置", "消防设备编号", "应急联系人", "集合点坐标"
    );

    public GuardResult check(String query, String response, String tenantId) {
        // 1. 判断是否涉及关键信息
        if (!isCriticalQuery(query)) {
            return GuardResult.allowed();
        }

        // 2. 提取响应中的结构化信息
        List<Entity> entities = entityExtractor.extract(response);

        // 3. 与空间资产库交叉验证
        for (Entity entity : entities) {
            String dbValue = assetService.queryAsset(tenantId, entity.getType());
            if (!entity.getValue().equals(dbValue)) {
                return GuardResult.blocked(
                    entity.getType() + " 信息不一致，已触发人工复核");
            }
        }

        return GuardResult.allowed();
    }

    private boolean isCriticalQuery(String query) {
        return CRITICAL_FIELDS.stream().anyMatch(query::contains);
    }
}
```

**设计原则**：代码比模型训练便宜一万倍，但效果更稳。

---

#### 场景 3：LLM 调用成本 GuardRail

**问题**：被攻击者刷爆 LLM 调用费用（已遇到过）

```java
/**
 * 成本护栏 — 限流 + 缓存穿透检测 + 配额
 */
@Component
public class CostGuardRail {

    private final RateLimiter rateLimiter;
    private final BloomFilter<String> bloomFilter;
    private final TenantQuotaService quotaService;
    private final QueryCache queryCache;

    public CostCheckResult check(String tenantId, String query) {
        // 1. 租户级 QPS 限流
        if (!rateLimiter.tryAcquire("agent:qps:" + tenantId, 20)) {
            return CostCheckResult.reject("请求过于频繁");
        }

        // 2. 缓存穿透检测 — 布隆过滤器
        if (!bloomFilter.mightContain(query)) {
            return CostCheckResult.reject("异常查询模式");
        }

        // 3. Token 配额检查
        long monthlyTokens = quotaService.getMonthlyUsage(tenantId);
        long quota = quotaService.getQuota(tenantId);
        if (monthlyTokens >= quota) {
            return CostCheckResult.degrade("配额超限，降级到缓存模式");
        }

        // 4. 相同 query 短时间重复检测
        if (queryCache.exists(tenantId, query)) {
            return CostCheckResult.cacheHit("命中缓存");
        }

        return CostCheckResult.allow();
    }
}
```

**已有成果**：QPS 限流 + Redis 缓存 + Token 配额 + 布隆过滤器，整体 LLM 调用成本降 60%。

---

#### 场景 4：检索质量 GuardRail

**问题**：新租户冷启动，检索质量差

```java
/**
 * 检索质量校验器 — 冷启动保护 + 相似度阈值
 */
@Component
public class RetrievalQualityGuardRail {

    private static final double MIN_SCORE_THRESHOLD = 0.5;
    private static final int COLD_START_DOC_COUNT = 100;

    public RetrievalCheckResult check(String query, List<RetrievedChunk> chunks,
                                       String tenantId) {
        // 1. 相似度阈值检查
        double maxScore = chunks.stream()
            .mapToDouble(RetrievedChunk::getScore)
            .max()
            .orElse(0.0);

        if (maxScore < MIN_SCORE_THRESHOLD) {
            return RetrievalCheckResult.reject("检索质量不足，触发降级策略");
        }

        // 2. 租户文档量检查 — 冷启动保护
        int docCount = docService.getTenantDocCount(tenantId);
        if (docCount < COLD_START_DOC_COUNT) {
            return RetrievalCheckResult.adjust("冷启动期，启用 BM25 优先策略");
        }

        // 3. 结果一致性检查 — 多个 chunk 是否矛盾
        if (hasContradiction(chunks)) {
            return RetrievalCheckResult.warn("检索结果存在矛盾，建议人工复核");
        }

        return RetrievalCheckResult.allow();
    }
}
```

**已有成果**：冷启动保护期 7 天，新租户首周 Faithfulness 从 0.55 提到 0.82。

---

#### 场景 5：缓存一致性 GuardRail

**问题**：文档更新后，缓存可能返回旧数据

```java
/**
 * 缓存一致性校验器 — 文档版本 + TTL
 */
@Component
public class CacheConsistencyGuardRail {

    private static final long EMERGENCY_TTL = 3600;      // 应急类 1 小时
    private static final long NORMAL_TTL = 86400;        // 常规类 24 小时

    public CacheCheckResult check(String query, CachedResponse cached,
                                   String tenantId) {
        // 1. 检查缓存关联的文档版本
        List<String> cachedVersions = cached.getMetadata().getDocVersions();
        List<String> currentVersions = docService.getCurrentVersions(tenantId);

        if (!cachedVersions.equals(currentVersions)) {
            return CacheCheckResult.invalidate("文档已更新，缓存失效");
        }

        // 2. 检查 TTL — 应急类短，常规类长
        long ttl = isEmergencyQuery(query) ? EMERGENCY_TTL : NORMAL_TTL;
        if (cacheService.getAge(cached) > ttl) {
            return CacheCheckResult.invalidate("缓存过期");
        }

        return CacheCheckResult.allow();
    }
}
```

**设计原则**：哪里慢、哪里贵、哪里重复，就放哪里。向量检索 9ms 不值得缓存，LLM 550ms 才值得。

---

#### 场景总结

| GuardRail 场景 | 校验点 | 策略 |
|---------------|--------|------|
| 租户隔离 | 检索结果 + LLM 输出 + 资产归属 | 白名单校验 |
| 应急信息准确性 | 关键字段（逃生通道、消防设备） | 结构化库兜底 |
| LLM 调用成本 | QPS + 穿透检测 + 配额 | 限流 + 缓存 + 布隆过滤器 |
| 检索质量 | 相似度 + 文档量 + 一致性 | 冷启动保护 + 降级策略 |
| 缓存一致性 | 文档版本 + TTL | 自动失效 + 分级 TTL |

**面试回答模板**：

> "GuardRail 不是万能的，但在应急安全场景下，关键信息（逃生通道、消防设备）必须硬编码校验。我们用结构化库兜底 RAG，用限流+缓存+配额控制成本，用租户隔离防止数据泄露。规则不多，但每条都针对'出事会死'的场景。"

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

**解决的问题**：Agent 推理是多步的（Thought → Action → Observation），出问题时需要知道"LLM 在第几步做了什么决策"。

**不需要拆分的原因**：Trace 数据模型和 Prometheus 接入是通用的可观测性基础设施，跟业务场景无关。

**技术选型**：基于 **Micrometer + Prometheus + Grafana**（已有监控栈），不引入额外依赖。通过 **ThreadLocal + MDC** 传递 traceId，日志自动携带链路信息。

#### 数据模型

```java
/**
 * 链路上下文 — 贯穿整个请求生命周期（单 Agent 模式）
 */
@Data
public class TraceContext {
    private String traceId;           // 全局唯一，UUID
    private String tenantId;          // 租户 ID
    private String scene;             // 场景：ops / rag
    private long startTime;

    public static TraceContext create(String traceId, String tenantId, String scene) {
        TraceContext ctx = new TraceContext();
        ctx.setTraceId(traceId);
        ctx.setTenantId(tenantId);
        ctx.setScene(scene);
        ctx.setStartTime(System.currentTimeMillis());
        return ctx;
    }
}

/**
 * ThreadLocal 持有者 — 传递 traceId
 */
public class TraceContextHolder {
    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
        // 同步到 MDC（日志自动携带）
        MDC.put("traceId", ctx.getTraceId());
        MDC.put("tenantId", ctx.getTenantId());
        MDC.put("scene", ctx.getScene());
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

#### 平台层实现

```java
/**
 * Agent 追踪器 — 基于 Micrometer，单 Agent 模式
 */
@Component
public class AgentTracer {
    private final MeterRegistry meterRegistry;

    public AgentTracer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 开始新的 Trace（请求入口调用）
     */
    public TraceContext startTrace(String tenantId, String scene) {
        String traceId = UUID.randomUUID().toString();

        // 绑定到 ThreadLocal
        TraceContext ctx = TraceContext.create(traceId, tenantId, scene);
        TraceContextHolder.set(ctx);

        // 记录指标
        meterRegistry.counter("agent.request.total",
            "tenant", tenantId,
            "scene", scene
        ).increment();

        return ctx;
    }

    /**
     * 记录 Tool 调用
     */
    public void recordToolCall(String toolName, long durationMs, String status) {
        meterRegistry.counter("agent.tool.call.total",
            "tool", toolName,
            "status", status
        ).increment();

        meterRegistry.timer("agent.tool.call.duration",
            "tool", toolName
        ).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录 Token 使用
     */
    public void recordTokenUsage(String tenantId, int tokens) {
        meterRegistry.counter("agent.token.usage",
            "tenant", tenantId
        ).increment(tokens);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit(String tenantId, boolean hit) {
        meterRegistry.counter("agent.cache.hit",
            "tenant", tenantId,
            "hit", String.valueOf(hit)
        ).increment();
    }
}
```

#### 日志自动携带 traceId

```xml
<!-- logback-spring.xml -->
<pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{traceId}] [%X{tenantId}] [%X{scene}] %-5level %logger{36} - %msg%n</pattern>
```

日志输出示例：
```
2024-01-15 10:30:45 [http-nio-8080-exec-1] [a1b2c3d4] [tenant-001] [rag] INFO  AgentExecutor - ReAct loop started
2024-01-15 10:30:45 [http-nio-8080-exec-1] [a1b2c3d4] [tenant-001] [rag] INFO  ToolExecutor - Tool dense_retrieve called, duration=15ms
2024-01-15 10:30:45 [http-nio-8080-exec-1] [a1b2c3d4] [tenant-001] [rag] INFO  ToolExecutor - Tool sparse_retrieve called, duration=8ms
2024-01-15 10:30:46 [http-nio-8080-exec-1] [a1b2c3d4] [tenant-001] [rag] INFO  AgentExecutor - Final answer generated
```

#### Prometheus 指标

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `agent.request.total` | Counter | tenant, scene | 请求总量 |
| `agent.tool.call.total` | Counter | tool, status | Tool 调用次数 |
| `agent.tool.call.duration` | Timer | tool | Tool 调用耗时 |
| `agent.token.usage` | Counter | tenant | Token 消耗 |
| `agent.cache.hit` | Counter | tenant, hit | 缓存命中率 |

#### Grafana Dashboard

- 请求总量（按租户/场景）
- P99 端到端延迟
- 工具调用热力图
- Token 消耗趋势
- 缓存命中率

---

### 7. AgentExecutor — ReAct 推理引擎

**核心执行流程**，串联上述所有模块。通过 MCP Gateway 调用工具，不直接访问业务服务。

```java
@Component
public class AgentExecutor {
    private final ToolRegistry toolRegistry;       // MCP Gateway 的工具注册表
    private final PreProcessorChain preProcessor;
    private final ContextManager contextManager;
    private final Map<String, ContextStrategy> contextStrategies;
    private final GuardRail guardRail;
    private final TenantCtrl tenantCtrl;
    private final AgentTracer tracer;
    private final ChatClient chatClient;  // Spring AI

    public AgentResponse execute(AgentRequest request) {
        // 0. 租户检查
        TenantCheckResult check = tenantCtrl.check(request.getTenantId());
        if (!check.isAllowed()) return AgentResponse.quotaExceeded(check);

        // 1. 初始化追踪
        TraceContext traceCtx = tracer.startTrace(
            request.getTenantId(), request.getScene());

        // 2. 获取场景工具集 + 上下文策略
        List<ToolDefinition> tools = toolRegistry.getToolsByScene(request.getScene());
        ContextStrategy ctxStrategy = contextStrategies.getOrDefault(
            request.getScene(), new DefaultContextStrategy());

        // 3. ReAct 循环
        AgentSession session = new AgentSession(request, tools);
        while (!session.isFinished() && session.getStepCount() < maxSteps) {

            // 3a. 构建上下文
            ChatMessage context = contextManager.buildContext(session, ctxStrategy);

            // 3b. 调用 LLM → Thought + Action
            ChatResponse llmResponse = chatClient.prompt()
                .system(session.getSystemPrompt())
                .user(context.getContent())
                .functions(tools.stream().map(ToolDefinition::getName).toList())
                .call();

            ThoughtAction ta = parseThoughtAction(llmResponse);

            if (ta.hasAction()) {
                // 3c. 安全检查
                ToolDefinition tool = toolRegistry.getTool(ta.getActionTool());
                GuardResult guard = guardRail.checkBeforeExecute(
                    tool, ta.getActionParams(), request.getTenantId());

                if (!guard.isAllowed()) {
                    session.addObservation("操作被安全策略拦截: " + guard.getReason());
                    continue;
                }

                // 3d. 通过 MCP Gateway 调用工具（内部调用，非网络）
                long start = System.currentTimeMillis();
                ToolResult result = mcpGateway.callTool(
                    ta.getActionTool(),
                    ta.getActionParams(),
                    request.getTenantId()
                );
                long duration = System.currentTimeMillis() - start;

                // 3e. 记录指标
                tracer.recordToolCall(ta.getActionTool(), duration, "SUCCESS");

                // 3f. 预处理返回值
                String processed = preProcessor.process(ta.getActionTool(), result);

                // 3g. 写入 Observation
                session.addObservation(processed);
            } else {
                // 无 Action → LLM 认为推理完成
                session.setFinalAnswer(ta.getThought());
                session.setFinished(true);
            }
        }

        // 4. 收尾
        tracer.recordTokenUsage(request.getTenantId(), session.getTotalTokens());
        tenantCtrl.recordUsage(request.getTenantId(), session.toRecord());

        return AgentResponse.of(session.getFinalAnswer());
    }
}
```

---

## 业务场景接入示例

### 场景一：运维自愈 Agent

**业务服务**（ops-service）：只关心业务逻辑，暴露 REST 接口。

```java
// ops-service — 普通 REST 接口
@RestController
@RequestMapping("/api")
public class OpsController {

    @PostMapping("/log/query")
    public LogQueryResponse queryLog(@RequestBody LogQueryRequest request,
                                      @RequestHeader("X-Tenant-Id") String tenantId) {
        return logService.query(request, tenantId);
    }

    @PostMapping("/metric/query")
    public MetricQueryResponse queryMetric(@RequestBody MetricQueryRequest request,
                                            @RequestHeader("X-Tenant-Id") String tenantId) {
        return metricService.query(request, tenantId);
    }

    @PostMapping("/service/restart")
    public RestartResponse restartService(@RequestBody RestartRequest request,
                                           @RequestHeader("X-Tenant-Id") String tenantId) {
        return opsService.restart(request, tenantId);
    }
}
```

**MCP Gateway 配置**：工具注册 + 协议转换。

```yaml
# CoreAgent 服务配置
mcp:
  gateway:
    tools:
      - name: log_query
        description: "查询日志"
        service: ops-service
        path: /api/log/query
        method: POST
        risk-level: LOW
        scene: ops

      - name: metric_query
        description: "查询指标"
        service: ops-service
        path: /api/metric/query
        method: POST
        risk-level: LOW
        scene: ops

      - name: service_restart
        description: "重启服务"
        service: ops-service
        path: /api/service/restart
        method: POST
        risk-level: HIGH
        scene: ops
```

**System Prompt**：

```java
String OPS_PROMPT = """
    你是一个运维诊断助手。你的职责是分析告警信息，定位根因，给出修复建议。
    规则：
    1. 先查日志和指标，再做判断，不要猜测
    2. 高风险操作（重启、配置变更）必须说明理由并等待确认
    3. 最终输出格式：根因分析 → 修复方案 → 预期效果
    """;
```

---

### 场景二：RAG 问答 Agent

**业务服务**（rag-service）：

```java
@RestController
@RequestMapping("/api")
public class RAGController {

    @PostMapping("/retrieve/dense")
    public RetrieveResponse denseRetrieve(@RequestBody RetrieveRequest request,
                                           @RequestHeader("X-Tenant-Id") String tenantId) {
        return ragService.denseRetrieve(request, tenantId);
    }

    @PostMapping("/retrieve/sparse")
    public RetrieveResponse sparseRetrieve(@RequestBody RetrieveRequest request,
                                            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ragService.sparseRetrieve(request, tenantId);
    }

    @PostMapping("/rerank")
    public RerankResponse rerank(@RequestBody RerankRequest request,
                                  @RequestHeader("X-Tenant-Id") String tenantId) {
        return ragService.rerank(request, tenantId);
    }
}
```

**MCP Gateway 配置**：

```yaml
mcp:
  gateway:
    tools:
      - name: dense_retrieve
        description: "向量检索"
        service: rag-service
        path: /api/retrieve/dense
        method: POST
        risk-level: LOW
        scene: rag

      - name: sparse_retrieve
        description: "BM25 检索"
        service: rag-service
        path: /api/retrieve/sparse
        method: POST
        risk-level: LOW
        scene: rag

      - name: rerank
        description: "精排"
        service: rag-service
        path: /api/rerank
        method: POST
        risk-level: LOW
        scene: rag
```

**System Prompt**：

```java
String RAG_PROMPT = """
    你是一个安全知识问答助手。根据检索到的文档回答用户问题。
    规则：
    1. 只基于检索到的文档回答，不要编造
    2. 引用文档时标注来源
    3. 如果检索结果不足以回答，明确说明"未找到相关文档"
    """;
```

---

### 场景三：运营数据查询 Agent

运营人员用自然语言查询 SaaS 客户的业务数据，不需要写 SQL、不需要登录多个系统。

**业务服务**（analytics-service）：

```java
@RestController
@RequestMapping("/api")
public class AnalyticsController {

    @PostMapping("/visitor/query")
    public VisitorQueryResponse queryVisitor(@RequestBody VisitorQueryRequest request,
                                              @RequestHeader("X-Tenant-Id") String tenantId) {
        return analyticsService.queryVisitor(request, tenantId);
    }

    @PostMapping("/token/usage")
    public TokenUsageResponse queryTokenUsage(@RequestBody TokenUsageRequest request,
                                               @RequestHeader("X-Tenant-Id") String tenantId) {
        return analyticsService.queryTokenUsage(request, tenantId);
    }
}
```

**MCP Gateway 配置**：

```yaml
mcp:
  gateway:
    tools:
      - name: visitor_query
        description: "到访人数查询"
        service: analytics-service
        path: /api/visitor/query
        method: POST
        risk-level: LOW
        scene: analytics

      - name: token_usage_query
        description: "Token 用量查询"
        service: analytics-service
        path: /api/token/usage
        method: POST
        risk-level: LOW
        scene: analytics
```

**System Prompt**：

```java
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
Agent: Thought → 调用 visitor_query(today) → Observation: 1200人
Agent: "今日到访 1200 人，较昨日 +15%，较上周同期 +8%。"

运营: "哪个租户贡献最多？"
Agent: Thought → 调用 visitor_query(today, groupBy=tenant) → Observation: {A: 450, B: 320...}
Agent: "Top 3 租户：A园区 450人(37.5%)、B工厂 320人(26.7%)、C学校 210人(17.5%)。"
```

---

### 接入方式总结

**业务服务接入只需**：
1. 暴露普通 REST 接口（不需要了解 MCP 协议）
2. 从 Header 获取 `X-Tenant-Id` 做租户隔离
3. 从 Header 获取 `X-Trace-Id` 做链路追踪

**CoreAgent 平台接入只需**：
1. 在 `mcp.gateway.tools` 配置中添加工具定义
2. 实现 `ResultPreProcessor`（可选，工具返回值预处理）
3. 实现 `ContextStrategy`（可选，上下文裁剪策略）
4. 实现 `RiskRule`（可选，风险等级判定）
5. 注册 System Prompt

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
| **MCP Gateway** | **纯平台** | 工具注册、协议转换、租户隔离 | 业务服务只暴露 REST 接口 |
| **PreProcessor** | 平台+业务 | 接口定义、路由链（`PreProcessorChain`） | 具体预处理逻辑（日志聚合、指标趋势、文档截断） |
| **ContextManager** | 平台+业务 | Token 计数、预算分配框架、裁剪机制 | 优先级策略（`ContextStrategy`）：什么内容优先保留 |
| **GuardRail** | 平台+业务 | 检查框架、频率限制、人工确认服务、审计日志 | 风险判定规则（`RiskRule`）：哪些操作是 HIGH/CRITICAL |
| **TenantCtrl** | **纯平台** | Token 配额、QPS 限流、成本追踪 | 无需实现（配额差异通过配置解决） |
| **AgentTracer** | **纯平台** | Trace 数据模型、Prometheus 接入 | 无需实现（scene label 区分业务） |
| **AgentExecutor** | **纯平台** | ReAct 推理引擎，串联所有模块 | 无需实现 |

**核心原则**：
- **MCP Gateway 统一管控**：工具注册、协议转换、租户隔离都在 Gateway 层
- **业务服务零侵入**：只暴露 REST 接口，不需要了解 MCP 协议
- **配置化接入**：新增工具只需修改 `mcp.gateway.tools` 配置
- **平台层解决"怎么调 LLM"**，业务层解决"暴露什么接口"

新业务场景接入只需：
1. 业务服务暴露 REST 接口
2. 在 MCP Gateway 配置中添加工具定义
3. 可选：实现 `ResultPreProcessor`、`ContextStrategy`、`RiskRule`

**不需要改平台层代码**。

---

## 面试回答要点

1. **定位清晰**：不是造框架，是在 Spring AI 之上做业务集成层
2. **MCP Gateway 统一管控**：工具注册、协议转换、租户隔离都在 Gateway 层，业务服务零侵入
3. **配置化接入**：新增工具只需修改配置，不改代码；业务服务只暴露 REST 接口
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

**注意**：无论哪种推理模式，工具调用都通过 MCP Gateway，业务服务保持零侵入。
