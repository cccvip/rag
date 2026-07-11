# CoreAgent 2.0 升级计划

## 目标

把 `core-agent` 从「可运行的 ReAct 原型」升级为「基于状态图的 Agent 编排平台」，对齐招聘市场对 Agent 研发的核心要求：

- 状态机编排（State Graph）
- 多执行策略（ReAct / Plan-and-Execute / Reflection）
- 结构化工具调用与并行执行
- 多 Agent 协作（Supervisor + Workers）
- 人工介入断点（HITL）
- 结构化输出与评估框架

同时保持：Java 17 + Spring Boot 3.3.5 + Spring AI 1.0.0-M4 技术栈不变；现有 `TenantCtrl`、`GuardRail`、`Tracer` 等平台能力继续复用。

---

## 当前状态（截至阶段一完成）

### 已具备能力
- ReAct 推理引擎（`Agent.java`）
- 工具注册表（`ToolRegistry` / `Tool`）
- 基础安全护栏（`GuardRail` / `RiskLevel`）
- 内存版 Memory 模块（`MemoryManager` / `MemoryStore`）
- 7 项评测指标（`MetricsTracker`）
- LLM / Tool 超时与重试
- 规则评判 + 可开关的 Reflection
- 平台级上下文管理（`ContextManager` + `ContextStrategy`）
- 租户管控（`TenantCtrl`：配额、限流、成本追踪）
- 调用链追踪（`AgentTracer`：TraceId + Micrometer）
- MCP Gateway / 配置化工具注册

### 多模块重构（已完成）

`core-agent` 已从单模块改造为 Maven 多模块工程：

```
core-agent/
├── pom.xml                              # 父 POM
├── core-agent-shared/                   # 共享模型与异常
├── core-agent-api/                      # 公共 API 与 SPI
├── core-agent-runtime/                  # 默认实现（内存版 + MCP Gateway）
├── core-agent-engine/                   # 状态图引擎 + 执行策略 + Agent 入口
└── core-agent-starter/                  # Spring Boot 装配 + 启动类 + REST 接口
```

**关键调整**：
- `AgentGraph` / `AgentNode` / `AgentEdge` 改为泛型 `<C>`，避免 api 依赖引擎上下文
- `NodeContext` / `GraphExecutor` / `ReactStrategy` / `Agent` 放入 `core-agent-engine`
- `McpProperties` / `McpConfig` / `McpController` 放入 `core-agent-starter`
- `McpGateway` 改为接收 `int timeoutMs`，不再依赖 `McpProperties`
- `McpToolRegistry` / `StaticServiceResolver` 改为接收简单集合，不再依赖 `McpProperties`
- `AgentTracer.noOp()` 改为匿名内部类，避免 api 依赖 runtime 实现

**验证**：
```bash
mvn clean test
# Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS
```

### 阶段一新增能力（已落地）
- 状态图核心模型：`AgentState`、`AgentNode`、`AgentEdge`、`AgentGraph`、`Checkpoint`
- 策略接口：`ExecutionStrategy`
- ReAct 策略实现：`ReactStrategy`
- 状态图执行器：`GraphExecutor`
- `Agent.run()` 内部已改为状态图驱动，对外 API 保持兼容

### 核心待补齐
- Plan-and-Execute / Reflection 策略
- 并行工具执行
- Spring AI Function Calling 替代字符串解析
- HITL 人工审批流程
- 多 Agent 编排（Supervisor + Workers）
- 系统化 Eval 框架

---

## 目标架构

```
业务场景层
    RAG Agent │ 运维 Agent │ 运营 Agent
              ↓
        AgentOrchestrator（多 Agent 编排）
              ↓
        AgentGraph（状态图执行引擎）
   ┌──────────┼──────────┬──────────────┐
   ↓          ↓          ↓              ↓
ReActStrategy PlanStrategy ReflectStrategy HITLCheckpoint
   └──────────┴──────────┴──────────────┘
              ↓
        ToolExecutor（串行/并行/条件）
              ↓
   Spring AI ChatModel / FunctionCallback
              ↓
   TenantCtrl │ GuardRail │ Tracer │ Memory
```

---

## 关键设计决策

### 1. 状态图作为统一抽象
所有执行模式（ReAct、Plan-and-Execute、多 Agent）都建模为**有向图**：
- `AgentState`：不可变状态快照，携带消息、中间结果、元数据、checkpoint token。
- `AgentNode`：节点接口，接收 state，返回新 state。
- `AgentEdge`：边，支持条件路由 `state -> nextNodeName`。
- `AgentGraph`：图的构建器和执行器，支持同步/异步执行、checkpoint、重放。

这样天然支持：暂停、恢复、重试、可视化、调试。

### 2. 执行策略可插拔
`ExecutionStrategy` 负责把业务意图编译成图：
- `ReActStrategy`：保留现有行为，但用状态图实现。
- `PlanAndExecuteStrategy`：先让 LLM 生成计划，再按步骤执行。
- `ReflectionStrategy`：在失败节点后插入反思节点。
- `SupervisorStrategy`：多 Agent 编排，由 Supervisor 分配任务给 Worker。

### 3. 工具调用现代化
- 引入 `ToolCallRequest` / `ToolCallResult` 结构化对象，带参数映射。
- `Tool` 接口升级为支持 `ToolDefinition`（name/description/parameters）。
- `ToolExecutor` 支持串行、并行、条件分支。
- 接入 Spring AI `FunctionCallback`，让 LLM 通过原生 Function Calling 输出工具调用，**彻底替代字符串解析**。

### 4. HITL 作为一等公民
- `HumanCheckpoint` 节点：执行到此处时抛出/返回 `AwaitingHumanApproval` 状态。
- `HumanApprovalService` 接口：内存版（测试）、REST 版（生产对接审批系统）。
- `AgentGraph.resume(checkpointToken, decision)`：从断点恢复。
- 新增 REST 端点 `/agent/approval/{token}` 供人工审批。

### 5. 多 Agent 最小可用模型
第一版只做 **Supervisor + Workers**：
- `AgentRole`：定义角色、system prompt、可用工具子集。
- `SupervisorAgent`：接收任务，决定交给哪个 Worker，聚合结果。
- `WorkerAgent`：执行子任务，返回结果。
- 所有 Worker 共享同一个 `AgentGraph` 执行引擎。

不一开始就搞复杂的消息总线或 Agent 间竞价，避免过度工程。

---

## 阶段规划

### 阶段一：状态图引擎与 ReAct 重构（✅ 已完成）

**目标**：用状态图重新实现现有 ReAct，保证所有现有测试通过，行为不变。

| # | 任务 | 说明 | 预计工时 | 产出文件 |
|---|------|------|---------|--------|
| ✅ 1.1 | 定义 `AgentState` | 不可变状态对象：messages、variables、checkpoint、metadata | 0.5d | `agent/graph/domain/AgentState.java` |
| ✅ 1.2 | 定义 `AgentNode` / `AgentEdge` / `AgentGraph` | 图构建器 + 执行器，支持条件边、checkpoint、重放 | 1d | `agent/graph/domain/*.java` |
| ✅ 1.3 | 定义 `ExecutionStrategy` 接口 | `compile() -> AgentGraph` 契约 | 0.5d | `agent/strategy/domain/ExecutionStrategy.java` |
| ✅ 1.4 | 实现 `ReactStrategy` | 把现有 ReAct 逻辑搬进状态图 | 1d | `agent/strategy/infrastructure/ReactStrategy.java` |
| ✅ 1.5 | 实现 `GraphExecutor` | 状态图执行器薄封装 | 0.5d | `agent/graph/application/GraphExecutor.java` |
| ✅ 1.6 | 改造 `Agent` 类 | 内部使用 `AgentGraph` + `ReactStrategy`，对外 API 尽量不变 | 1d | `agent/domain/Agent.java` |
| ✅ 1.7 | 补充状态图单元测试 | 线性图、条件边、checkpoint、halt、maxSteps | 0.5d | `graph/AgentGraphTest.java` |
| ✅ 1.8 | 兼容性回归 | 确保 `AgentTest`、`AgentTenantIntegrationTest` 全部通过 | 0.5d | 现有测试文件 |

**阶段一里程碑（已达成）**：
- `mvn clean test` 全部通过：42 个测试，0 失败，0 错误
- 新增 `AgentState` / `AgentNode` / `AgentEdge` / `AgentGraph` / `Checkpoint` 状态图核心模型
- 新增 `ExecutionStrategy` 策略接口与 `ReactStrategy` 实现
- `Agent.run()` 内部由 `AgentGraph` 驱动，对外接口保持兼容
- 保留原有 ReAct 行为，所有历史测试通过

**新增/修改文件**：
- 新增：`agent/graph/domain/AgentMessage.java`
- 新增：`agent/graph/domain/AgentState.java`
- 新增：`agent/graph/domain/AgentNode.java`
- 新增：`agent/graph/domain/AgentEdge.java`
- 新增：`agent/graph/domain/AgentGraph.java`
- 新增：`agent/graph/domain/Checkpoint.java`
- 新增：`agent/graph/domain/NodeContext.java`
- 新增：`agent/graph/application/GraphExecutor.java`
- 新增：`agent/strategy/domain/ExecutionStrategy.java`
- 新增：`agent/strategy/infrastructure/ReactStrategy.java`
- 新增：`src/test/java/com/core/agent/graph/AgentGraphTest.java`
- 修改：`agent/domain/Agent.java`（内部委托给状态图引擎）
- 修改：`agent/graph/domain/NodeContext.java`（补充 ContextManager / MetricsTracker）

---

### 阶段二：Plan-and-Execute + Reflection + 并行工具

**目标**：在状态图基础上增加高级执行策略和并行能力。

| # | 任务 | 说明 | 预计工时 | 产出文件 |
|---|------|------|---------|--------|
| 2.1 | 实现 `PlanAndExecuteStrategy` | Plan 节点生成步骤列表，Execute 节点逐项执行 | 1.5d | `agent/strategy/infrastructure/PlanAndExecuteStrategy.java` |
| 2.2 | 实现 `ReflectionStrategy` | 在任意节点失败后插入反思重试子图 | 1d | `agent/strategy/infrastructure/ReflectionStrategy.java` |
| 2.3 | 实现 `ParallelToolExecutor` | 并行调用多个工具，聚合结果 | 1d | `tool/infrastructure/ParallelToolExecutor.java` |
| 2.4 | 接入 Spring AI Function Calling | 用 `FunctionCallback` 替换字符串解析 | 2d | `tool/infrastructure/SpringAiToolAdapter.java` |
| 2.5 | 输出解析器 | `JsonOutputParser` + `ReActOutputParser` 两种实现 | 0.5d | `agent/parser/domain/*.java` |
| 2.6 | 策略配置化 | `application.yml` 支持 `agent.strategy=react|plan|reflect` | 0.5d | `application.yml` |
| 2.7 | 补充策略测试 | Plan/Reflection/并行工具均有端到端测试 | 1d | 新增测试 |

**阶段二里程碑**：新增 Plan-and-Execute 测试；RAG 场景可用 Plan 模式跑多步检索；字符串解析有 Function Calling 替代方案。

---

### 阶段三：HITL 与多 Agent

**目标**：支持高风险操作人工确认和多 Agent 协作。

| # | 任务 | 说明 | 预计工时 | 产出文件 |
|---|------|------|---------|--------|
| 3.1 | 实现 `HumanCheckpointNode` | 在图中插入等待人工审批节点 | 0.5d | `agent/hitl/domain/HumanCheckpointNode.java` |
| 3.2 | 实现 `HumanApprovalService` | 内存版 + 接口，支持 approve/reject/resume | 0.5d | `agent/hitl/application/HumanApprovalService.java` |
| 3.3 | 改造 `GuardRail` | HIGH 风险工具触发 checkpoint，而非直接 block | 0.5d | `guardrail/domain/GuardRail.java` |
| 3.4 | 新增审批 REST API | `/agent/approval/{token}` POST | 0.5d | `agent/interfaces/ApprovalController.java` |
| 3.5 | 定义 `AgentRole` | 角色、system prompt、可用工具子集 | 0.5d | `agent/role/domain/AgentRole.java` |
| 3.6 | 实现 `SupervisorStrategy` | Supervisor 分配任务给 Worker，聚合结果 | 1.5d | `agent/strategy/infrastructure/SupervisorStrategy.java` |
| 3.7 | 多 Agent 测试 | Supervisor 路由 + Worker 执行 + HITL 中断 | 1d | 新增测试 |

**阶段三里程碑**：HIGH 风险工具触发人工审批；Supervisor + Worker 多 Agent 链路跑通。

---

### 阶段四：评估与工程化收尾

**目标**：补齐评估框架和文档，让面试表述有代码支撑。

| # | 任务 | 说明 | 预计工时 | 产出文件 |
|---|------|------|---------|--------|
| 4.1 | 实现 `AgentEvaluator` | 接入 RAGAS 指标：Faithfulness、AnswerRelevance、ContextPrecision | 1d | `eval/domain/AgentEvaluator.java` |
| 4.2 | LLM-as-a-Judge | 用 LLM 评判输出质量 | 0.5d | `eval/infrastructure/LlmAsJudge.java` |
| 4.3 | 结构化输出 Schema | 支持 POJO + `@JsonSchema` 生成 | 1d | `agent/output/domain/StructuredOutput.java` |
| 4.4 | 更新 `coreAgent.md` | 设计文档与代码一致 | 1d | `coreAgent.md` |
| 4.5 | 整理 README | 运行方式、如何新增场景、关键设计决策 | 0.5d | `core-agent/README.md` |
| 4.6 | 更新本计划 | 标记所有完成项 | 0.5d | `plan.md` |

**阶段四里程碑**：有可运行的 Eval 测试；设计文档对齐；README 完整。

---

## 总览

| 阶段 | 主题 | 预计工时 | 里程碑 |
|:---|:---|:---|:---|
| 第一阶段 | 状态图底座 + ReAct 重构 | 5.5 天 | ✅ 状态图引擎落地，42 个测试全绿 |
| 第二阶段 | 高级策略 + 并行工具 + Function Calling | 6.5 天 | Plan/Reflection 策略 + 并行工具 |
| 第三阶段 | HITL + 多 Agent | 4.5 天 | 人工审批 + Supervisor/Worker |
| 第四阶段 | 评估框架 + 文档 | 3.5 天 | Eval + 文档对齐 |
| **合计** | | **约 20 天** | |

---

## 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| Spring AI 1.0.0-M4 的 Function Calling API 不稳定 | 阶段二可能延期 | Function Calling 作为 P1.5，先保证状态图和策略稳定；字符串解析保留为 fallback |
| 状态图重构破坏现有行为 | 测试失败 | 阶段一已严格保证现有测试全绿；后续每阶段先跑回归再新增功能 |
| HITL 引入异步语义增加复杂度 | 代码难维护 | 第一版只支持同步阻塞等待审批；异步通知作为后续扩展 |
| 多 Agent 过度设计 | 工期超支 | 只做 Supervisor + Worker，不做通用 Agent 市场 |

---

## 下一步建议

进入 **阶段二**，因为：
- 状态图底座已稳定，可以在其上叠加高级策略
- Plan-and-Execute 是面试中最常被问到的 Agent 模式之一
- 并行工具和 Function Calling 能直接解决当前 ReAct 的痛点（字符串解析脆弱、工具串行低效）

具体可以从 **2.1（PlanAndExecuteStrategy）** 开始，让 RAG 查询可以先规划再执行多步检索。
