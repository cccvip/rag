# CoreAgent Memory 设计（第一版）

> 本文档定义 CoreAgent 中台服务层的 Memory 模块第一版。  
> 当前版本聚焦 **工作记忆（Working Memory）**，面向 **QA 助手** 场景，支持多轮会话。

---

## 一、背景与目标

### 1.1 为什么要做 Memory

当前 CoreAgent 的 `AgentExecutor` 在执行 ReAct 循环时，仅依靠单轮 Prompt 内的 `history` 字符串维持上下文。这种方式存在明显问题：

- **无法跨请求保留状态**：每次调用 `Agent.run()` 都是独立的，历史不会自动继承
- **上下文容易撑爆**：随着轮数增加，history 线性增长，最终超出模型上下文窗口
- **无法区分会话**：多个用户/会话的历史会混在一起
- **检索重复**：多轮 QA 中，相同或相关的问题会重复调用 Retriever

### 1.2 第一版目标

- 支持 **QA 助手** 的多轮对话场景
- 提供 **工作记忆** 能力：保存当前任务执行过程中的关键信息
- 不引入外部存储依赖，先用内存实现
- 为后续长期记忆、语义检索、租户隔离留下扩展接口

### 1.3 非目标

- 第一版不做长期记忆（跨会话持久化）
- 第一版不做语义检索（向量召回历史）
- 第一版不做复杂记忆压缩/摘要

---

## 二、工作记忆的定义

在本版本中，**工作记忆** 指的是：

> 在当前会话（Session）内，维护用户与 Agent 的多轮交互记录，并在每次调用 LLM 时，把相关历史以合理形式组装进 Prompt。

它包含两类信息：

| 信息类型 | 说明 | 示例 |
|:---|:---|:---|
| **对话历史** | 用户问题和 Agent 回答的完整记录 | `User: A 工厂疏散预案` / `Assistant: ...` |
| **任务中间状态** | ReAct 循环中的 Thought/Action/Observation | `Thought: 需要检索...` / `Action: retriever` |

### 2.2 存储方案选型

Memory 的存储介质选择，直接决定了能否从原型走向生产。

| 方案 | 适用阶段 | 优点 | 缺点 |
|:---|:---|:---|:---|
| **内存** | 原型 / 单实例 demo | 简单、无依赖 | 重启丢失、无法扩缩容、无持久化 |
| **Redis** | 生产短期记忆 | 快、共享、支持 TTL | 内存贵、不适合长期大量存储 |
| **数据库** | 生产长期记忆 / 审计 | 持久、便宜、可查询 | 比 Redis 慢 |
| **Redis + 数据库** | 生产完整方案 | 各取所长 | 架构稍复杂 |

**推荐方案：Redis + 数据库分层。**

- **Redis**：存放当前会话的短期工作记忆，设置 TTL（如 30 分钟）
- **数据库**：存放长期记忆、完整审计日志、用户画像

这样既能保证多轮对话的响应速度，又能满足持久化和审计需求。

---

## 三、QA 助手场景分析

### 3.1 典型多轮对话

```text
User:  A 工厂的化学品泄漏预案是什么？
Agent: A 工厂化学品泄漏预案包括 ...

User:  那火灾预案呢？
Agent: （需要知道"那"指 A 工厂）A 工厂火灾预案包括 ...

User:  上周演练了吗？
Agent: （需要知道"演练"指 A 工厂的应急演练）A 工厂上周进行了火灾疏散演练 ...
```

### 3.2 没有 Memory 的问题

第三轮如果没有记忆，Agent 会反问：

```text
Agent: 请问您指的是哪家工厂的演练？
```

这会严重降低用户体验。

### 3.3 有 Memory 的收益

1. **指代消解**：理解"那"、"这家工厂"等代词
2. **减少重复检索**：相同主题的历史文档可以直接复用
3. **连贯性**：保持话题一致性，避免每轮都从零开始
4. **可追溯**：可以看到完整对话脉络

---

## 四、MemoryManager 设计

### 4.1 接口定义

```java
public interface MemoryManager {

    /**
     * 保存一条消息到指定会话。
     *
     * @param sessionId 会话 ID
     * @param role      角色：user / assistant / system / observation
     * @param content   消息内容
     */
    void save(String sessionId, String role, String content);

    /**
     * 获取指定会话的历史消息，用于构造 Prompt。
     *
     * @param sessionId 会话 ID
     * @param maxTokens 最大 Token 数限制
     * @return 按时间顺序排列的消息列表
     */
    List<MemoryMessage> getHistory(String sessionId, int maxTokens);

    /**
     * 清空指定会话的记忆。
     */
    void clear(String sessionId);
}
```

### 4.2 消息模型

```java
public class MemoryMessage {
    private final String role;       // user / assistant / system / observation
    private final String content;    // 内容
    private final long timestamp;    // 时间戳
    private final int tokenCount;    // 估算的 Token 数

    // getters...
}
```

### 4.3 第一版实现

> **注意**：本实现仅用于原型验证。生产环境请使用 `RedisMemoryStore` 或 `DatabaseMemoryStore`，详见第 7 节。

实际代码按「存储层 + 管理层」分层，便于后续替换后端：

#### `MemoryStore` 与 `InMemoryMemoryStore`

```java
public interface MemoryStore {
    void save(String sessionId, MemoryMessage message);
    List<MemoryMessage> getHistory(String sessionId, int maxTokens);
    void clear(String sessionId);
}

public class InMemoryMemoryStore implements MemoryStore {
    private final Map<String, List<MemoryMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void save(String sessionId, MemoryMessage message) {
        store.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
             .add(message);
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        List<MemoryMessage> messages = store.getOrDefault(sessionId, Collections.emptyList());
        return trimByTokens(messages, maxTokens);
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }

    protected List<MemoryMessage> trimByTokens(List<MemoryMessage> messages, int maxTokens) {
        List<MemoryMessage> result = new ArrayList<>();
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MemoryMessage msg = messages.get(i);
            if (total + msg.getTokenCount() > maxTokens) {
                break;
            }
            total += msg.getTokenCount();
            result.add(0, msg);
        }
        return result;
    }
}
```

#### `InMemoryMemoryManager`

```java
public class InMemoryMemoryManager implements MemoryManager {

    private final MemoryStore store;
    private final TokenCountEstimator tokenCountEstimator;
    private final int defaultMaxTokens;

    public InMemoryMemoryManager(MemoryStore store, TokenCountEstimator tokenCountEstimator) {
        this(store, tokenCountEstimator, 2000);
    }

    public InMemoryMemoryManager(MemoryStore store, TokenCountEstimator tokenCountEstimator,
                                 int defaultMaxTokens) {
        this.store = store;
        this.tokenCountEstimator = tokenCountEstimator;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @Override
    public void save(String sessionId, String role, String content) {
        int tokens = tokenCountEstimator.estimate(content);
        MemoryMessage message = new MemoryMessage(role, content, System.currentTimeMillis(), tokens);
        store.save(sessionId, message);
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        return store.getHistory(sessionId, maxTokens);
    }

    @Override
    public void clear(String sessionId) {
        store.clear(sessionId);
    }
}
```

使用时注入 Tokenizer 和 Store：

```java
// Spring AI 提供的基于 jtokkit 的 Tokenizer
MemoryStore memoryStore = new InMemoryMemoryStore();
TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();
MemoryManager memoryManager = new InMemoryMemoryManager(memoryStore, tokenCountEstimator, 2000);
```

### 4.4 与 Agent 的集成

当前 `Agent.run(query)` 接收的是单条 query。引入 Memory 后，应该改为：

```java
public String run(String sessionId, String query) throws Exception {
    // 1. 保存用户问题
    memoryManager.save(sessionId, "user", query);

    // 2. 获取历史
    List<MemoryMessage> history = memoryManager.getHistory(sessionId, maxHistoryTokens);

    // 3. 组装 Prompt（历史 + 当前问题）
    Prompt prompt = buildPrompt(history, query);

    // 4. ReAct 循环 ...

    // 5. 保存最终答案
    memoryManager.save(sessionId, "assistant", finalAnswer);

    return finalAnswer;
}
```

### 4.5 与 ContextManager 的关系

`MemoryManager` 负责**存取历史**，`ContextManager` 负责**把历史拼进当前 Prompt 并控制总长度**。

当前 `core-agent` 已实现 `ContextManager`，它把历史消息转换为 `MessageBlock`，再按 `ContextStrategy` 定义的优先级裁剪。

```text
User Query
    ↓
MemoryManager.getHistory(sessionId)  →  List<MemoryMessage>
    ↓
toMessageBlocks()                    →  List<MessageBlock>
    ↓
ContextManager.assemble(systemPrompt, blocks, contextStrategy, maxTokens)
    ↓
Prompt 传给 LLM
```

`MessageBlock` 类型：
- `SYSTEM_PROMPT`：System Prompt，最高优先级
- `USER_QUESTION`：用户问题
- `ASSISTANT_ANSWER`：Agent 最终答案
- `TOOL_RESULT`：Tool 执行结果 / Observation
- `THOUGHT`：ReAct 中间推理
- `SYSTEM_META`：Evaluation / Reflection 等元信息

如果历史太长，`ContextManager` 会：
1. 先扣除 `System Prompt` 占用的 Token，得到内容块可用预算
2. 按 `ContextStrategy.priority()` 从低到高裁剪（数字越小越优先保留）
3. 同优先级内优先丢弃旧的内容
4. 最终按原始时间顺序拼接上下文，保证 LLM 阅读顺序正确

不同场景的优先级示例：
- **通用 QA（`DefaultContextStrategy`）**：System Prompt > 用户问题 > Assistant 答案 > Tool 结果 > 元信息 > Thought
- **RAG（`RagContextStrategy`）**：System Prompt > Tool 结果（检索文档）> 用户问题 > Assistant 答案 > 元信息 > Thought
- **运维（`OpsContextStrategy`）**：System Prompt > Tool 结果（日志/指标）> 用户问题 > Thought > Assistant 答案 > 元信息

---

## 五、多轮 QA 中的 ReAct 与 Memory

### 5.1 第一轮：新问题

```text
User: A 工厂的化学品泄漏预案是什么？

Memory:
  [user] A 工厂的化学品泄漏预案是什么？

Agent ReAct:
  Thought: 需要检索 A 工厂化学品泄漏预案
  Action: retriever
  Action Input: A 工厂 化学品泄漏预案
  Observation: [doc-1001] ...
  Thought: 可以生成答案了
  Action: writer
  Action Input: ...
  Final Answer: A 工厂化学品泄漏预案包括 ...

Memory after:
  [user] A 工厂的化学品泄漏预案是什么？
  [assistant] A 工厂化学品泄漏预案包括 ...
  [tool] retriever: [doc-1001] ...
```

### 5.2 第二轮：指代消解

```text
User: 那火灾预案呢？

Memory:
  [user] A 工厂的化学品泄漏预案是什么？
  [assistant] A 工厂化学品泄漏预案包括 ...
  [tool] retriever: [doc-1001] ...
  [user] 那火灾预案呢？

Agent ReAct:
  Thought: 用户问"那火灾预案"，结合上下文，应该指 A 工厂的火灾预案
  Action: retriever
  Action Input: A 工厂 火灾预案
  ...
  Final Answer: A 工厂火灾预案包括 ...
```

### 5.3 第三轮：跨轮状态

```text
User: 上周演练了吗？

Memory:
  [user] A 工厂的化学品泄漏预案是什么？
  [assistant] A 工厂化学品泄漏预案包括 ...
  [tool] retriever: A 工厂 化学品泄漏预案 → [doc-1001]
  [user] 那火灾预案呢？
  [assistant] A 工厂火灾预案包括 ...
  [tool] retriever: A 工厂 火灾预案 → [doc-1002]
  [user] 上周演练了吗？

Agent ReAct:
  Thought: 用户问"上周演练了吗"，结合上下文指 A 工厂的应急演练
  Action: retriever
  Action Input: A 工厂 上周 应急演练
  ...
```

---

## 六、关键设计决策

### 6.1 为什么第一版用内存实现

- 简单，不引入 Redis/DB 依赖
- 适合原型验证和单实例部署
- 后续可以无缝替换为 `RedisMemoryManager` 或 `DatabaseMemoryManager`

### 6.2 为什么消息分 role

- 便于后续按角色筛选（比如只保留 user/assistant，丢弃 tool 细节）
- 与 OpenAI / Spring AI 的消息格式对齐
- 支持更灵活的记忆策略

### 6.3 Token 估算为什么用 Spring AI Tokenizer

- Spring AI 提供 `TokenCountEstimator` 接口，底层可集成 `jtokkit`（OpenAI tiktoken 的 Java 实现）
- 对 OpenAI 协议模型，估算结果较准确；对 DeepSeek 等兼容模型，接近但可能有微小偏差
- 比简单的 `content.length() / 4` 更可靠，避免上下文窗口管理出现偏差
- 实际成本统计仍应以模型 API 返回的 `Usage` 为准

### 6.4 要不要存 ReAct 中间状态

**要存，但可以压缩。**

完整保存每一轮 Thought/Action/Observation 会让历史很快膨胀。建议：

- 第一轮完整保存，帮助模型理解推理过程
- 后续轮次可以只保存：
  - 用户问题
  - Agent 最终答案
  - 关键 Tool 调用结果（如检索到的 doc-id）
- 中间 Thought 可以丢弃，因为答案已经包含了结论

当前 `core-agent` 的实现中，为了演示和审计，**每一轮 ReAct 都会以 `observation` 角色保存一条结构化记录**，格式如下：

```text
[ReAct Step N]
Thought: ...
Action: ...
Action Input: ...
Observation: ...
Evaluation: OK: result accepted.
```

由于 `MemoryManager` 已经按 `maxTokens` 裁剪历史，保存完整 ReAct 不会无限制占用上下文；若历史过长，最旧的消息会被自动丢弃。

### 6.5 中间结果的评判

Agent 不仅要执行 Tool，还要对每一步结果做评判，否则无法判断是否需要重试、换工具或终止循环。

当前 `core-agent` 在 `Agent.evaluateStep(...)` 中实现了规则化评判：

| 场景 | 评判结果 | 说明 |
|:---|:---|:---|
| 工具不存在 | `FAIL: tool 'xxx' not found` | 提示 LLM 选择合法工具 |
| 被 GuardRail 拦截 | `BLOCKED: tool 'xxx' is HIGH/CRITICAL` | 提示换用低风险工具 |
| 工具执行报错 | `FAIL: execution error` | 建议重试或换输入 |
| 重复动作 | `WARN: repeated action 'xxx'` | 避免死循环，建议换策略 |
| 检索无结果 | `WARN: no documents retrieved` | 建议改写查询 |
| 正常 | `OK: result accepted` | 继续下一步 |

评判结果会：
1. 打印到控制台用于调试
2. 追加到当前轮 Prompt，让 LLM 在下一步决策时参考
3. 以 `Evaluation:` 字段写入 `observation` 角色的记忆消息，供后续轮次和审计复盘

```text
[ReAct Step N]
Thought: ...
Action: ...
Action Input: ...
Observation: ...
Evaluation: OK: result accepted.
```

在生产环境中，评判可以进一步升级为：
- **模型化评判**：再用一次 LLM 判断结果是否满足用户意图
- **检索质量评分**：计算召回文档与问题的相关性
- **引用一致性检查**：确保 Observation 中的 doc-id 与最终答案引用一致

### 6.6 Reflection：自我检查与回溯

**Reflection（反思）** 是指 Agent 在执行完一步后，主动停下来检查自己的推理和结果是否正确；如果发现错误，就回溯并修正下一步策略。

它和 6.5 节的 `evaluateStep` 的区别：

| | `evaluateStep` | `Reflection` |
|:---|:---|:---|
| 执行者 | 代码规则 | LLM 自身 |
| 能力 | 只能按固定规则判断 | 可以理解语义，发现更隐蔽的错误 |
| 成本 | 无额外 LLM 调用 | 每步可能多一次 LLM 调用 |
| 用途 | 快速拦截常见错误 | 深度检查推理质量 |

#### 为什么需要 Reflection

假设 Agent 已经执行了以下步骤：

```text
Thought: 用户问化学泄漏，我要检索。
Action: retriever
Action Input: chemical spill
Observation: [doc-1001] ...
Evaluation: OK: result accepted.
```

`evaluateStep` 认为没问题，但人类可能发现：检索词 `chemical spill` 太宽泛，召回的文档不针对「应急处理流程」。这种**语义层面的错误**需要 LLM 自己反思才能发现。

#### 当前实现

`core-agent` 在 `Agent.reflect(...)` 中实现了一个轻量版 Reflection：

- **触发条件**：`evaluateStep` 结果不是 `OK`（即 FAIL / BLOCKED / WARN）
- **输入**：上一步的 Thought / Action / Action Input / Observation / Evaluation
- **输出**：`Reflection: [问题分析 + 修正建议]`
- **后续**：Reflection 被追加到当前轮 Prompt，也被保存到 Memory

示例：

```text
Thought: I will call a tool.
Action: unknown_tool
Action Input: test
Observation: Error: tool 'unknown_tool' not found.
Evaluation: FAIL: tool 'unknown_tool' not found. Please choose a valid tool.
Reflection: The tool 'unknown_tool' does not exist. I should use 'retriever'.
```

#### 什么是“回溯重做”

当前 demo 的回溯是**逻辑层面**的，不是状态机层面的：

1. Agent 发现上一步错了（通过 Evaluation + Reflection）
2. 把错误原因和修正建议写进 Prompt
3. 下一轮 LLM 基于这些反馈，选择新的 Action
4. 相当于**用新的 Thought/Action 覆盖了错误的方向**

真正的状态回溯需要保存每一步的完整状态（上下文、工具输出、记忆等），出错时回退到某个检查点重新执行。生产级系统可以维护一个 `ReActStep` 栈：

```java
Stack<ReActStep> stepStack;

// 发现错误时
stepStack.pop(); // 回退到上一步
// 用 Reflection 建议重新生成 Action
```

#### Reflection 是否一定需要？

**不一定。** Reflection 本质上是拿一次额外的 LLM 调用换取更深的错误分析能力。是否要开，取决于场景和成本：

| 场景 | 是否需要 Reflection | 原因 |
|:---|:---|:---|
| 简单错误（工具不存在、被拦截、执行报错） | **不需要** | `evaluateStep` 已经把错误原因写进 Prompt，LLM 自己就能修正 |
| 重复动作 / 空检索 | **可选** | Evaluation 的 WARN 信息通常足够，Reflection 可以给出更具体的改写建议 |
| 语义偏差（检索对了但理解错） | **需要** | 规则发现不了，必须用 LLM 自我检查 |
| 最终答案质检 | **建议开启** | 在输出 Final Answer 前做一次 Reflection，检查引用、完整性 |
| 成本敏感 / 延迟敏感 | **关闭** | 每步省一次 LLM 调用，延迟和费用显著下降 |

当前 `core-agent` 通过 `enableReflection` 参数控制是否开启：

```java
// 开启 Reflection（默认）
Agent agent = new Agent(chatModel, registry, guardRail, metrics, memoryManager, 5,
        "tenant-A", "user-001", 2000, 60, 30, 2, true);

// 关闭 Reflection，仅保留 Evaluation
Agent agent = new Agent(chatModel, registry, guardRail, metrics, memoryManager, 5,
        "tenant-A", "user-001", 2000, 60, 30, 2, false);
```

关闭 Reflection 后：
- 不会调用 `Agent.reflect(...)`
- 不会生成 `Reflection:` 字段
- 但 `Evaluation:` 仍然保留，LLM 仍能看到失败/警告信息并自行调整

#### 生产环境建议

- **按需触发**：只在 Evaluation 非 OK 或最终答案前触发 Reflection，避免每步都调用 LLM
- **分级反思**：
  - 快速反思：用规则判断（当前 evaluateStep）
  - 深度反思：用 LLM 判断语义是否正确
- **保存反思记录**：用于后续微调 Agent、分析错误模式
- **设置回溯上限**：避免无限反思循环，最多允许 N 次回溯

### 6.7 超时与错误处理

生产环境必须给 LLM 调用和 Tool 执行都加上**超时控制**和**失败重试**，否则一个慢请求或异常就可能卡住整个 Agent。

#### 当前实现

`core-agent` 在 `Agent` 中增加了三个可配置参数：

| 参数 | 默认值 | 说明 |
|:---|:---|:---|
| `llmTimeoutSeconds` | 60 | 单次 LLM 调用超时 |
| `toolTimeoutSeconds` | 30 | 单次 Tool 执行超时 |
| `maxRetries` | 2 | LLM 调用失败后的最大重试次数 |

核心方法：

```java
private ChatResponse callLlmWithTimeoutAndRetry(Prompt prompt) throws Exception {
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            return CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
                    .orTimeout(llmTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            // 记录日志、判断是否为超时、决定是否重试
        }
    }
    throw new Exception("LLM call failed after " + (maxRetries + 1) + " attempts", ...);
}

private String executeToolWithTimeout(Tool tool, String input) throws Exception {
    return CompletableFuture.supplyAsync(() -> tool.execute(input))
            .orTimeout(toolTimeoutSeconds, TimeUnit.SECONDS)
            .join();
}
```

#### 错误处理策略

| 场景 | 处理 | 结果 |
|:---|:---|:---|
| LLM 调用超时 | 重试，超过重试次数后返回错误信息 | `LLM call failed: LLM call timed out after 60 seconds` |
| LLM API 异常 | 重试，超过重试次数后返回错误信息 | `LLM call failed: API rate limit exceeded` |
| Tool 执行超时 | 标记为失败，Observation 返回超时错误 | `Error: tool execution failed: TimeoutException` |
| Tool 执行异常 | 标记为失败，Observation 返回异常信息 | `Error: tool execution failed: ...` |
| 输出格式非法 | 作为错误 Observation 返回，让 LLM 下一步修正 | `Error: tool '' not found` |

这些错误都会：
1. 被 `evaluateStep` 评判为 FAIL
2. 可能触发 Reflection
3. 写入 Memory，作为审计和复盘依据
4. 影响 `MetricsTracker` 中的任务成功率和工具调用成功率

#### 生产环境建议

- **分级超时**：简单 Tool（如本地检索）5 秒，复杂 Tool（如调用外部 API）30 秒，LLM 60~120 秒
- **指数退避重试**：LLM 限流时，第 1 次等 1 秒，第 2 次等 2 秒，第 3 次等 4 秒
- **熔断降级**：连续失败 N 次后，直接返回兜底答案或转人工
- **工具隔离**：每个 Tool 在独立线程/协程中执行，避免一个 Tool 挂掉拖垮 Agent
- **链路追踪**：把每次 LLM 调用、Tool 执行的耗时和错误码上报到监控系统

---

## 七、生产环境存储层设计

第一版使用内存实现只是为了跑通流程。进入生产环境后，应该抽象出 `MemoryStore` 接口，并支持多种存储后端。

### 7.1 MemoryStore 接口

```java
public interface MemoryStore {

    void save(String sessionId, MemoryMessage message);

    List<MemoryMessage> getHistory(String sessionId, int maxTokens);

    void clear(String sessionId);
}
```

### 7.2 分层存储架构

```text
┌─────────────────────────────────────┐
│           AgentExecutor              │
│         （调用 MemoryManager）        │
└─────────────┬───────────────────────┘
              │
    ┌─────────┴─────────┐
    ▼                   ▼
┌──────────┐      ┌────────────┐
│  Redis   │      │  Database  │
│ 短期记忆  │      │  长期记忆   │
│ (会话)   │      │ (审计/画像) │
└──────────┘      └────────────┘
```

### 7.3 不同数据存哪里

| 数据类型 | 存储 | TTL | 说明 |
|:---|:---|:---|:---|
| 当前会话历史 | Redis | 30 分钟 ~ 24 小时 | 多轮对话快速访问 |
| ReAct 中间状态 | Redis | 随会话过期 | 临时工作记忆 |
| 用户高频问题缓存 | Redis | 较短 | 减少重复检索 |
| 跨会话用户画像 | Database | 长期 | 用户偏好、关注点 |
| 完整问答日志 | Database | 长期 | 审计、分析、合规 |

### 7.4 实现类示例

```java
// 短期记忆：Redis
public class RedisMemoryStore implements MemoryStore {
    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    @Override
    public void save(String sessionId, MemoryMessage message) {
        String key = "coreagent:memory:" + sessionId;
        redisTemplate.opsForList().rightPush(key, serialize(message));
        redisTemplate.expire(key, ttl);
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        String key = "coreagent:memory:" + sessionId;
        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);
        // 反序列化并按 token 裁剪
        return trimByTokens(deserialize(messages), maxTokens);
    }

    @Override
    public void clear(String sessionId) {
        redisTemplate.delete("coreagent:memory:" + sessionId);
    }
}
```

```java
// 长期记忆：数据库
public class DatabaseMemoryStore implements MemoryStore {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void save(String sessionId, MemoryMessage message) {
        jdbcTemplate.update(
            "INSERT INTO agent_memory (session_id, role, content, token_count, tenant_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            sessionId, message.getRole(), message.getContent(),
            message.getTokenCount(), getCurrentTenantId(), new Timestamp(System.currentTimeMillis())
        );
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        // 按时间倒序查询最近 N 条，再按 token 裁剪
        List<MemoryMessage> history = jdbcTemplate.query(
            "SELECT * FROM agent_memory WHERE session_id = ? ORDER BY created_at DESC LIMIT ?",
            new MemoryMessageRowMapper(), sessionId, 100
        );
        Collections.reverse(history);
        return trimByTokens(history, maxTokens);
    }

    @Override
    public void clear(String sessionId) {
        jdbcTemplate.update("DELETE FROM agent_memory WHERE session_id = ?", sessionId);
    }
}
```

### 7.5 多租户隔离

生产环境必须在存储层做好租户隔离：

- **Redis**：key 前缀加 `tenant:{tenantId}:`
  ```
  coreagent:memory:tenant:A:session:123
  ```
- **数据库**：表加 `tenant_id` 字段，所有查询带 `WHERE tenant_id = ?`

### 7.6 CompositeMemoryManager

也可以把短期和长期记忆组合成一个 `MemoryManager`：

```java
public class CompositeMemoryManager implements MemoryManager {
    private final MemoryStore shortTermStore;   // Redis
    private final MemoryStore longTermStore;    // Database
    private final TokenCountEstimator tokenCountEstimator;

    @Override
    public void save(String sessionId, String role, String content) {
        MemoryMessage message = new MemoryMessage(role, content,
                System.currentTimeMillis(), tokenCountEstimator.estimate(content));

        // 短期记忆一定保存
        shortTermStore.save(sessionId, message);

        // 长期记忆只保存关键信息（用户问题 + 最终回答）
        if ("user".equals(role) || "assistant".equals(role)) {
            longTermStore.save(sessionId, message);
        }
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        // 优先从 Redis 读，命中快；未命中再查数据库
        List<MemoryMessage> history = shortTermStore.getHistory(sessionId, maxTokens);
        if (history.isEmpty()) {
            history = longTermStore.getHistory(sessionId, maxTokens);
        }
        return history;
    }

    @Override
    public void clear(String sessionId) {
        shortTermStore.clear(sessionId);
        // 长期记忆一般不清除，只清短期
    }
}
```

## 八、与现有 CoreAgent 模块的关系

```text
┌─────────────────────────────────────────┐
│              AgentExecutor              │
│         （调用 MemoryManager）           │
└─────────────────┬───────────────────────┘
                  │
    ┌─────────────┼─────────────┐
    ▼             ▼             ▼
┌────────┐   ┌──────────┐  ┌───────────┐
│Memory  │   │Context   │  │ Tool      │
│Manager │   │Manager   │  │ Registry  │
└────────┘   └──────────┘  └───────────┘
    │
    ▼
┌─────────────────────────────────────┐
│           MemoryStore                │
│  ┌──────────┐      ┌────────────┐  │
│  │  Redis   │      │  Database  │  │
│  │ 短期记忆  │      │  长期记忆   │  │
│  └──────────┘      └────────────┘  │
└─────────────────────────────────────┘
```

- `MemoryManager`：负责**存取**历史
- `ContextManager`：负责**裁剪和组装**历史到 Prompt
- `ToolRegistry`：负责工具注册和调用
- `MemoryStore`：负责**持久化**，可替换为 Redis / Database

三者共同支撑多轮 QA 场景。

---

## 九、后续演进

| 阶段 | 能力 | 说明 |
|:---|:---|:---|
| **第一版（当前）** | 工作记忆 + 内存实现 + 评判与反思 | 支持 QA 助手多轮对话，Agent 对中间结果做规则评判，出错时触发 Reflection |
| **第二版** | Redis 短期记忆 | 生产环境多实例共享会话 |
| **第三版** | 数据库长期记忆 + 审计 | 持久化、合规、用户画像 |
| **第四版** | 记忆压缩/摘要 | 对长历史做摘要，减少 Token 占用 |
| **第五版** | 向量长期记忆 | 语义检索历史，支持更复杂的指代和关联 |
| **第六版** | 租户隔离完善 | Redis key 前缀 + DB tenant_id 全面落地 |

---

## 十、总结

> **Memory 是 CoreAgent 中台的重要扩展模块。第一版聚焦工作记忆，面向 QA 助手的多轮对话场景，通过 `MemoryManager` 保存会话历史，由 `ContextManager` 控制 Prompt 长度，从而提升指代消解、减少重复检索、保持对话连贯性。**

目前已在 `core-agent` 中落地第一版实现：

- `com.example.agent.memory.MemoryManager` / `MemoryStore` 接口
- `InMemoryMemoryManager` + `InMemoryMemoryStore`（内存原型）
- `com.example.agent.context.ContextManager` + `ContextStrategy` 上下文窗口管理
- `DefaultContextStrategy` / `RagContextStrategy` / `OpsContextStrategy` 三种策略
- `Agent.run(String sessionId, String query)` 支持按会话存取历史
- `AgentApp` 接入 `JTokkitTokenCountEstimator` 并演示多轮 QA
- `Agent.evaluateStep(...)` 对中间结果做规则评判
- `Agent.reflect(...)` 在评判非 OK 时触发 LLM 自我反思，并保存到 Memory
- LLM 调用和 Tool 执行均支持超时控制与失败重试

生产环境可按 7.2 节分层架构替换为 `RedisMemoryStore` + `DatabaseMemoryStore`。
