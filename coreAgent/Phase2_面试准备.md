# CoreAgent 阶段二：面试应答指南

> 本文档对应阶段二实现：Function Calling、RAGAS 评估、标准 MCP Server、RAG 链路。
> 用于面试前快速复习和项目亮点包装。

---

## 一、一句话定位

**阶段二让 CoreAgent 从"能跑通的 Agent 引擎"升级为"具备企业级单 Agent 能力的中台雏形"：接入 Spring AI Function Calling、实现 LLM-as-a-Judge 评估、提供标准 MCP Server、搭建可插拔的 RAG 检索链路。**

面试开场可以这样讲：

> "阶段二我重点做了四件事：第一，把工具调用从字符串匹配升级到 Spring AI Function Calling；第二，用 LLM-as-a-Judge 实现了 RAGAS 简化版评估框架；第三，提供了符合 Anthropic MCP 协议的 SSE Server；第四，搭建了 RAG 文档存储 SPI 和向量检索工具。全量测试目前 50+，全部通过。"

---

## 二、阶段二核心成果

### 2.1 Spring AI Function Calling 接入

- `ToolFunctionCallback`：把 CoreAgent 的 `Tool` 适配成 Spring AI `FunctionCallback`
- `FunctionCallingExecutor`：驱动 LLM 多轮 Function Calling 循环
- `FunctionCallingStrategy`：作为 `ExecutionStrategy` SPI 的第三个实现
- 通过 `Agent.withStrategy(new FunctionCallingStrategy(...))` 即可切换

### 2.2 LLM-as-a-Judge 评估框架

- 参考 RAGAS 设计，Java 实现三个核心指标：
  - `faithfulness`：答案是否基于上下文（幻觉检测）
  - `answer_relevancy`：答案是否切题
  - `context_precision`：检索上下文是否相关
- `RagasEvaluator` 组合多个指标输出综合得分
- 基于 LLM 解析 `Score: x.xx\nReason: ...` 格式

### 2.3 标准 MCP Server（SSE 传输）

- `McpSseController`：提供 `/mcp/sse` 和 `/mcp/message`
- `McpMessageHandler`：处理标准 JSON-RPC：`initialize`、`tools/list`、`tools/call`
- 符合 Anthropic MCP 协议规范，外部 MCP Client 可接入
- 与原有自定义 `/mcp/tools/list`、`/mcp/tools/call` 并存

### 2.4 RAG 检索链路

- `Document` / `DocumentStore` / `EmbeddingService` SPI
- `InMemoryDocumentStore`：余弦相似度检索，用于测试和开发
- `SpringAiEmbeddingService`：对接 Spring AI `EmbeddingModel`
- `VectorRetrieverTool`：把检索结果格式化为 `[doc-xxx] content`
- 架构上 Qdrant / Milvus / ES 可通过实现 `DocumentStore` 接入

### 2.5 验证

```bash
mvn clean test
# 全量测试通过，新增 8+ 个测试用例
```

---

## 三、关键技术决策 & 为什么这么做

### 3.1 为什么要接入 Function Calling？

**标准回答：**

> "之前工具调用是字符串匹配：LLM 输出 toolName + input，我们再手动解析执行。这种方式有几个问题：
> 1. **不标准**：每家 LLM 输出格式不一样，维护成本高；
> 2. **不稳定**：LLM 可能输出非法 JSON 或 hallucinate 工具名；
> 3. **不支持工具 schema**：无法让 LLM 知道每个工具需要什么参数。
>
> Function Calling 让 LLM 在协议层面理解工具定义，输出结构化的 function call，我们更可控，也更容易对接 OpenAI、DeepSeek、Qwen 等模型。"

### 3.2 为什么自研 RAGAS 而不是直接用 Python 版？

**标准回答：**

> "RAGAS 官方是 Python 库，但我们的中台是 Java。为了把评估能力嵌入到 CI/CD 和 Java 服务里，我参考 RAGAS 指标设计，用 Java + LLM-as-a-Judge 实现了一个简化版。
>
> 核心思路是一样的：用 LLM 当裁判，评估 faithfulness、answer_relevancy、context_precision。未来如果需要更复杂的指标，可以对接 Python 评估服务，但基础评估在 Java 侧先跑起来。"

### 3.3 为什么同时保留自定义 MCP Gateway 和标准 MCP Server？

**标准回答：**

> "自定义 MCP Gateway 是我们内部业务服务的工具路由层，负责租户隔离、协议转换、服务发现。
>
> 标准 MCP Server 是为了让外部 MCP Client（比如 Claude Desktop、Cursor）能发现我们的工具。两者是互补关系：
> - 内部微服务走 Gateway；
> - 外部客户端走标准 MCP SSE。
>
> 这也符合我们'企业级中台'的定位：对内标准化治理，对外标准化协议。"

### 3.4 RAG 为什么先做 SPI + 内存版？

**标准回答：**

> "RAG 链路里变化最快的是向量数据库选型。我先定义了 `DocumentStore` 和 `EmbeddingService` SPI，把检索逻辑和存储实现解耦。
>
> 内存版用于快速验证和单元测试；生产环境可以无缝替换为 Qdrant、Milvus、Elasticsearch，业务代码不需要改。"

---

## 四、高频面试问题 & 标准回答

### Q1：阶段二对应哪些招聘要求？

> "主要覆盖：
> 1. Function Calling / 结构化工具调用；
> 2. RAG 完整链路（Embedding、向量检索、引用格式化）；
> 3. Agent 评估体系（RAGAS / LLM-as-a-Judge）；
> 4. MCP 标准协议接入；
> 5. 企业级工程化（SPI 解耦、多模块、测试覆盖）。"

### Q2：Function Calling 在你的项目里是怎么落地的？

> "我做了三层：
> 1. **适配层**：`ToolFunctionCallback` 把内部 `Tool` 包装成 Spring AI 的 `FunctionCallback`；
> 2. **执行层**：`FunctionCallingExecutor` 负责多轮调用循环，LLM 返回 tool call 就执行工具，返回最终结果就结束；
> 3. **策略层**：`FunctionCallingStrategy` 作为 `ExecutionStrategy` 实现，可以通过 `Agent.withStrategy()` 切换。
>
> 这样既能用 Spring AI 的 Function Calling 能力，又保留了我们自己的状态图和 Agent 入口。"

### Q3：你们的 RAGAS 评估怎么用？

> "调用 `RagasEvaluator.evaluate(query, answer, contexts)`，它会并行调用三个 LLM-as-a-Judge 评估器：
> - Faithfulness：检查答案是否都能在 context 中找到依据；
> - Answer Relevancy：检查答案是否回答了问题；
> - Context Precision：检查检索到的 context 是否相关。
>
> 最终返回每个指标的得分、理由和综合平均分。我们可以在单元测试里设阈值，比如 0.8 以下认为不达标。"

### Q4：MCP Server 支持哪些标准方法？

> "目前支持三个核心 JSON-RPC 方法：
> - `initialize`：协议握手，返回 protocolVersion 和 serverInfo；
> - `tools/list`：返回工具列表和 JSON Schema；
> - `tools/call`：调用指定工具并返回结果。
>
> 传输层用 SSE：`/mcp/sse` 建立连接，`/mcp/message` 提交请求。"

### Q5：RAG 链路里如果要把内存版换成 Qdrant，要改多少代码？

> "几乎不用改业务代码。只要实现 `DocumentStore` 接口的 Qdrant 版本，然后在配置里替换 bean 即可。
>
> `VectorRetrieverTool` 只依赖 `DocumentStore` 接口，不关心底层是内存、Qdrant 还是 Milvus。"

---

## 五、如何把阶段二讲成项目亮点

### 亮点 1：Function Calling 标准化工具调用

> "阶段二我们把工具调用从'字符串解析'升级到了'Spring AI Function Calling'。这意味着 LLM 自己决定调用哪个工具、传什么参数，我们只需要按标准协议执行。这大大降低了工具调用的不稳定性和维护成本。"

### 亮点 2：有评估体系的 Agent 中台

> "很多项目只关心 Agent 能不能跑通，我们不只跑通，还建立了 RAGAS 简化版评估。面试时我可以展示如何用 LLM-as-a-Judge 检测幻觉、评估回答相关性。"

### 亮点 3：标准协议双轨制

> "我们同时支持内部自定义 MCP Gateway 和外部标准 MCP SSE Server。对内做治理，对外做兼容，这是企业级中台应有的设计。"

### 亮点 4：可插拔 RAG 架构

> "RAG 链路通过 `DocumentStore` SPI 解耦，内存版用于开发测试，生产可替换为 Qdrant。这种设计避免了业务代码和向量数据库的强绑定。"

---

## 六、常见追问 & 延伸准备

| 追问方向 | 准备要点 |
|---|---|
| Function Calling 和 ReAct 怎么选？ | Function Calling 适合工具定义清晰的场景；ReAct 适合需要显式 reasoning 的场景 |
| RAGAS 指标怎么设计 prompt？ | 用 `Score: x.xx\nReason: ...` 格式，让 LLM 打分并给出理由 |
| MCP 和 A2A 什么关系？ | MCP 是模型↔工具，A2A 是 Agent↔Agent，互补 |
| 为什么还没接真实 Embedding 模型？ | SPI 已预留，`SpringAiEmbeddingService` 已对接 Spring AI，换模型只需改配置 |
| Qdrant 实现计划是什么？ | 实现 `DocumentStore` 接口，用 Qdrant Java client 做 collection 管理和向量搜索 |

---

## 七、面试前 5 分钟速记

记住这三个数字 + 三个关键词：

**三个数字：**
- 3 个 ExecutionStrategy 实现（ReAct、Plan-and-Execute、Function Calling）
- 3 个 RAGAS 指标（faithfulness、answer_relevancy、context_precision）
- 50+ 个测试用例

**三个关键词：**
- Function Calling
- LLM-as-a-Judge
- MCP Server

---

## 八、诚实地讲清当前边界

阶段二虽然完成了核心能力，但仍有明确边界，面试时不要夸大：

| 能力 | 当前状态 | 说明 |
|---|---|---|
| Function Calling | ✅ 已接入 | 可通过 `FunctionCallingStrategy` 使用 |
| RAGAS 评估 | ✅ Java 简化版 | 覆盖 3 个核心指标 |
| MCP Server | ✅ SSE 基础版 | 支持 initialize/tools/list/tools/call |
| RAG 向量存储 | ⚠️ 内存版 + SPI | Qdrant/Milvus 实现可插拔，待生产接入 |
| HITL 人工审批 | ❌ 未实现 | 阶段三 |
| 持久化 Memory | ❌ 未实现 | 阶段三 |

---

## 九、一句话收尾

> "阶段二我们把 CoreAgent 从'能跑'升级到了'可评估、可协议化、可插拔'。Function Calling 让工具调用标准化，RAGAS 让回答质量可量化，MCP Server 让外部系统能接入，RAG SPI 让向量数据库可替换。下一步阶段三重点是 Multi-Agent 协同和 HITL。"
