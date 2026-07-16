# CoreAgent 2.0 演进计划

> 定位：企业级 Java AI Agent 中台
> 目标：让业务系统能安全、可控、低成本地接入 AI 能力

---

## 一、战略定位

**CoreAgent = 企业级 Java AI Agent 中台**

不跟 Python 卷模型和算法，专注四层能力：

1. **Agent 编排层**：状态图、多策略、多 Agent 协同
2. **工具治理层**：工具注册、协议转换、租户隔离、安全护栏
3. **标准协议层**：MCP、A2A、Function Calling
4. **可观测评估层**：指标、追踪、RAGAS / LLM-as-a-Judge

---

## 二、阶段路线图

### 阶段一：Agent 执行引擎 ✅ 已完成

**目标**：拥有自研、可扩展的单 Agent 执行能力。

**交付**：
- 状态图引擎 `AgentGraph` + 不可变 `AgentState`
- `ExecutionStrategy` SPI
- ReAct / Plan-and-Execute 策略
- Maven 五模块拆分
- 42 个单元测试

**对应招聘需求**：Agent 推理引擎、状态图编排、策略 SPI、工程化模块化。

---

### 阶段二：企业级单 Agent 强化 ✅ 已完成

**目标**：让单 Agent 具备生产可用性，补齐标准协议和评估。

**交付**：
- Spring AI Function Calling 接入
- LLM-as-a-Judge 评估框架（faithfulness / answer_relevancy / context_precision）
- 标准 MCP SSE Server（initialize / tools/list / tools/call）
- RAG 链路 SPI + 内存版实现 + 向量检索工具

**边界**：向量存储目前为内存版，Qdrant/Milvus 实现可插拔；HITL 和持久化 Memory 未实现。

**对应招聘需求**：Function Calling、RAG 链路、MCP 协议、Agent 评估。

---

### 阶段三：多 Agent 协同中台 ✅ 已完成

**目标**：从单 Agent 演进为多 Agent 编排。

**交付**：
- Supervisor + Workers 架构（`SupervisorStrategy` + `DecomposeNode` / `WorkerDispatchNode` / `AggregateNode`）
- A2A 协议接入（`A2aGateway` + `HttpA2aGateway`）
- Agent 注册与发现（`AgentCard` + `AgentRegistry` + `AgentRegistryController`）
- 子任务并行执行（`CompletableFuture` 并行 `Worker` 调度）
- 跨 Agent 记忆共享（`SharedMemoryManager` + `MemoryScope` + JPA 持久化）
- HITL 人工审批节点（`HumanApprovalNode` + `CheckpointController`）
- 持久化 Memory / Checkpoint（JPA + H2，生产可替换为 PostgreSQL/MySQL）
- Agent 统一执行入口（`AgentExecutionController`：run / resume / status）

**对应招聘需求**：Multi-Agent、A2A、任务编排、人机协同。

---

### 阶段四：平台治理与规模化 🚧 待实现

**目标**：让 CoreAgent 成为真正的"中台"，多业务线可接入。

**交付**：
- 可视化执行链路 / Debug UI
- Prompt 版本管理与 A/B 测试
- 成本配额与模型路由
- 配置中心动态刷新
- 多模型 Provider 适配
- 真实 Qdrant / Milvus 向量存储实现

**对应招聘需求**：AI 平台产品化、成本优化、可观测性、企业级治理。

---

## 三、模块职责

```
core-agent/
├── core-agent-shared/    # 枚举、异常、常量
├── core-agent-api/       # 接口与 SPI（AgentGraph、ExecutionStrategy）
├── core-agent-runtime/   # 默认实现（MCP Gateway、Memory、RAG、评估）
├── core-agent-engine/    # 编排逻辑（Agent、策略实现）
└── core-agent-starter/   # Spring Boot 装配 + REST 入口
```

---

## 四、当前状态

- **已完成**：阶段一、阶段二、阶段三核心内容
- **全量测试**：`mvn clean test` 通过
- **下一步**：阶段四（平台治理与规模化）
