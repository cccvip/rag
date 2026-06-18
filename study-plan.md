# Agent 工程师面试学习计划

## 原则
- 每个阶段有明确的验收标准，不靠"感觉学会了"
- 验收方式：口述（录音）或默写（画图），不看笔记
- 未通过验收的项目，补学后再验

---

## 第一阶段：CoreAgent 内化（2天）

### Day 1：架构 + 平台层模块

**学习内容**
- CoreAgent 整体架构（7 模块）
- ToolRegistry、PreProcessor、ContextManager 三个模块的设计

**验收标准**
| # | 验收项 | 方式 | 通过条件 |
|---|--------|------|---------|
| 1 | 七模块架构图 | 默画 | 5 分钟内画出完整架构图，标注每个模块是"纯平台"还是"平台+业务" |
| 2 | ToolRegistry | 口述 | 说清 CoreTool 4 个方法、ToolMeta 5 个字段、按场景隔离的原理 |
| 3 | PreProcessor | 口述 | 说清平台层（PreProcessorChain 路由）vs 业务层（具体预处理逻辑）的边界 |
| 4 | ContextManager | 口述 | 说清 Token 预算分配比例（20/50/30）、ContextStrategy 的作用、运维 vs RAG 的优先级差异 |
| 5 | 新场景接入流程 | 口述 | "接入一个运营数据查询场景，需要实现哪些接口"——4 个接口 + 1 个 Prompt，不改平台代码 |

**验收方式**：录音，每题限时 2 分钟。回听检查是否有遗漏或错误。

---

### Day 2：管控层 + 业务场景

**学习内容**
- GuardRail、TenantCtrl、AgentTracer、AgentExecutor
- 三个业务场景（运维自愈、RAG 问答、运营数据查询）

**验收标准**
| # | 验收项 | 方式 | 通过条件 |
|---|--------|------|---------|
| 6 | GuardRail | 口述 | 说清四级风险分级（LOW/MEDIUM/HIGH/CRITICAL）、RiskRule 业务层实现、与 LangGraph Human-in-the-loop 的区别 |
| 7 | TenantCtrl | 口述 | 说清三层管控（Token 配额 + QPS 限流 + 成本追踪）、为什么是纯平台 |
| 8 | AgentExecutor | 口述 | 说清 ReAct 循环流程（8 步：租户检查→初始化→获取工具→循环{构建上下文→调用LLM→安全检查→执行工具→预处理}→收尾） |
| 9 | 三个业务场景 | 默写 | 写出每个场景的 ToolSet（工具列表）、Prompt 核心约束、效果数据 |
| 10 | CoreAgent vs 流程引擎 | 口述 | 说清共同点（都是任务编排）和差异（确定性 vs 非确定性） |

**验收方式**：录音，每题限时 2 分钟。回听检查。

---

## 第二阶段：LangGraph 设计理解（2天）

### Day 3：LangGraph 核心概念

**学习资源**：LangGraph 官方文档 https://langchain-ai.github.io/langgraph/

**学习内容**
- State Graph（节点 + 边 + 条件路由）
- Checkpointer（状态持久化 + 断点恢复）
- Human-in-the-loop（任意步骤暂停）

**验收标准**
| # | 验收项 | 方式 | 通过条件 |
|---|--------|------|---------|
| 11 | State Graph | 默画 | 画出一个 LangGraph 的 ReAct 状态图（START→LLM→Tools→LLM→END），标注节点和边 |
| 12 | Checkpointer | 口述 | 说清 Checkpointer 的作用（断点恢复、时间旅行）、对比 CoreAgent 的 Redis Session 方案 |
| 13 | Human-in-the-loop | 口述 | 说清 LangGraph 在任意步骤暂停等人 vs CoreAgent 在执行前拦截的区别，各自的适用场景 |
| 14 | LangGraph vs CoreAgent 对比 | 默写 | 写出对比表（8 个维度：编排模型、状态持久化、人工介入、多 Agent、多租户、成本管控、工具预处理、运行时） |

**验收方式**：默写对比表 + 口述 3 个概念，每题限时 2 分钟。

---

### Day 4：多 Agent + 综合对比

**学习内容**
- LangGraph 多 Agent 模式（Supervisor、Handoff）
- 综合对比表整理
- "为什么不选 LangGraph" 标准答案

**验收标准**
| # | 验收项 | 方式 | 通过条件 |
|---|--------|------|---------|
| 15 | Supervisor 模式 | 默画 | 画出 Supervisor→Worker1/Worker2 的任务分配图，标注 Handoff 机制 |
| 16 | 多 Agent 对比 | 口述 | 说清 LangGraph 动态 Supervisor vs CoreAgent 固定流水线的优劣 |
| 17 | "为什么不选 LangGraph" | 口述 | 3 分钟内完整回答：三个约束（运行时、团队、场景）→ 承认不足 → 点明差异化价值 |
| 18 | "CoreAgent 比 LangGraph 差在哪" | 口述 | 诚实回答：编排能力弱（线性循环 vs 任意图）、没有 Checkpointer、Human-in-the-loop 只能执行前拦截 |

**验收方式**：录音，每题限时 3 分钟。重点检查 17 题是否"诚实 + 有逻辑"。

---

## 第三阶段：Spring AI 夯实（1天）

### Day 5：Spring AI 核心 API

**学习内容**
- Function Calling（@Tool 注解、ChatClient）
- Embedding API
- ChatMemory

**验收标准**
| # | 验收项 | 方式 | 通过条件 |
|---|--------|------|---------|
| 19 | Function Calling 流程 | 口述 | 说清从 @Tool 定义 → ChatClient 调用 → LLM 返回 function_call → 执行 → 返回结果的完整链路 |
| 20 | CoreTool vs @Tool | 口述 | 说清我们为什么不直接用 @Tool——需要 ToolMeta 元数据（风险等级、租户可见性、超时） |
| 21 | ChatMemory vs ContextManager | 口述 | 说清 Spring AI 的 ChatMemory 只管对话历史，CoreAgent 的 ContextManager 还管工具结果和 Token 预算分配 |
| 22 | "Spring AI 提供了什么，CoreAgent 补了什么" | 口述 | 3 分钟内完整回答，列出 Spring AI 的 3 个能力 + CoreAgent 补的 4 个能力 |

**验收方式**：录音，每题限时 2 分钟。

---

## 第四阶段：模拟面试（1天）

### Day 6：高频问题 + 追问演练

**验收标准**
| # | 验收项 | 方式 | 通过条件 |
|---|--------|------|---------|
| 23 | 5 个必问问题 | 录音 | 每题 3 分钟内完成，无长时间停顿（>10 秒），无夸大其词 |
| 24 | 追问应对 | 录音 | 每个问题随机抽 1 个追问回答，能给出具体数据或设计细节 |
| 25 | 不足应对 | 录音 | 被问到"CoreAgent 比 LangGraph 差在哪"时，诚实回答 + 给出改进方向 |

**5 个必问问题**：
1. "介绍一下你的 CoreAgent 架构"
2. "为什么选 Spring AI 不选 LangGraph"
3. "CoreAgent 跟 LangGraph 比怎么样"
4. "多租户隔离怎么做的"
5. "RAG 双轨检索架构"

**验收方式**：全程录音，回听检查。标注每个停顿点和不确定的回答，针对性补学。

---

## 学习资源

| 资源 | 用途 | 优先级 |
|------|------|--------|
| f:\rag\coreAgent.md | CoreAgent 设计文档 | 必读 |
| f:\rag\resume.md | 简历（面试底稿） | 必读 |
| f:\rag\answer.data.js | 32 题答案 | 必读 |
| LangGraph 官方文档 | State Graph、Checkpointer 概念 | Day 3-4 |
| Spring AI 官方文档 | Function Calling API | Day 5 |

---

## 每日检查

每天结束时回答以下问题（录音）：
1. 今天的验收项，哪些通过了？哪些没通过？
2. 没通过的原因是什么？（概念不清 / 表达不清 / 记不住）
3. 明天需要补学什么？
