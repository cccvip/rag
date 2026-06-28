# ReAct 模式原理与实现

> 当前版本首先实现了 ReAct（Reasoning + Acting）推理模式，并在此基础上扩展了工具注册、安全护栏、评测指标等中台能力。

---

## 一、ReAct 是什么

ReAct 是 **Reasoning（推理）** 与 **Acting（行动）** 的结合，由 Yao 等人在 2022 年提出。它的核心思想是：

> 让大模型在解决问题时，不是一次性给出答案，而是交替进行 **思考（Thought）**、**行动（Action）** 和 **观察（Observation）**，直到任务完成。

### 1.1 与传统方式的对比

| 方式 | 特点 | 问题 |
|:---|:---|:---|
| 纯推理（Chain-of-Thought） | 只让模型内部思考 | 容易幻想，无法使用外部工具 |
| 纯行动（Tool Use） | 直接调用工具 | 缺乏规划，容易反复试错 |
| **ReAct** | **思考与行动交替** | 既有推理规划，又能使用工具验证 |

### 1.2 ReAct 的三元组

一个典型的 ReAct 循环由以下三个元素构成：

```text
Thought:  模型对当前状态的推理
Action:   模型决定执行的动作（通常是调用某个工具）
Observation: 动作执行后返回的结果
```

循环一直进行，直到模型认为已经获得足够信息，输出最终答案。

### 1.3 为什么需要 ReAct

在 RAG、运维自愈、数据查询等场景中，问题往往不是“一次性回答”就能解决的：

- RAG 需要 **检索 → 精排 → 生成**
- 运维自愈需要 **诊断 → 查日志 → 定位 → 修复**
- 数据查询需要 **理解问题 → 选数据源 → 生成 SQL → 解释结果**

ReAct 让 LLM 能够像人一样分步骤解决问题，并在每一步根据反馈调整策略。

---

## 二、项目中的 ReAct 架构

### 2.1 核心角色

```text
┌─────────────────────────────────────────────────────────┐
│                      User Query                          │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────┐
│                         Agent                            │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │
│  │   Thought   │ -> │   Action    │ -> │ Observation │ │
│  └─────────────┘    └─────────────┘    └─────────────┘ │
│         ▲                                          │     │
│         └──────────────────────────────────────────┘     │
└────────────────────────┬────────────────────────────────┘
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
      ┌─────────┐  ┌─────────┐  ┌─────────┐
      │Retriever│  │Reranker │  │ Writer  │
      └─────────┘  └─────────┘  └─────────┘
            │            │            │
            └────────────┴────────────┘
                         │
                   ┌─────────────┐
                   │ ToolRegistry │
                   └─────────────┘
```

### 2.2 核心类说明

| 类 | 职责 |
|:---|:---|
| `Tool` | 工具接口，定义工具的 name、description、riskLevel、execute |
| `ToolRegistry` | 工具注册中心，管理所有可用工具 |
| `Agent` | ReAct 循环引擎，驱动 Thought → Action → Observation |
| `GuardRail` | 安全护栏，根据风险等级拦截或审计工具调用 |
| `MetricsTracker` | 评测指标追踪器 |
| `ChatModel` | Spring AI 提供的 LLM 调用抽象 |

### 2.3 风险等级的业务含义

风险等级不是技术概念，而是**业务安全概念**。同一个工具在不同业务里风险等级可能完全不同。

以应急安全 SaaS 为例：

| 风险等级 | 含义 | 典型工具 | GuardRail 策略 |
|:---|:---|:---|:---|
| **LOW** | 只读、无副作用 | 文档检索、数据查询 | 直接允许 |
| **MEDIUM** | 有输出但影响可控 | 生成回答、发送通知 | 记录审计日志后允许 |
| **HIGH** | 有实际副作用，需要确认 | 重启服务、修改配置 | 需要人工确认 |
| **CRITICAL** | 高危、不可逆、危及安全 | 自动修复、触发疏散、删除租户数据 | **不允许 Agent 自动调用** |

---

## 三、核心代码实现

### 3.1 Tool 接口

每个可被 Agent 调用的能力都需要实现 `Tool` 接口：

```java
public interface Tool {
    String name();
    String description();
    RiskLevel riskLevel();
    String execute(String input);
}
```

**关键点：**
- `name` 是 LLM 在 `Action` 中填写的工具名
- `description` 会进入 system prompt，告诉 LLM 这个工具做什么、什么时候用、输入输出格式
- `riskLevel` 用于 GuardRail 安全管控

### 3.2 工具注册中心

```java
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }
}
```

### 3.3 Agent 中的 ReAct 循环

`Agent.run()` 是整个 ReAct 模式的核心实现：

```java
public String run(String query) throws Exception {
    String systemPrompt = buildSystemPrompt();
    StringBuilder history = new StringBuilder();
    history.append("User: ").append(query).append("\n");

    for (int i = 0; i < maxIterations; i++) {
        metrics.recordStep();

        // 1. 调用 LLM 获取 Thought + Action
        long llmStart = System.currentTimeMillis();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(history.toString())
        ));
        ChatResponse response = chatModel.call(prompt);
        long llmLatency = System.currentTimeMillis() - llmStart;

        String llmOutput = response.getResult().getOutput().getContent();
        Usage usage = response.getMetadata().getUsage();
        metrics.recordLlmCall(
                usage.getPromptTokens(),
                usage.getGenerationTokens(),
                usage.getTotalTokens(),
                llmLatency
        );

        // 2. 如果 LLM 直接输出最终答案，结束循环
        if (llmOutput.contains("Final Answer:")) {
            String finalAnswer = llmOutput.substring(
                    llmOutput.indexOf("Final Answer:") + 13).trim();
            metrics.computeCitationAccuracy(finalAnswer);
            metrics.setTaskSuccess(true);
            return finalAnswer;
        }

        // 3. 解析 Thought / Action / Action Input
        String thought = extractLine(llmOutput, "Thought:");
        String action = extractLine(llmOutput, "Action:");
        String actionInput = extractLine(llmOutput, "Action Input:");

        // 4. 通过 ToolRegistry 查找并执行工具
        Tool tool = registry.get(action);
        String observation;
        boolean success;
        boolean blocked = false;

        long toolStart = System.currentTimeMillis();
        if (tool == null) {
            observation = "Error: tool '" + action + "' not found.";
            success = false;
        } else {
            // 高风险动作记录 + 拦截
            if (tool.riskLevel() == RiskLevel.HIGH || tool.riskLevel() == RiskLevel.CRITICAL) {
                metrics.recordHighRiskAttempt();
            }
            if (!guardRail.allow(tool, tenantId, userId)) {
                observation = "Blocked by GuardRail: tool '" + action + "' is " + tool.riskLevel() + ".";
                success = false;
                blocked = true;
            } else {
                observation = tool.execute(actionInput);
                success = !observation.startsWith("Error");

                // 检索工具召回的文档用于后续引用准确率计算
                if (tool.name().contains("retriever") || tool.name().contains("search")) {
                    metrics.recordRetrievedDocs(observation);
                }
            }
        }
        long toolLatency = System.currentTimeMillis() - toolStart;

        metrics.recordToolCall(action, actionInput, observation, success, toolLatency, blocked);

        // 5. 把本轮结果追加到历史，继续下一轮
        history.append("Assistant: ").append(llmOutput).append("\n");
        history.append("Observation: ").append(observation).append("\n");
    }

    metrics.setTaskSuccess(false);
    return "Reached max iterations without final answer.";
}
```

### 3.4 System Prompt 设计

ReAct 能否正确运行，很大程度上取决于 system prompt 是否把输出格式讲清楚：

```java
private String buildSystemPrompt() {
    StringBuilder sb = new StringBuilder();
    sb.append("You are a helpful assistant that solves problems by using tools.\n");
    sb.append("Think step by step. For each step, output exactly in this format:\n\n");
    sb.append("Thought: [your reasoning about what to do next]\n");
    sb.append("Action: [tool name]\n");
    sb.append("Action Input: [input for the tool]\n\n");
    sb.append("When you have enough information to answer the user, output:\n");
    sb.append("Final Answer: [your final answer]\n\n");
    sb.append("Available tools:\n");
    for (Tool tool : registry.all()) {
        sb.append("- ").append(tool.name())
          .append("(").append(tool.riskLevel()).append(")")
          .append(": ").append(tool.description()).append("\n");
    }
    return sb.toString();
}
```

### 3.5 安全护栏

GuardRail 的核心作用是：**防止 LLM 在推理过程中误调用具有真实副作用甚至危险后果的工具**。

即使 LLM 输出了 `Action: auto_repair`，GuardRail 也可以把它拦下来：

```text
User: 帮我看看系统状态
LLM Thought: 系统有点异常，我直接调用 auto_repair 修复吧
LLM Action: auto_repair
GuardRail: [AUDIT] tenant=tenant-A, user=user-001, tool=auto_repair, risk=CRITICAL
GuardRail: [Blocked] CRITICAL tool 'auto_repair' for tenant=tenant-A, user=user-001
Observation: Blocked by GuardRail: tool 'auto_repair' is CRITICAL.
LLM Thought: 自动修复被拦截了，我应该先诊断问题
LLM Action: diagnose
```

实现代码：

```java
public class GuardRail {

    private final List<String> auditLogs = new ArrayList<>();

    public boolean allow(Tool tool) {
        return allow(tool, "default-tenant", "default-user");
    }

    public boolean allow(Tool tool, String tenantId, String userId) {
        RiskLevel riskLevel = tool.riskLevel();
        recordAudit(tool, tenantId, userId);

        switch (riskLevel) {
            case LOW:
            case MEDIUM:
                return true;
            case HIGH:
                return requestHumanConfirmation(tool, tenantId, userId);
            case CRITICAL:
                System.out.println("[GuardRail] Blocked CRITICAL tool '" + tool.name()
                        + "' for tenant=" + tenantId + ", user=" + userId);
                return false;
            default:
                return false;
        }
    }

    private void recordAudit(Tool tool, String tenantId, String userId) {
        String log = String.format("[AUDIT] tenant=%s, user=%s, tool=%s, risk=%s",
                tenantId, userId, tool.name(), tool.riskLevel());
        auditLogs.add(log);
        System.out.println(log);
    }

    /**
     * 高风险工具请求人工确认。
     * 生产环境中应该调用工单系统、发送审批消息或弹出确认界面。
     */
    private boolean requestHumanConfirmation(Tool tool, String tenantId, String userId) {
        System.out.println("[GuardRail] HIGH risk tool '" + tool.name()
                + "' requires human confirmation for tenant=" + tenantId
                + ", user=" + userId + ". Blocking in demo mode.");
        return false;
    }

    public List<String> getAuditLogs() {
        return new ArrayList<>(auditLogs);
    }
}
```

### 3.6 RAG 工具的注册示例

```java
registry.register(new Tool() {
    public String name() { return "retriever"; }

    public String description() {
        return "Retrieve relevant emergency safety documents from the knowledge base by keyword or question. " +
               "Use this FIRST when the user asks about any safety procedure, regulation, or emergency response. " +
               "Input: a plain text search query, e.g. 'chemical spill response procedure'. " +
               "Output: a list of candidate documents in the format '[doc-id] document title/summary; ...'";
    }

    public RiskLevel riskLevel() { return RiskLevel.LOW; }

    public String execute(String input) {
        return "[doc-1001] Chemical spill response guide; " +
               "[doc-1002] Factory evacuation procedures; " +
               "[doc-1003] Hazardous material handling checklist.";
    }
});
```

---

## 四、ReAct 运行示例

以用户问题 **"What is the emergency procedure for chemical spill?"** 为例，整个 ReAct 流程如下：

```text
User: What is the emergency procedure for chemical spill?

--- LLM Response ---
Thought: The user is asking about emergency procedure for chemical spill. I need to retrieve relevant safety documents first.
Action: retriever
Action Input: chemical spill response procedure

--- Observation ---
[doc-1001] Chemical spill response guide; [doc-1002] Factory evacuation procedures; [doc-1003] Hazardous material handling checklist.

--- LLM Response ---
Thought: I have 3 candidate documents. I should rerank them to find the most relevant ones.
Action: reranker
Action Input: {"query": "chemical spill response", "doc_ids": ["1001", "1002", "1003"]}

--- Observation ---
[doc-1001](0.95); [doc-1002](0.72); [doc-1003](0.45)

--- LLM Response ---
Thought: The most relevant documents are [doc-1001] and [doc-1002]. I can now generate the final answer with citations.
Action: writer
Action Input: {"query": "chemical spill response", "doc_ids": ["1001", "1002"]}

--- Observation ---
Draft answer generated based on selected documents.

--- LLM Response ---
Final Answer: In case of a chemical spill, first evacuate personnel from the affected area [doc-1002], then follow the containment and cleanup procedures outlined in the safety guide [doc-1001].
```

---

## 五、评测指标

原型中内置了 `MetricsTracker`，用于评估 ReAct Agent 的运行效果：

| 指标 | 说明 |
|:---|:---|
| 任务成功率 | 是否在规定步数内输出 Final Answer |
| 平均步数 | 实际执行的 Thought-Action-Observation 轮数 |
| 工具调用成功率 | 成功执行的工具调用 / 总工具调用 |
| 重复动作次数 | 连续调用同一个工具的次数 |
| 证据引用准确率 | Final Answer 中的 `[doc-id]` 引用是否来自检索结果 |
| 高风险动作拦截率 | GuardRail 拦截的高风险动作 / 高风险动作尝试总数 |
| 成本和延迟 | LLM Token 总数 + 每次调用耗时 |

---

## 六、与 Spring AI 的关系

本原型基于 **Spring AI** 构建 LLM 调用层，但保留了手动的 ReAct 循环：

| 层级 | 技术/职责 |
|:---|:---|
| LLM 调用层 | Spring AI `ChatModel` + `OpenAiChatModel`（复用 OpenAI 协议访问 DeepSeek） |
| 推理编排层 | 手写的 ReAct 循环（`Agent.run`） |
| 工具管理层 | `ToolRegistry` + `Tool` 接口 |
| 安全治理层 | `GuardRail` + `RiskLevel` |
| 评测层 | `MetricsTracker` |

这种设计的意义在于：

1. **Spring AI 解决模型抽象问题**：切换 DeepSeek / OpenAI / Qwen 只需改配置，不改核心代码。
2. **CoreAgent 解决业务中台问题**：ToolRegistry、GuardRail、TenantCtrl、MetricsTracker 等是企业生产落地必需的能力，Spring AI 不会帮你做。

---

## 七、后续演进方向

当前 ReAct 是单 Agent 内的线性循环，后续可以在此基础上扩展：

1. **多 Agent 协同**：不同 Agent 负责不同领域（RAG Agent、Ops Agent、Data Agent），由上层 Router 分发。
2. **Plan-and-Execute**：先让 LLM 生成完整计划，再按步骤执行，减少重复调用。
3. **Reflection**：让 Agent 自我检查中间结果，发现错误时回溯重做。
4. **Memory 增强**：引入长期记忆，支持跨会话上下文。
5. **MCP 协议接入**：把 CoreAgent 的工具通过标准协议暴露给外部客户端。

---

## 八、总结

ReAct 的本质是 **让 LLM 在推理和行动之间反复迭代**，通过外部工具获取信息、验证假设、逐步逼近答案。

在本项目中：

- `Agent` 是 ReAct 循环的载体
- `Tool` 和 `ToolRegistry` 提供可复用的工具能力
- `GuardRail` 保证高风险操作可控
- `MetricsTracker` 让 Agent 的运行效果可量化
- Spring AI 提供底层的 LLM 调用抽象

这套原型正是 CoreAgent 中台服务层的雏形：在 Spring AI 之上，封装出面向企业生产环境的 Agent 基础设施。
