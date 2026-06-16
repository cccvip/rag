# MCP 协议 — 面试深度 QA

> 面试题：MCP（Model Context Protocol）的 Server/Client 架构是什么？它和 Function Calling 有什么区别？生态价值在哪？
> 候选人背景：8年 Java 后端，实际使用 MCP 打通 YAPI 与 AI 编码助手的开发工作流

---

## 一、最佳答案

### 1.1 MCP 是什么

MCP（Model Context Protocol）是 Anthropic 在 2024 年底发布的开放协议，定义了**LLM 应用和外部数据源/工具之间的标准通信方式**。

一句话理解：**Function Calling 解决的是"模型怎么调工具"，MCP 解决的是"工具怎么接入模型"。**

### 1.2 Server/Client 架构

```
┌─────────────────────┐         ┌─────────────────────┐
│     MCP Host        │         │     MCP Server      │
│   (LLM 应用端)      │         │   (工具/数据源端)    │
│                     │         │                     │
│  ┌───────────────┐  │  JSON-  │  ┌───────────────┐  │
│  │  MCP Client   │──┼─RPC────▶│  │ Tool Provider │  │
│  │  (协议客户端)  │  │  over   │  │ (工具实现)     │  │
│  └───────────────┘  │  stdio/ │  ├───────────────┤  │
│                     │  SSE    │  │ Resource      │  │
│  ┌───────────────┐  │         │  │ Provider      │  │
│  │  LLM Runtime  │  │◀────────┤  │ (数据源)      │  │
│  └───────────────┘  │  tools  │  ├───────────────┤  │
│                     │  +      │  │ Prompt        │  │
│                     │  resrc  │  │ Template      │  │
│                     │         │  └───────────────┘  │
└─────────────────────┘         └─────────────────────┘
```

**三个角色**：

- **MCP Host**：LLM 应用本身（比如 Claude Desktop、Cursor、你自研的 Agent 平台）。它发起连接，管理多个 MCP Server。
- **MCP Client**：协议客户端，嵌入在 Host 里，负责和 MCP Server 通信。一个 Client 对应一个 Server。
- **MCP Server**：工具和数据源的提供方。它把自己的能力（工具、资源、Prompt 模板）通过标准协议暴露出去。

### 1.3 MCP 暴露的三类能力

MCP Server 可以向 Client 暴露三类东西：

| 类型 | 说明 | 类比 |
|------|------|------|
| **Tools** | 可执行的操作（函数调用） | Function Calling 的工具 |
| **Resources** | 可读取的数据源（文件、数据库、API） | RAG 的外部知识 |
| **Prompts** | 预定义的 Prompt 模板 | 系统 Prompt 的模块化 |

Tools 是 Function Calling 的等价物，但 Resources 和 Prompts 是 MCP 独有的——Function Calling 只管"调工具"，MCP 还管"读数据"和"用模板"。

### 1.4 通信协议

MCP Client 和 Server 之间的通信基于 **JSON-RPC 2.0**，支持两种传输方式：

- **stdio**：Client 启动 Server 进程，通过 stdin/stdout 通信。适合本地工具（比如操作文件系统、本地数据库）。
- **SSE（Server-Sent Events）**：Client 通过 HTTP SSE 连接远程 Server。适合远程服务（比如云端 API、共享数据库）。

### 1.5 和 Function Calling 的核心区别

| 维度 | Function Calling | MCP |
|------|-----------------|-----|
| **定义者** | 模型厂商（OpenAI/智谱/Anthropic） | Anthropic 发起的开放协议 |
| **工具定义** | 每次请求时把 Schema 塞进 messages | Server 启动时注册，Client 自动发现 |
| **耦合度** | 工具逻辑和 LLM 应用紧耦合 | 工具独立部署，通过协议解耦 |
| **复用性** | 换个模型可能要改 Schema 格式 | 一个 MCP Server 可以被任何 MCP Client 调用 |
| **能力范围** | 只有 Tools | Tools + Resources + Prompts |
| **生态** | 各厂商各自为政 | 统一标准，第三方可以独立开发 Server |

**本质区别**：Function Calling 是**模型侧的能力**——模型能调工具。MCP 是**生态侧的协议**——工具怎么标准化接入。

---

## 二、加分项

1. **区分"协议"和"能力"。** "Function Calling 是模型的一种能力，MCP 是一种通信协议。两者不在同一层——MCP 的 Tools 就是 Function Calling 的工具，但 MCP 把它标准化了，还扩展了 Resources 和 Prompts。"——说明你理解抽象层次。

2. **提到工具的可发现性。** "Function Calling 的工具列表是硬编码在请求里的——每次调用都要把所有工具的 Schema 发给模型。MCP Server 启动后，Client 可以动态发现它提供了哪些工具，新增工具不需要改 Host 代码。这是本质区别——从'硬编码'到'动态发现'。"——说明你理解协议的工程价值。

3. **类比已有知识。** "MCP 之于 LLM 工具，就像 HTTP 之于 Web 服务——都是通过标准化协议解耦两端。HTTP 让浏览器可以访问任何 Web 服务器，MCP 让任何 LLM 应用可以接入任何工具提供方。"——面试官喜欢听类比。

4. **提到生态价值。** "MCP 的生态价值在于**工具的独立开发和分发**。现在做 Function Calling，工具是和你的 Agent 代码绑在一起的。有了 MCP，工具可以独立打包、独立部署、独立版本管理——就像 npm 包一样。"

5. **结合自己项目讲认知。** "我在运维 Agent 中用的是 Function Calling，直接把 ELK、Prometheus 封装成工具。如果用 MCP，可以把这些工具封装成 MCP Server，其他 Agent（比如客服 Agent、数据分析 Agent）也可以复用同一套工具，不需要重复开发。"

6. **区分 MCP 的两类使用场景。** "MCP 我在两个场景有认知：一是**工具调用**（运维 Agent 的 Function Calling），二是**数据源接入**（YAPI 接口规范通过 MCP Resource 给 AI 编码助手）。前者用 Tools，后者用 Resources——MCP 的三类能力不是都要用，按场景选。"

---

## 三、追问应对

### Q1：MCP 的资源订阅机制是怎么工作的？

"MCP 的 Resources 不是被动查询，而是支持**主动订阅**。

工作流程：

```
1. Client 连接 Server 后，调用 resources/list 获取可用资源列表
   → 返回: [{ uri: "db://orders/schema", name: "订单表结构" },
             { uri: "file://logs/error.log", name: "错误日志" }]

2. Client 选择感兴趣的资源，调用 resources/subscribe 订阅

3. 当资源内容变化时，Server 主动推送 notifications/resources/updated
   → Client 收到通知后，调用 resources/read 获取最新内容
```

**和 Function Calling 的区别**：Function Calling 是**请求-响应模式**——Agent 调一次工具拿一次结果。MCP 的资源订阅是**推模式**——Server 主动告诉 Client 数据变了，不需要 Client 轮询。

**实际应用场景**：比如一个监控 Agent 订阅了 Prometheus 的告警资源，当新告警触发时，Server 主动推送给 Agent，Agent 实时处理。比轮询高效得多。"

> **结合项目**："我的运维 Agent 当前是轮询模式——定时查告警列表。如果用 MCP 的资源订阅，可以改成推送模式，告警触发时 MCP Server 主动通知 Agent，响应延迟从分钟级降到秒级。"

### Q2：为什么不直接用 Function Calling，而要引入一个独立协议？

"三个核心原因：

**第一，工具复用。** Function Calling 的工具是和你的 Agent 代码绑在一起的——你写了一个 `query_elk` 工具，只能给你的 Agent 用。换一个 Agent 项目，还得重新写一遍。有了 MCP，工具封装成独立的 Server，任何 MCP Client 都能调用。就像你写了一个 HTTP API，任何浏览器都能访问。

**第二，动态发现。** Function Calling 的工具列表是硬编码的——新增一个工具，要改代码、重新部署。MCP Server 启动后，Client 通过 `tools/list` 自动发现所有可用工具，新增工具不需要动 Host 代码。

**第三，生态标准化。** 现在 OpenAI、智谱、Anthropic 的 Function Calling Schema 格式各不相同。同一个工具，接 OpenAI 要写一套 Schema，接智谱要写另一套。MCP 定义了统一标准，工具开发者只需要写一次 MCP Server，所有支持 MCP 的 Host 都能用。

**类比**：Function Calling 就像你直接在代码里写 `HttpClient.get("http://elk/api/logs")`——能用，但紧耦合、难复用。MCP 就像你用了 REST API 标准——松耦合、可复用、生态统一。"

> **诚实补充**："当然，MCP 目前还在早期阶段，生态还不够成熟。实际生产中，如果你的工具只给一个 Agent 用，直接用 Function Calling 更简单。MCP 的价值在工具需要跨 Agent 复用、或者需要接入第三方工具生态时才体现。"

### Q3：你在项目中用过 MCP 吗？遇到过什么坑？

**有实际使用，场景是 AI 辅助代码生成：**

"MCP 我在实际开发中有使用，场景是 **AI 辅助工程效能提升**。

复杂业务的接口定义还是由人工在 YAPI 上完成——入参、出参、枚举值、业务约束这些需要人来把控。然后通过 MCP 把 YAPI 的接口规范暴露为 Resource，AI 编码助手通过 MCP 读取这些规范，自动生成对应的 Controller、Service、Request/Response 代码。

```
YAPI (人工定义接口规范)
  │
  ▼
MCP Server (YAPI 适配层)
  │  resources/list → 暴露所有接口定义
  │  resources/read  → 返回接口的 Schema（入参、出参、枚举）
  ▼
AI 编码助手 (Cursor / Claude)
  │  读取接口规范 → 生成代码
  ▼
Controller + Service + Request/Response
  → 代码一定符合 YAPI 定义的规范
```

**好处**：
1. **规范即代码**——YAPI 是 Single Source of Truth，AI 生成的代码一定符合接口规范，不会出现人写的和文档不一致的问题
2. **复杂接口人工定义，重复编码 AI 完成**——业务逻辑的决策权在人手里，搬砖的活交给 AI
3. **接口变更时自动同步**——YAPI 改了，下次 AI 读取时自动拿到最新规范，不需要手动同步

**这个场景用 MCP 而不是直接用 Function Calling 的原因是**：YAPI 的接口规范是数据，不是操作。我不需要'调用' YAPI 的什么功能，我只需要'读取'它暴露的接口定义。MCP 的 Resources 就是为这种场景设计的。

**遇到的挑战**：
1. **Schema 质量依赖人工**——YAPI 上的接口定义如果不规范（缺注释、枚举值写错），AI 生成的代码也有问题。Garbage in, garbage out。
2. **复杂业务逻辑仍需人工介入**——涉及多表关联、事务边界、并发控制的代码，AI 还是写不好，需要人 review 和修改。
3. **MCP Server 的稳定性**——YAPI 接口多的时候，MCP Server 响应变慢，需要做缓存。

**我的判断**：MCP 在**数据源接入**场景（API 规范、文档、配置）比 Function Calling 更自然。但对于运维 Agent 那种**工具调用**场景，Function Calling 直接集成更简单。两者不是替代关系，是互补的——MCP 管'数据从哪来'，Function Calling 管'工具怎么调'。"

---

## 四、架构图

### MCP Server/Client 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        MCP Host (LLM 应用)                       │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    Agent Runtime                          │   │
│  │                                                            │   │
│  │   用户输入 ──▶ LLM 推理 ──▶ 工具调用 ──▶ 结果生成          │   │
│  └─────────────────────────────┬──────────────────────────────┘   │
│                                │                                  │
│  ┌─────────────────────────────┼──────────────────────────────┐   │
│  │                    MCP Client Manager                       │   │
│  │                                                             │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                 │   │
│  │  │ Client 1 │  │ Client 2 │  │ Client 3 │                 │   │
│  │  │ (stdio)  │  │  (SSE)   │  │  (SSE)   │                 │   │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘                 │   │
│  └───────┼──────────────┼──────────────┼───────────────────────┘   │
└──────────┼──────────────┼──────────────┼───────────────────────────┘
           │              │              │
     JSON-RPC       JSON-RPC       JSON-RPC
     over stdio     over SSE       over SSE
           │              │              │
           ▼              ▼              ▼
  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ MCP Server A │ │ MCP Server B │ │ MCP Server C │
  │              │ │              │ │              │
  │ Tools:       │ │ Tools:       │ │ Tools:       │
  │ - read_file  │ │ - query_db   │ │ - send_email │
  │ - write_file │ │ - exec_sql   │ │ - notify     │
  │              │ │              │ │              │
  │ Resources:   │ │ Resources:   │ │ Resources:   │
  │ - /workspace │ │ - db://schema│ │ - templates/ │
  └──────────────┘ └──────────────┘ └──────────────┘
```

### MCP vs Function Calling 调用链路对比

```
=== Function Calling: 工具和 Agent 紧耦合 ===

  Agent 代码
    │
    ├─ tool_schemas = [                          ← 启动时硬编码
    │    { name: "query_elk", params: {...} },
    │    { name: "query_prom", params: {...} }
    │  ]
    │
    ├─ LLM 推理 → tool_call: query_elk
    │
    └─ 直接调用本地函数 query_elk(args)          ← 紧耦合，只能自己用
         │
         └─ 返回结果


=== MCP: 工具通过协议解耦 ===

  Agent 代码 (MCP Host)
    │
    ├─ MCP Client 连接 Server A                    ← 动态连接
    │   └─ tools/list → 发现 [query_elk, query_prom]  ← 动态发现
    │
    ├─ LLM 推理 → tool_call: query_elk
    │
    └─ MCP Client 通过 JSON-RPC 调用 Server A     ← 协议解耦
         │
         Server A 执行 query_elk                   ← 独立部署
         │
         └─ 返回结果

  Agent 代码 (另一个 Agent)
    │
    └─ MCP Client 也连接 Server A                  ← 复用同一个 Server
        └─ 同样的 query_elk 工具，不需要重新开发
```

### MCP 三类能力的交互流程

```
  MCP Host                           MCP Server
     │                                    │
     │  1. initialize                     │
     │ ─────────────────────────────────▶ │  建立连接，交换协议版本
     │ ◀───────────────────────────────── │  返回 Server 能力声明
     │                                    │
     │  2. tools/list                     │
     │ ─────────────────────────────────▶ │  获取可用工具列表
     │ ◀───────────────────────────────── │  返回 [{name, description, schema}]
     │                                    │
     │  3. resources/list                 │
     │ ─────────────────────────────────▶ │  获取可用资源列表
     │ ◀───────────────────────────────── │  返回 [{uri, name, mimeType}]
     │                                    │
     │  4. resources/subscribe (uri)      │
     │ ─────────────────────────────────▶ │  订阅资源变更通知
     │                                    │
     │  5. prompts/list                   │
     │ ─────────────────────────────────▶ │  获取可用 Prompt 模板
     │ ◀───────────────────────────────── │  返回 [{name, arguments}]
     │                                    │
     │  === 运行时 ===                     │
     │                                    │
     │  6. tools/call (name, args)        │
     │ ─────────────────────────────────▶ │  调用工具
     │ ◀───────────────────────────────── │  返回工具执行结果
     │                                    │
     │  7. notifications/resources/updated│
     │ ◀───────────────────────────────── │  Server 主动推送：资源变了
     │                                    │
     │  8. resources/read (uri)           │
     │ ─────────────────────────────────▶ │  读取最新资源内容
     │ ◀───────────────────────────────── │  返回资源内容
```

---

## 五、面试策略总结

| 场景 | 怎么说 |
|------|--------|
| 面试官问架构 | 讲 Server/Client/Host 三层 + 三类能力（Tools/Resources/Prompts） |
| 面试官问和 Function Calling 的区别 | 讲四个维度：定义者、耦合度、复用性、能力范围 |
| 面试官问生态价值 | 讲"工具的独立开发和分发"，类比 npm/REST API |
| 面试官问你用过没有 | 讲 YAPI + MCP 的实际场景：API 规范通过 MCP Resource 给 AI 编码助手 |
| 面试官追问资源订阅 | 讲 subscribe + notifications 的推模式 |
| 面试官追问为什么不直接用 FC | 讲三个原因：工具复用、动态发现、生态标准化 |
| 面试官追问挑战 | 讲 Schema 质量依赖人工、复杂逻辑仍需人工介入、Server 稳定性 |

**核心原则：MCP 的 Resources 和 Tools 是两类不同的使用场景。你的运维 Agent 用 Function Calling 调工具（Tools），AI 辅助开发用 MCP 读数据源（Resources）。能区分这两类场景，说明你对 MCP 的理解不是停留在概念层面。**
