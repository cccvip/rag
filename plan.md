# CoreAgent 落地计划

## 目标

把当前 `core-agent` 从「可运行的 ReAct 原型」升级为「平台级 Agent 中台」，对齐 `coreAgent.md` 设计蓝图，支撑简历中「120+ 租户、RAG/运维/运营多场景、成本可控」的表述。

---

## 当前状态

已完成：
- ReAct 推理引擎（`Agent.java`）
- 工具注册表（`ToolRegistry` / `Tool`）
- 基础安全护栏（`GuardRail` / `RiskLevel`）
- 内存版 Memory 模块（`MemoryManager` / `MemoryStore`）
- 7 项评测指标（`MetricsTracker`）
- LLM / Tool 超时与重试
- 规则评判 + 可开关的 Reflection

核心缺失：
- 平台级上下文管理（`ContextManager` + `ContextStrategy`）
- 租户管控（`TenantCtrl`：配额、限流、成本追踪）
- 调用链追踪（`AgentTracer`：TraceId + Micrometer）
- MCP Gateway / 配置化工具注册
- 工具返回值预处理（`PreProcessorChain`）
- GuardRail 扩展机制（`RiskRule`、频率限制、人工确认）

---

## 阶段规划

### 第一阶段：补齐平台层骨架（纯平台能力）

**目标**：让 `core-agent` 具备平台级 Agent 中台的基础能力，面试时能讲清楚租户隔离、上下文管理、调用链追踪。

| # | Todo 项 | 说明 | 预计工时 | 产出物 |
|---|--------|------|---------|--------|
| ✅ 1.1 | 抽象 `MessageBlock` | 把 Prompt 内容拆分为 system / user / tool-result / thought / observation 等块 | 0.5d | 新类 `MessageBlock` |
| ✅ 1.2 | 实现 `ContextStrategy` 接口 | 定义 `priority(MessageBlock)`、`trim()` 契约 | 0.5d | `ContextStrategy` 接口 |
| ✅ 1.3 | 实现 `ContextManager` | 收集 blocks → token 计数 → 按策略优先级裁剪 | 1d | `ContextManager` 类 |
| ✅ 1.4 | 提供默认策略 + RAG/运维策略 | `DefaultContextStrategy`、`RagContextStrategy`、`OpsContextStrategy` | 0.5d | 3 个策略实现 |
| ✅ 1.5 | 改造 `Agent` 接入 `ContextManager` | 替换当前简单历史拼接逻辑 | 1d | `Agent.java` 更新 |
| ✅ 1.6 | 设计 `TenantQuota` 与 `TenantUsage` 模型 | 租户套餐、月度 Token 配额、已用量 | 0.5d | 实体类 |
| ✅ 1.7 | 实现 `TenantCtrl.check()` | Token 配额检查 + QPS 限流（基于内存/Redis） | 1d | `TenantCtrl` 类 |
| ✅ 1.8 | 实现 `TenantCtrl.recordUsage()` | 记录单次调用 Token、延迟、工具调用次数 | 0.5d | 记录逻辑 + 内存存储 |
| ✅ 1.9 | 接入 `Agent.run()` | 调用前后执行租户检查与用量记录 | 0.5d | `Agent.java` 更新 |
| ✅ 1.10 | 实现 `TraceContext` + `TraceContextHolder` | ThreadLocal + MDC 传递 traceId/tenantId/scene | 0.5d | 追踪上下文类 |
| ✅ 1.11 | 实现 `AgentTracer` | 基于 Micrometer 记录请求、Tool 调用、Token、缓存命中 | 1d | `AgentTracer` 类 |
| ✅ 1.12 | 统一日志格式 | 日志输出携带 `[traceId] [tenantId] [scene]` | 0.5d | logback 配置 + MDC |
| ✅ 1.13（部分） | 补充 ContextManager 单元测试 | 覆盖预算内保留、超预算裁剪、RAG 策略、顺序保持 | 0.5d | `ContextManagerTest` |

**第一阶段里程碑**：`mvn test` 全部通过，`Agent.run()` 跑通 RAG 多轮 QA，日志带 traceId，租户配额/限流可演示。

---

### 第二阶段：工具层平台化

**目标**：工具可配置化注册，返回值可预处理，GuardRail 可扩展业务规则。

| # | Todo 项 | 说明 | 预计工时 | 产出物 |
|---|--------|------|---------|--------|
| 2.1 | 设计 `ToolDefinition` | name、description、service、path、method、riskLevel、scene 等元数据 | 0.5d | `ToolDefinition` 类 |
| 2.2 | 实现 `McpGateway` 接口 | 按 toolName 路由到后端服务，做 HTTP 调用 | 1d | `McpGateway` 接口 + 实现 |
| 2.3 | 配置化工具注册 | 从 `application.yml` 或 JSON 加载工具定义 | 0.5d | 配置类 + 示例配置 |
| 2.4 | `ToolRegistry` 改造 | 支持按 scene 过滤工具列表 | 0.5d | `ToolRegistry` 升级 |
| 2.5 | 实现 `ResultPreProcessor` 接口 | `supports(toolName)` + `process(raw, ctx)` | 0.5d | 接口 |
| 2.6 | 实现 `PreProcessorChain` | 按 toolName 路由到具体处理器 | 0.5d | 路由类 |
| 2.7 | 提供示例预处理器 | `DocPreProcessor`（截断摘要）、`LogPreProcessor`（聚合去重） | 1d | 2 个示例实现 |
| 2.8 | 接入 `Agent` 执行链路 | Tool 执行后先过 PreProcessor，再写入 Observation | 0.5d | `Agent.java` 更新 |
| 2.9 | 扩展 `GuardRail`：支持 `RiskRule` | 业务方可自定义风险判定规则 | 0.5d | `RiskRule` 接口 + GuardRail 改造 |
| 2.10 | 增加频率限制器 | 同租户同工具短时间重复调用拦截（内存/Redis） | 0.5d | `RateLimiter` |
| 2.11 | 增加人工确认接口 | HIGH 风险操作走人工确认（先 mock 实现） | 0.5d | `HumanConfirmService` |
| 2.12 | 补充单元测试 | McpGateway、PreProcessor、GuardRail 扩展均有测试 | 1d | 测试类 |

**第二阶段里程碑**：新增工具只需改配置；RAG 文档返回可自动截断；HIGH 风险工具触发确认。

---

### 第三阶段：工程化打磨与场景演示

**目标**：把多场景接入跑通，补全文档，让代码和面试表述完全对齐。

| # | Todo 项 | 说明 | 预计工时 | 产出物 |
|---|--------|------|---------|--------|
| 3.1 | 场景化 System Prompt 管理 | RAG、运维、运营各自独立 Prompt，支持外部文件加载 | 0.5d | `prompts/rag.txt`、`prompts/ops.txt` |
| 3.2 | 配置化 scene 接入 | `application.yml` 中配置 scene → tools + strategy + prompt | 0.5d | 配置示例 |
| 3.3 | 接入 Spring AI Function Calling | 用原生 Function Calling 替代字符串解析 Thought/Action | 2d | `Agent.java` 重构 |
| 3.4 | 实现 `Plan-and-Execute` 策略 | 作为 `AgentExecutionStrategy` 第二种实现 | 1.5d | 新策略类 |
| 3.5 | AgentExecutor 策略路由 | 根据场景选择 ReAct / Plan-and-Execute | 0.5d | `AgentExecutor` 改造 |
| 3.6 | 集成测试 | 覆盖 RAG 问答完整链路、运维诊断链路 | 1d | 集成测试 |
| 3.7 | 更新 `coreAgent/Memory.md` 与 `coreAgent.md` | 代码实现与设计文档保持一致 | 1d | 文档更新 |
| 3.8 | 整理 README | 说明如何运行、如何新增场景、关键设计决策 | 0.5d | `core-agent/README.md` |

**第三阶段里程碑**：`core-agent` 可直接跑 RAG/运维两个场景 demo；设计文档与代码一致；README 完整。

---

## 总览

| 阶段 | 主题 | 预计工时 | 里程碑 |
|:---|:---|:---|:---|
| 第一阶段 | 平台层骨架 | 9.5 天 | 上下文管理 + 租户管控 + 调用链追踪落地 |
| 第二阶段 | 工具层平台化 | 7.5 天 | 配置化工具 + 预处理 + GuardRail 扩展 |
| 第三阶段 | 工程化打磨 | 7.5 天 | 多场景 demo + Function Calling + 文档对齐 |
| **合计** | | **约 24.5 天** | |

---

## 风险与调整建议

1. **工时偏乐观**：如果 Spring AI 1.0.0-M4 的 Function Calling API 不稳定，第三阶段可能延期。
   - 应对：Function Calling 作为 P2，先保证第一、二阶段稳定。

2. **Redis/Micrometer 依赖**：第一阶段需要引入 Redis 客户端（如 lettuce）和 Micrometer。
   - 应对：先用内存版实现跑通逻辑，Redis/Micrometer 以接口形式预留，后续替换。

3. **测试数据准备**：集成测试需要模拟后端服务响应。
   - 应对：用 WireMock 或手写 mock `McpGateway` 实现。

---

## 下一步建议

建议先进入 **第一阶段**，因为：
- 纯平台能力，与业务无关，面试价值最高
- 不依赖外部服务，可独立验证
- 补齐后，当前 demo 的「租户隔离、成本追踪、调用链」就有落地点

具体可以从 **1.1 ~ 1.5（ContextManager）** 开始，改造 `Agent` 的上下文拼接逻辑。
