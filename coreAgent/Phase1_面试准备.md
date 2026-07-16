# CoreAgent 阶段一：面试应答指南

> 本文档用于面试前快速复习，帮助你在 5-10 分钟内把阶段一的工作讲清楚、讲出彩。
>
> 目标岗位：Java AI 中台 / Agent 研发工程师

---

## 一、一句话定位

**阶段一完成了 CoreAgent 从 "ReAct 原型" 到 "基于状态图的 Agent 执行引擎" 的升级，并拆分为五模块 Maven 工程，为后续多策略、多场景接入打下基础。**

面试开场可以这样讲：

> "我主导的 CoreAgent 项目，第一阶段是把一个硬编码的 ReAct 原型，重构成基于状态图的 Agent 执行引擎。核心工作包括三点：第一，抽象出 AgentGraph + AgentState 的状态图核心模型；第二，把 ReAct 改造成 ExecutionStrategy SPI 的首个实现；第三，按 clean architecture 拆成五个 Maven 子模块。最终 42 个单元测试全部通过，代码也已推送到 GitHub。"

---

## 二、阶段一核心成果（面试时按这个顺序讲）

### 2.1 状态图引擎

- 自研轻量状态图 `AgentGraph<C>`，支持有向节点、条件边、checkpoint、暂停/恢复
- 核心模型：`AgentState`、`AgentNode`、`AgentEdge`、`Checkpoint`、`GraphResult`
- `AgentState` 设计为**不可变对象**，每次状态变更返回新副本

### 2.2 策略 SPI

- 定义 `ExecutionStrategy<C>` 接口：`name()` + `compile()`
- `ReactStrategy` 成为第一个实现
- `PlanAndExecuteStrategy` 随后加入，作为阶段二预热

### 2.3 多模块 Maven 拆分

| 模块 | 职责 | 关键类 |
|---|---|---|
| `core-agent-shared` | 枚举/异常 | `RiskLevel`、`McpException` |
| `core-agent-api` | 接口与 SPI | `AgentGraph`、`AgentState`、`ExecutionStrategy` |
| `core-agent-runtime` | 默认实现 | `McpGateway`、`MetricsTracker`、`InMemoryMemoryManager` |
| `core-agent-engine` | 编排逻辑 | `Agent`、`ReactStrategy`、`GraphExecutor` |
| `core-agent-starter` | Spring Boot 装配 | `AgentApp`、`McpConfig` |

### 2.4 关键解耦

- `AgentGraph<C>` 泛型化，避免 `api` 模块依赖引擎上下文
- `McpGateway` 接收 `int timeoutMs`，不再依赖配置类
- 工具注册改为接收简单集合，方便测试和替换

### 2.5 验证

```bash
mvn clean test
# Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

---

## 三、关键技术决策 & 为什么这么做

### 3.1 为什么要从 ReAct 改成 State Graph？

**标准回答：**

> "ReAct 是 '边想边做' 的循环，适合简单任务。但随着场景复杂，我们面临三个问题：
> 1. **难以扩展**：加一个新推理模式就要改 Agent 主流程；
> 2. **难以调试**：中间状态散落在代码各处，出问题不好定位；
> 3. **难以恢复**：一旦中断，无法从中间状态恢复。
>
> 状态图把所有执行模式统一建模为有向图，天然支持暂停、恢复、重试、可视化，也方便我们对齐 LangGraph 的设计思想，同时保留自研可控性。"

### 3.2 AgentState 为什么设计为不可变？

**标准回答：**

> "不是'状态不变'，而是'对象不变，变化通过新对象体现'。这样有三个好处：
> 1. **状态转移显式化**：每次变更都返回新副本，便于追踪；
> 2. **Checkpoint / 重放**：一个 `AgentState` 就是完整快照，恢复时直接丢回图里继续跑；
> 3. **避免节点间副作用**：节点 A 不会污染节点 B 的输入状态，测试也更确定。"

### 3.3 为什么要拆成五个 Maven 模块？

**标准回答：**

> "我们按 clean architecture 拆分，核心原则是依赖无环：
> - `api` 只定义接口，不依赖 Spring，方便被业务系统直接引用；
> - `runtime` 放可替换实现，未来可以换 Qdrant、Redis、真实 MCP Server；
> - `engine` 只关心编排逻辑，不耦合具体工具；
> - `starter` 负责 Spring Boot 装配。
>
> 这样业务场景接入时，只需要依赖 `api` 和 `starter`，不需要了解内部实现。"

### 3.4 自研状态图 vs 直接用 LangGraph？

**标准回答（必考题）：**

> "选型时我们对比过 LangGraph，最终选择自研主要出于三点考虑：
> 1. **可控性**：企业级场景需要深度定制租户隔离、成本配额、审计日志，自研更灵活；
> 2. **技术深度**：面试和项目中，自研能证明对 Agent 执行原理的理解，而不是只会调包；
> 3. **Java 生态**：LangGraph 是 Python，我们整个中台是 Java + Spring AI，自研更贴合技术栈。
>
> 但我们也不是闭门造车，状态图的设计思想、节点/边/条件路由这些概念都是对齐 LangGraph 的，未来如果需要，也可以把 LangGraph 作为一个 `ExecutionStrategy` 实现接入进来。"

---

## 四、高频面试问题 & 标准回答

### Q1：阶段一对应哪些招聘要求？

> "主要覆盖 Agent 工程师 JD 中的五项能力：
> 1. Agent 推理引擎实现（ReAct 多步推理）；
> 2. 状态图编排能力（自研轻量版 LangGraph）；
> 3. 策略可插拔架构（`ExecutionStrategy` SPI）；
> 4. 工程化与模块化（Maven 多模块、clean architecture）；
> 5. 平台级基础设施复用（GuardRail、TenantCtrl、AgentTracer、MemoryManager）。"

### Q2：状态图执行流程是怎样的？

> "入口是 `AgentGraph.execute(state, ctx)`。内部是一个 while 循环：
> 1. 检查是否到达 `endNode`，到达则执行结束节点并返回结果；
> 2. 根据当前节点名找到对应 `AgentNode` 执行；
> 3. 节点执行后，根据状态路由到下一个节点（条件边优先，无条件边兜底）；
> 4. 超过 `maxSteps` 则报错退出。
>
> 所有状态变更都通过不可变的 `AgentState` 传递。"

### Q3：如果新增一个 Reflection 策略，需要改哪些地方？

> "几乎不需要改现有代码。只需要：
> 1. 新建一个类实现 `ExecutionStrategy<NodeContext>`；
> 2. 在 `compile()` 里构建 Reflection 状态图（例如：execute → reflect → 判断是否需要修正 → 循环或结束）；
> 3. 通过 `Agent.withStrategy()` 注入即可。
>
> 这就是 SPI 设计的好处。"

### Q4：多模块拆分后，依赖关系怎么保证无环？

> "我们规定：
> - `shared` 不依赖任何模块；
> - `api` 只依赖 `shared`；
> - `runtime` 依赖 `api`；
> - `engine` 依赖 `api` 和 `runtime`；
> - `starter` 依赖以上所有。
>
> 具体通过 Maven 依赖约束，加上代码审查时检查 package import。比如 `AgentGraph<C>` 泛型化就是为了避免 `api` 反向依赖 `engine` 的 `NodeContext`。"

### Q5：阶段一最大的技术难点是什么？

> "最大的难点是**状态转移的不可变设计**和**模块边界的划分**。
>
> 不可变设计初期会带来一些写法上的不习惯，比如每次变更都要 `state.withVariable(...)`，但它让 checkpoint 和重放变得非常简单。
>
> 模块边界方面，我们花了时间讨论 `AgentGraph` 应该放在 `api` 还是 `engine`。最终放在 `api` 并通过泛型化，保证它是纯编排契约，不耦合引擎上下文。"

---

## 五、如何把阶段一讲成项目亮点

### 亮点 1：自研状态图引擎

> "我没有直接调 LangGraph，而是基于对 Agent 执行原理的理解，自研了一个轻量状态图引擎。这个设计让我对 Agent 的暂停、恢复、重试机制有更深的掌控。"

### 亮点 2：策略可插拔架构

> "我把 ReAct 和 Plan-and-Execute 统一抽象成 `ExecutionStrategy` SPI。这意味着新增一种推理模式不需要改 Agent 主流程，符合开闭原则。"

### 亮点 3：企业级工程意识

> "项目一开始就考虑了租户隔离、工具风险等级、调用审计、Token 配额。虽然阶段一还是内存实现，但接口已经预留，后续接真实权限/配额系统成本很低。"

### 亮点 4：测试覆盖

> "42 个单元测试全部通过，包括状态图执行、策略路由、ReAct 循环、GuardRail 拦截等核心路径。重构过程中测试给了我很大信心。"

---

## 六、常见追问 & 延伸准备

面试官听到状态图 + 自研，很可能会往深了问，提前准备这些：

| 追问方向 | 准备要点 |
|---|---|
| 状态图和 LangGraph 具体区别 | 自研更轻量、可控；LangGraph 生态更完善 |
| 不可变对象性能怎么样 | 小对象复制开销低；大状态需要优化，可引入结构共享 |
| checkpoint 怎么持久化 | 可序列化 `AgentState` 到 Redis/DB，恢复时反序列化 |
| 怎么支持并行工具执行 | 状态图支持分支节点 + 聚合节点，后续阶段二实现 |
| 怎么接入真实 LLM | 已通过 Spring AI `ChatModel` 抽象，切换模型只需改配置 |
| 和 Spring AI 的关系 | CoreAgent 在 Spring AI 之上做编排和治理，不替代它 |

---

## 七、面试前 5 分钟速记

记住这三个数字 + 三个关键词：

**三个数字：**
- 5 个 Maven 子模块
- 42 个测试用例
- 2 个已实现策略（ReAct、Plan-and-Execute）

**三个关键词：**
- 状态图（State Graph）
- 策略 SPI（ExecutionStrategy）
- 不可变状态（Immutable AgentState）

---

## 八、一句话收尾

> "阶段一我们把 CoreAgent 从一个硬编码的 ReAct 原型，升级成了可扩展、可测试、可演进的 Agent 执行引擎。下一阶段会重点补齐 Function Calling、MCP 协议、RAG 链路和评估体系，向企业级 Java AI 中台演进。"
