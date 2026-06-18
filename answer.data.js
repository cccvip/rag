window.PREPME_ANSWERS = window.PREPME_ANSWERS || {};

window.PREPME_ANSWERS["q1kcmjz3"] = {
  question: "请解释 ReAct Pattern 的工作原理，以及它与传统 Chain-of-Thought 的区别是什么？",
  level: "Core",
  why: "JD: 要求理解Agent工作机制",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>ReAct 是"边想边做"，CoT 是"只想不做"。CoT 是封闭推理，所有信息都在 Prompt 里，模型靠内部知识推理；ReAct 是开放推理，每一步都可以调用外部工具获取真实数据，推理被事实约束。</p>

<h3>在 CoreAgent 平台层中的实践</h3>
<p>我在 Spring AI 之上封装了 CoreAgent 平台层，其中 AgentExecutor 就是 ReAct 引擎。核心链路：Thought（LLM 决策下一步操作）→ Action（通过 Function Calling 调用工具）→ Observation（工具返回值经 PreProcessor 预处理后作为上下文）→ 循环或终止。</p>
<p>以运维场景为例，收到"服务响应慢"告警：</p>
<ol>
<li><strong>Thought</strong>：响应慢可能是什么原因？→ 先看日志</li>
<li><strong>Action</strong>：调用 LogQueryTool 查最近错误日志</li>
<li><strong>Observation</strong>：PreProcessor 聚合去重后，摘要显示大量 DB timeout</li>
<li><strong>Thought</strong>：DB timeout → 可能慢查询 → 看 SQL 监控</li>
<li><strong>Action</strong>：调用 MetricQueryTool 查慢查询指标</li>
<li><strong>Observation</strong>：某条 SQL 全表扫描</li>
<li><strong>结论</strong>：XX 表缺索引，建议加索引</li>
</ol>
<p>如果用 CoT，Agent 可能直接猜"可能是网络问题"——没有数据支撑，幻觉风险高。ReAct 每一步都有 Observation 作为锚点。</p>

<h3>架构图</h3>
<pre>
┌──────────────── CoreAgent 平台层 ────────────────────┐
│  AgentExecutor (ReAct 引擎)                           │
│  ┌─────────┐  ┌─────────┐  ┌─────────────┐          │
│  │ Thought │→ │ Action  │→ │ Observation │          │
│  │  (LLM)  │  │(FC调用) │  │ (预处理后)  │          │
│  └────▲────┘  └─────────┘  └──────┬──────┘          │
│       └───────────反馈─────────────┘  最多 N 轮       │
├──────────────────────────────────────────────────────┤
│  ToolRegistry  │ PreProcessor │ GuardRail │ Tracer   │
├──────────────────────────────────────────────────────┤
│  运维ToolSet   │  RAG ToolSet │  未来场景...          │
│  日志/指标/重启 │  向量/BM25   │                       │
└──────────────────────────────────────────────────────┘
</pre>

<h3>ReAct vs CoT vs Function Calling</h3>
<p>三者不是互斥的，是不同层次：CoT 是推理策略，ReAct 是 Agent 范式，Function Calling 是工具调用机制。现在主流做法是 ReAct 的 Action 映射为 Function Calling，Thought 通过 System Prompt 引导 LLM 内部完成。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>循环问题</strong>：早期没设最大轮次，Agent 反复查同一个指标得不出结论。加了 5 轮上限 + 重复 Action 检测解决。</li>
<li><strong>工具返回值撑爆 context</strong>：ELK 返回几万条日志直接塞进 Prompt 会爆 window。CoreAgent 的 PreProcessor 解决了这个问题——日志聚合去重、指标趋势提取，只给 LLM 摘要。</li>
<li><strong>幻觉控制</strong>：LLM 偶尔"脑补"不存在的指标。CoreAgent 的 GuardRail 限定 LLM 决策范围为"下一步查什么"，数据分析由确定性代码完成。</li>
</ul>

<h2>加分项</h2>
<p>ReAct 适合探索性任务（不确定要做什么），Plan-and-Execute 适合确定性任务（步骤明确）。CoreAgent 的 AgentExecutor 目前用 ReAct，下一步优化方向是支持可插拔执行策略——简单故障走 ReAct，复杂任务走 Plan-and-Execute，多 Agent 协同走 Multi-Agent。</p>
<p>ReAct 的本质是把 LLM 的推理能力和外部工具的执行能力结合，让推理有事实依据。任何需要"基于外部数据做决策"的场景都适用。</p>`,
  followups: [
    {
      q: "ReAct在实际工程落地时有哪些挑战？",
      answer: `<p>三个核心挑战：</p>
<p><strong>1. 延迟和成本</strong>。每轮一次 LLM + 一次工具调用，5 轮就是 10 次请求，P99 能到 8 秒。优化：模型分级（简单步骤小模型，关键决策大模型）、工具调用并行化。</p>
<p><strong>2. 循环控制</strong>。Agent 会陷入死循环反复查同一个东西。解法：最大轮次上限 + 重复 Action 检测。</p>
<p><strong>3. 工具返回值管理</strong>。CoreAgent 的 PreProcessor 解决了这个问题——平台层提供路由链，业务方实现具体预处理逻辑（日志聚合、指标趋势提取），避免原始数据撑爆 context。</p>`
    },
    {
      q: "如何处理Agent推理过程中的幻觉问题？",
      answer: `<p>CoreAgent 的 GuardRail 安全护栏从三个维度控制幻觉：</p>
<ul>
<li><strong>缩小决策范围</strong>：LLM 只做"下一步查什么"的决策，数据分析由确定性代码完成。</li>
<li><strong>要求引用来源</strong>：Prompt 明确要求"基于 Observation 回答"，禁止编造。</li>
<li><strong>高风险操作人工确认</strong>：四级风险分级，HIGH 以上需人工确认，CRITICAL 不开放给 Agent。</li>
</ul>`
    },
    {
      q: "ReAct和Function Calling可以结合使用吗？",
      answer: `<p>必须结合，这是现在的主流做法。CoreAgent 的 AgentExecutor 就是这么实现的：Thought 部分通过 System Prompt 引导 LLM 内部思考，Action 部分通过 Function Calling 输出结构化 JSON 调用工具。比纯文本 ReAct（靠正则解析 Action）稳定得多。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1142hhr"] = {
  question: "Function Calling 的实现原理是什么？如何设计一个好的 Tool Schema？",
  level: "Core",
  why: "JD: 要求Function Calling能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>Function Calling 的本质是让 LLM 输出结构化的工具调用请求，而不是自由文本。LLM 不直接执行工具，它只是输出"我要调用哪个工具、传什么参数"的 JSON，由应用层执行。</p>

<h3>CoreAgent 中的 Tool Schema 设计</h3>
<p>CoreAgent 的 ToolRegistry 定义了统一的 CoreTool 接口，业务方实现 4 个方法即可注册工具：</p>
<pre>
CoreTool 接口:
├── name()         → 工具名称（FC 的 function name）
├── description()  → 工具描述（LLM 靠这个决定是否调用）
├── inputSchema()  → 入参 JSON Schema
└── execute()      → 执行逻辑
</pre>
<p>ToolMeta 元数据承载平台管控：风险等级、租户可见性、超时配置、最大重试次数。平台按场景隔离工具集，运维 Agent 看不到 RAG 工具。</p>

<h3>好的 Tool Schema 三要素</h3>
<ol>
<li><strong>name 语义化</strong>：search_error_logs 而不是 search1，LLM 靠名称理解用途</li>
<li><strong>description 写使用场景</strong>：不只是"查询日志"，而是"当需要排查错误原因时使用，支持按时间范围和关键词过滤"</li>
<li><strong>参数用 enum 约束</strong>：time_range 用 enum: ["1h", "6h", "24h"] 而不是 free text，减少 LLM 幻觉</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>description 歧义</strong>：两个工具描述太相似，LLM 经常调错。解法：description 里写明"什么时候用这个而不是那个"。</li>
<li><strong>参数类型不严格</strong>：LLM 传了字符串 "3" 而不是数字 3。解法：inputSchema 严格定义 type，execute 入口做类型校验。</li>
<li><strong>工具数量太多</strong>：注册了 20+ 工具，LLM 选择困难。CoreAgent 的 ToolRegistry 按场景过滤，每个场景最多 5-8 个工具。</li>
</ul>

<h2>加分项</h2>
<p>CoreAgent 的设计亮点是 ToolMeta 元数据——不只是定义工具"能做什么"，还定义了"谁能用、风险多大、超时多久"。这样 Function Calling 不只是 LLM 和工具的桥梁，还承载了平台管控能力。</p>`,
  followups: [
    {
      q: "如何处理Function Calling的错误和重试？",
      answer: `<p>CoreAgent 的 GuardRail 在工具执行前做三重检查：频率限制（同一工具短时间内不可重复调用）→ 风险判定（业务层 RiskRule）→ 人工确认（HIGH 以上）。工具执行失败时，AgentExecutor 按 ToolMeta.maxRetry 重试，超限后将错误信息作为 Observation 反馈给 LLM，让它调整策略。</p>`
    },
    {
      q: "如何限制LLM的工具调用频率？",
      answer: `<p>CoreAgent 的 TenantCtrl 提供租户级 QPS 限流（Redis 令牌桶），GuardRail 提供工具级频率限制。两层叠加：租户层面防止资源抢占，工具层面防止单个工具被滥用。</p>`
    },
    {
      q: "多工具调用时如何保证原子性？",
      answer: `<p>目前不支持原子性——每个工具独立执行，失败只影响当前步骤。如果业务需要原子性（比如"查询+写入"必须一起成功），应该设计成一个工具而不是两个。CoreAgent 的 ToolRegistry 鼓励粗粒度工具设计。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qzvl1fk"] = {
  question: "什么是 Agent 的任务分解（Task Decomposition）？常见的分解策略有哪些？",
  level: "Core",
  why: "JD: 要求任务分解能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>任务分解是把一个复杂任务拆成多个可执行的子任务。核心问题是：谁来拆、怎么拆、子任务之间怎么协调。</p>

<h3>三种常见策略</h3>
<pre>
┌─────────────────┬──────────────────────────────────────┐
│ 策略             │ 特点                                 │
├─────────────────┼──────────────────────────────────────┤
│ 固定流水线       │ 预定义步骤，如 Retriever→Reranker    │
│ LLM 动态分解     │ LLM 实时决定拆成哪些子任务            │
│ Plan-and-Execute │ 先规划全部步骤，再逐个执行             │
└─────────────────┴──────────────────────────────────────┘
</pre>

<h3>CoreAgent 中的实践</h3>
<p>当前 AgentExecutor 用 ReAct 模式处理单 Agent 任务。Multi-Agent 场景（Retriever→Reranker→Writer）用固定流水线——三个 Agent 串行编排，每个 Agent 有自己的 ToolSet 和 Prompt。</p>
<p>单 Agent Faithfulness 0.72，三 Agent 协同提升至 0.89。代价是延迟增加 50%（300ms→450ms），准确性收益大于延迟代价。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>分解粒度太细</strong>：子任务太多导致延迟叠加严重。5 个子任务串行，延迟翻 5 倍。后来控制在 3 个以内。</li>
<li><strong>子任务间信息丢失</strong>：前一个 Agent 的结论没有传递给下一个。解法：每个 Agent 的输出做结构化摘要，作为下一个 Agent 的输入上下文。</li>
</ul>

<h2>加分项</h2>
<p>CoreAgent 的下一步优化是 AgentExecutor 支持可插拔执行策略。ReAct 适合探索性任务，Plan-and-Execute 适合步骤明确的任务，Multi-Agent 适合职责差异大的任务。通过 AgentExecutionStrategy 接口，业务方按场景选择推理模式。</p>`,
  followups: [
    {
      q: "如何处理子任务之间的依赖关系？",
      answer: `<p>Multi-Agent 场景用固定流水线，依赖关系在编排时确定。如果需要动态依赖（根据前一步结果决定下一步），用 ReAct 的 Observation 反馈机制——LLM 看到前一步结果后再决定下一步调用什么工具。</p>`
    },
    {
      q: "任务分解失败时如何回滚？",
      answer: `<p>当前不支持回滚。Agent 的工具调用大多是只读操作（查询日志、检索文档），高风险操作（重启、配置变更）需要人工确认，所以回滚需求不大。如果未来有写操作场景，需要在 ToolMeta 层面设计补偿机制。</p>`
    },
    {
      q: "如何评估分解粒度是否合适？",
      answer: `<p>两个指标：延迟和准确性。子任务越多延迟越高，但准确性可能提升。我们实测 3 个 Agent 是最优平衡点——Faithfulness 0.89，延迟 450ms。超过 3 个后准确性提升边际递减，延迟线性增长。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q19atnx"] = {
  question: "请解释 MCP（Model Context Protocol）协议的设计目标和核心概念。",
  level: "Advanced",
  why: "JD: 要求MCP协议理解",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>MCP 是 Anthropic 提出的开放协议，目标是标准化 AI 应用与外部数据源/工具的连接方式。类比：USB 接口统一了各种外设的连接方式，MCP 统一了 AI 应用与外部资源的连接方式。</p>

<h3>四个核心概念</h3>
<pre>
MCP 协议
├── Resources  → 暴露数据给 AI 读取（如文件、API 文档）
├── Tools      → 暴露操作给 AI 调用（如查询、写入）
├── Prompts    → 预定义的提示词模板
└── Sampling   → 让服务器请求 AI 生成内容（反向调用）
</pre>

<h3>我的实践：MCP 驱动的规范即代码</h3>
<p>通过 MCP 协议打通 YAPI 接口规范与 AI 代码生成链路。接口定义在 YAPI 完成后，通过 MCP 暴露为 Resource，AI 编码助手（Claude Code）读取规范自动生成 Controller/Service/Request/Response 代码。重复编码工作量减少约 60%，接口规范成为 Single Source of Truth。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>Resource 定义粒度</strong>：一开始把整个 YAPI 项目作为一个 Resource，AI 读取时 context 太大。改成按接口分组，每个 Resource 对应一个模块。</li>
<li><strong>版本同步</strong>：YAPI 接口更新后 MCP Resource 没同步，AI 生成的代码跟最新规范不一致。加了 Webhook 触发 Resource 更新。</li>
</ul>

<h2>加分项</h2>
<p>MCP 的价值是<strong>规范即代码</strong>——接口规范不再只是文档，而是可以直接被 AI 消费的结构化数据。实际效果：CRUD 类接口的重复编码减少约 60%。但说实话，复杂业务逻辑 AI 生成的只是骨架，省的是模板代码的时间，不是架构设计的时间。</p>`,
  followups: [
    {
      q: "MCP与传统的API Gateway有什么区别？",
      answer: `<p>API Gateway 是运行时的请求路由和治理，MCP 是开发时的资源暴露和消费。Gateway 管的是"请求怎么转发"，MCP 管的是"数据怎么给 AI 用"。两者不冲突，可以在 Gateway 之上加 MCP Resource 层。</p>`
    },
    {
      q: "MCP如何处理资源的权限控制？",
      answer: `<p>MCP 协议本身不管权限，权限由底层实现控制。我们的做法是 MCP Server 部署在内网，通过网络隔离保证安全。Resource 层面用 tenant_id 过滤，不同租户看到不同的接口规范。</p>`
    },
    {
      q: "在你的项目中是如何应用MCP的？",
      answer: `<p>两个场景：一是 MCP 驱动的规范即代码（YAPI→MCP→AI 自动生成代码），二是 CoreAgent 平台层中工具的标准化注册。CoreAgent 的 CoreTool 接口设计受 MCP 思想启发——统一的工具定义 + 元数据管控。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q2m0v3l"] = {
  question: "请解释 RAG（Retrieval-Augmented Generation）的基本架构，以及它解决了LLM的什么问题？",
  level: "Foundational",
  why: "JD: 要求RAG检索增强能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>RAG 解决了 LLM 三个核心问题：<strong>知识过时</strong>（训练数据有截止日期）、<strong>幻觉</strong>（编造不存在的信息）、<strong>领域知识缺失</strong>（不了解企业内部数据）。</p>

<h3>基本架构</h3>
<pre>
用户提问 → 查询改写（可选）→ 检索 → 重排 → LLM 生成
              │                │        │
              ▼                ▼        ▼
           HyDE           Qdrant    Reranker
           查询扩展        + ES      精排
</pre>

<h3>我的实践：应急安全 RAG 知识库</h3>
<p>为 120 租户提供安全文档的智能检索与问答。核心架构：</p>
<ul>
<li><strong>双轨检索</strong>：Qdrant Dense 向量 + ES BM25 关键词，应用层 RRF 融合</li>
<li><strong>三层缓存</strong>：Query 缓存 → HyDE 缓存 → LLM 响应缓存，成本降 82%</li>
<li><strong>成果</strong>：Recall@10 97%，P99 720ms，单租户月成本 25 元</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>检索质量 vs 成本</strong>：HNSW 索引需要调参，Flat 索引零维护但数据量受限。我们的场景单租户 200~2000 份文档，Flat 完全够用，查询延迟 1ms。</li>
<li><strong>新租户冷启动</strong>：新租户文档少，检索质量差（Faithfulness 0.55）。通过租户级同义词库 + 冷启动保护期修复至 0.82。</li>
</ul>

<h2>加分项</h2>
<p>RAG 和微调不是互斥的。RAG 适合知识频繁更新的场景，微调适合固定领域知识。我们的安全文档每周更新，RAG 是正确选择。如果文档不常变，微调性价比更高。</p>`,
  followups: [
    {
      q: "RAG有哪些常见的失败模式？",
      answer: `<p>三种：检索失败（相关文档没被召回）、排序失败（相关文档排在后面）、生成失败（LLM 忽略了检索结果）。我们的双轨检索 + RRF 融合解决前两种，Prompt 约束（"只基于检索结果回答"）解决第三种。</p>`
    },
    {
      q: "如何评估RAG系统的检索质量？",
      answer: `<p>Recall@K（前 K 个结果中包含正确答案的比例）和 Faithfulness（回答是否基于检索结果）。我们 Recall@10 97%，Faithfulness 从 0.55 优化到 0.82+。</p>`
    },
    {
      q: "RAG和微调应该如何选择？",
      answer: `<p>看知识更新频率。更新频繁（周级）→ RAG，不常变（月级）→ 微调，两者都有 → RAG + 微调结合。我们安全文档每周更新，RAG 是正确选择。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1gj4i5y"] = {
  question: "向量检索中 Flat 索引和 HNSW 索引各有什么优缺点？如何选择？",
  level: "Core",
  why: "JD: 要求RAG技术理解",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>Flat 是暴力搜索，逐个比较所有向量；HNSW 是图索引，跳着找近邻。</p>

<h3>对比</h3>
<pre>
┌──────────┬──────────────────┬──────────────────────┐
│ 维度      │ Flat             │ HNSW                 │
├──────────┼──────────────────┼──────────────────────┤
│ 查询延迟  │ O(n)，数据量大慢  │ O(log n)，快          │
│ 召回率    │ 100%（精确）      │ ~95-99%（近似）       │
│ 内存      │ 低               │ 高（图结构额外开销）   │
│ 调参      │ 零参数            │ ef_construction, M   │
│ 适用规模  │ <100万            │ 100万+               │
└──────────┴──────────────────┴──────────────────────┘
</pre>

<h3>我的选择：Flat</h3>
<p>RAG 知识库单租户 200~2000 份文档，Flat 查询延迟 1ms，100% 召回率，零参数零维护。HNSW 在这个规模下优势不明显，反而需要调参（ef_construction、M 参数），调不好召回率反而下降。</p>
<p>选型原则：<strong>能用 Flat 就不用 HNSW</strong>。Flat 的 100% 召回率是 HNSW 永远达不到的，而且零运维成本。只有数据量到百万级才需要考虑 HNSW。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>Flat 的扩展性</strong>：单租户 2000 份文档没问题，但如果未来单租户到 10 万份，Flat 会变慢。预留了迁移路径：Qdrant 支持在线切换索引类型，业务层无感知。</li>
<li><strong>HNSW 调参陷阱</strong>：之前测试过 HNSW，ef_construction 设太低导致召回率只有 85%，比 Flat 的 100% 差很多。调参成本是隐性的。</li>
</ul>

<h2>加分项</h2>
<p>除了这两种，还有 IVF（倒排文件索引）、PQ（乘积量化）等。IVF 适合超大规模（亿级），PQ 适合内存受限场景。我们的场景规模小、对召回率要求高（安全文档不能漏），Flat 是最优解。</p>`,
  followups: [
    {
      q: "除了这两种，还有哪些向量索引方式？",
      answer: `<p>IVF（倒排文件索引，适合亿级数据）、PQ（乘积量化，压缩向量省内存）、ScaNN（Google 的 ANN 索引）。选型看三个维度：数据规模、延迟要求、召回率要求。我们的场景是小规模+高召回率，Flat 最合适。</p>`
    },
    {
      q: "如何处理向量检索中的维度灾难？",
      answer: `<p>高维向量检索效率下降是物理规律。解法：降维（PCA）、量化（PQ 将 768 维压缩到 64 维）、混合检索（向量+关键词融合，不完全依赖向量）。我们的 RRF 融合就是混合检索思路——向量检索兜语义，BM25 兜精确匹配。</p>`
    },
    {
      q: "混合检索（Hybrid Search）的融合策略有哪些？",
      answer: `<p>主流是 RRF（Reciprocal Rank Fusion）：对两个排序列表取倒数排名加权求和。优点是不需要归一化分数，简单有效。我们用 RRF 融合 Qdrant Dense + ES BM25，比单用向量检索 Recall@10 提升约 3%。</p>`
    }
  ]
};

window.PREPME_ANSWERS["quqdt5b"] = {
  question: "什么是 HyDE（Hypothetical Document Embeddings）？它在RAG中的作用是什么？",
  level: "Advanced",
  why: "JD: 要求RAG技术深度",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>HyDE 的思路是：先让 LLM 根据问题生成一个"假想答案"，用这个假想答案的向量去检索，而不是用原始问题的向量。因为假想答案的语义跟真实文档更接近，检索效果更好。</p>

<h3>流程</h3>
<pre>
原始问题: "应急预案多久更新一次？"
    ↓ LLM 生成假想答案
假想答案: "应急预案应每年至少更新一次..."
    ↓ Embedding
假想向量 → Qdrant 检索 → 找到真实文档
</pre>

<h3>在 RAG 知识库中的实践</h3>
<p>我们把 HyDE 作为缓存层——HyDE 缓存命中率 30%。同一个问题生成的假想答案是稳定的，所以缓存有效。缓存命中时直接用缓存的假想向量检索，跳过 LLM 生成步骤，延迟从 800ms 降至 220ms。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>额外延迟</strong>：HyDE 多一次 LLM 调用（生成假想答案），延迟增加 ~200ms。通过缓存解决——相同问题不再重复生成。</li>
<li><strong>假想答案质量</strong>：如果问题模糊，LLM 生成的假想答案可能跑偏，导致检索到不相关的文档。解法：HyDE 只用于缓存层，实际检索还是用原始问题 + 假想答案双路融合。</li>
</ul>

<h2>加分项</h2>
<p>HyDE 不是万能的。在问题明确、文档结构化的场景下效果好；在问题模糊、多义性强的场景下可能反效果。我们的做法是 HyDE 作为可选优化层，不作为唯一检索路径。</p>`,
  followups: [
    {
      q: "HyDE会带来哪些额外的延迟？",
      answer: `<p>一次 LLM 调用生成假想答案，约 200ms。我们通过缓存解决——相同问题的假想答案缓存命中率 30%，命中时直接跳过生成步骤。</p>`
    },
    {
      q: "HyDE在哪些场景下效果不好？",
      answer: `<p>问题模糊或多义性强时。比如"怎么处理"——处理什么？假想答案可能完全跑偏。这类问题不适合用 HyDE，应该用原始问题直接检索。</p>`
    },
    {
      q: "还有哪些查询改写技术？",
      answer: `<p>Query Expansion（同义词扩展）、Query Decomposition（复杂问题拆成子问题）、Step-back Prompting（先问一个更抽象的问题）。我们的 RAG 知识库用了租户级同义词库做 Query Expansion，解决新租户冷启动问题。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1tc33m1"] = {
  question: "如何设计一个生产级的RAG系统的缓存策略？",
  level: "Advanced",
  why: "JD: 要求性能优化能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>RAG 系统的缓存不能只缓存最终答案，要分层缓存。我们的三层缓存策略使 LLM 调用成本降 82%，平均延迟从 800ms 降至 220ms。</p>

<h3>三层缓存架构</h3>
<pre>
用户提问
  ↓
┌─────────────────────┐
│ Layer 1: Query 缓存  │ ← 完全相同的问题，直接返回缓存答案
│ 命中率 55%           │    （Redis，TTL 24h）
└────────┬────────────┘
         ↓ 未命中
┌─────────────────────┐
│ Layer 2: HyDE 缓存   │ ← 缓存假想答案的向量，跳过 LLM 生成
│ 命中率 30%           │    （Redis，TTL 12h）
└────────┬────────────┘
         ↓ 未命中
┌─────────────────────┐
│ Layer 3: LLM 响应缓存│ ← 相同检索结果+问题，缓存最终答案
│ 命中率 40%           │    （Redis，TTL 6h）
└────────┬────────────┘
         ↓ 未命中
      调用 LLM 生成
</pre>

<h3>设计要点</h3>
<ul>
<li><strong>缓存键设计</strong>：Query 缓存用问题原文 hash；HyDE 缓存用问题 hash；LLM 响应缓存用问题 hash + 检索结果 hash（因为不同文档组合可能产生不同答案）</li>
<li><strong>失效策略</strong>：文档更新时，清除该租户下所有缓存（因为检索结果变了）；租户级 TTL 不同，活跃租户短、冷租户长</li>
<li><strong>缓存一致性</strong>：文档更新后异步清除缓存，允许短暂的不一致（几秒内可能返回旧答案），换取更高的缓存命中率</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>缓存穿透</strong>：恶意用户用随机问题反复穿透缓存。解法：租户级 QPS 限流 + 布隆过滤器前置。</li>
<li><strong>缓存雪崩</strong>：大量缓存同时过期导致 LLM 瞬间压力暴增。解法：TTL 加随机偏移（±10%），错开过期时间。</li>
</ul>

<h2>加分项</h2>
<p>三层缓存的核心思想是<strong>在不同粒度上缓存</strong>——问题级、中间结果级、最终结果级。粒度越粗命中率越高，但失效成本也越高。根据业务特点选择合适的缓存粒度。</p>`,
  followups: [
    {
      q: "缓存命中率如何监控和优化？",
      answer: `<p>CoreAgent 的 AgentTracer 接入 Prometheus，每层缓存命中/未命中都有指标。监控看板在 Grafana 上，按租户、按时间维度展示。优化方向：分析未命中的查询，看是否有相似查询可以归一化。</p>`
    },
    {
      q: "缓存失效策略如何设计？",
      answer: `<p>两种失效：时间失效（TTL）和主动失效（文档更新时清除）。我们用 Redis 的 keyspace notification 监听文档更新事件，异步清除相关缓存。允许短暂不一致，换取更高命中率。</p>`
    },
    {
      q: "如何处理缓存一致性问题？",
      answer: `<p>我们接受最终一致性——文档更新后几秒内可能返回旧答案。对于安全文档这种更新频率低的场景，这个代价可以接受。如果要求强一致性，缓存粒度要更细（按文档 ID 缓存），但命中率会下降。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1j1uzoi"] = {
  question: "什么是 Prompt Engineering？设计一个好的Prompt有哪些关键原则？",
  level: "Foundational",
  why: "JD: 要求提示工程能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>Prompt Engineering 是通过设计输入文本引导 LLM 产生期望输出的技术。核心原则：让 LLM 不猜、不编、不跑偏。</p>

<h3>关键原则</h3>
<ol>
<li><strong>角色明确</strong>：先告诉 LLM "你是谁"。运维场景："你是一个运维诊断助手"；RAG 场景："你是一个安全知识问答助手"</li>
<li><strong>任务具体</strong>：不要"帮我分析一下"，要"分析最近 1 小时的错误日志，找出 Top 3 错误模式"</li>
<li><strong>输出格式约束</strong>：要求 JSON/表格/特定结构，减少自由发挥空间</li>
<li><strong>Few-Shot 示例</strong>：给 1-2 个输入输出示例，比长篇描述有效</li>
<li><strong>反向约束</strong>：明确说"不要做什么"，如"只基于检索结果回答，不要编造"</li>
</ol>

<h3>在 CoreAgent 中的实践</h3>
<p>CoreAgent 的 ContextManager 管理 Prompt 的 Token 预算分配：System Prompt 固定保留（不压缩），工具结果和推理历史按场景策略裁剪。不同业务场景用不同的 System Prompt——通过 ContextStrategy 决定优先级。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>Prompt 注入</strong>：用户在问题里嵌入"忽略之前的指令"。解法：用户输入和系统指令严格分离，用户输入用特殊标记包裹。</li>
<li><strong>Prompt 太长</strong>：System Prompt 写了 2000 Token，挤压了工具结果的空间。CoreAgent 的 ContextManager 做 Token 预算分配，System Prompt 占 20%，工具结果占 50%。</li>
</ul>

<h2>加分项</h2>
<p>Prompt 版本管理很重要。我们用 Git 管理 Prompt 模板，每次修改有 diff，可以回滚。Prompt 的效果评估用自动化测试——固定输入、检查输出是否符合预期。</p>`,
  followups: [
    {
      q: "Few-Shot和Zero-Shot各适用什么场景？",
      answer: `<p>Zero-Shot 适合任务简单、描述清晰的场景（如分类、摘要）。Few-Shot 适合输出格式复杂或有特殊要求的场景。我们的 RAG 问答用 Zero-Shot（Prompt 里写清规则即可），运维诊断用 Few-Shot（给一个完整的 Thought→Action→Observation 示例）。</p>`
    },
    {
      q: "如何处理Prompt注入攻击？",
      answer: `<p>三层防御：1）用户输入用特殊标记包裹，与系统指令隔离；2）CoreAgent 的 GuardRail 做输入校验，检测已知注入模式；3）输出端做格式校验，防止 LLM 输出意外内容。</p>`
    },
    {
      q: "Prompt的版本管理如何做？",
      answer: `<p>Git 管理，每次修改有 diff。Prompt 模板存在独立的配置文件里，跟代码一起走 CI/CD。效果评估用自动化测试——固定输入、检查输出。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q8lf40y"] = {
  question: "如何设计多轮对话的上下文管理策略？",
  level: "Core",
  why: "JD: 要求多轮对话编排",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>多轮对话的核心问题是 context window 有限，但对话历史在增长。必须决定什么保留、什么丢弃。</p>

<h3>CoreAgent 的 ContextManager</h3>
<p>平台层提供 Token 计数和溢出裁剪机制，业务层通过 ContextStrategy 决定优先级：</p>
<pre>
Token 预算分配（默认）:
├── System Prompt: 20%（固定保留，不压缩）
├── 工具结果:      50%（已预处理，体积可控）
└── 推理历史:      30%（Thought 链条）
</pre>
<p>溢出时按优先级裁剪：</p>
<ul>
<li><strong>运维场景</strong>：日志/指标 > Thought > 文档（诊断依赖最新数据）</li>
<li><strong>RAG 场景</strong>：文档 > Thought > 日志（回答要引用文档来源）</li>
</ul>

<h3>实现</h3>
<p>ContextStrategy 接口定义 priority() 方法，业务方决定内容优先级。ContextManager 按优先级从低到高裁剪，直到 Token 预算内。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>上下文窗口超限</strong>：5 轮 ReAct 后 context 超 4096 Token，LLM 开始丢信息。解法：PreProcessor 压缩工具返回值 + ContextManager 裁剪旧 Thought。</li>
<li><strong>对话断点续传</strong>：用户中途离开再回来，上下文丢失。解法：AgentSession 持久化到 Redis，恢复时重新加载。</li>
</ul>

<h2>加分项</h2>
<p>上下文管理不只是"裁剪"，更是"分配"。20% 给 System Prompt、50% 给工具结果、30% 给推理历史——这个比例是实测出来的。工具结果占比最高，因为 LLM 需要足够的事实数据才能做出准确推理。</p>`,
  followups: [
    {
      q: "上下文窗口超限时如何处理？",
      answer: `<p>CoreAgent 的 ContextManager 自动处理：先裁剪最旧的 Thought，再压缩旧的工具结果为一行摘要，最后裁剪 System Prompt 的非关键部分。优先级由业务层 ContextStrategy 决定。</p>`
    },
    {
      q: "如何实现对话的断点续传？",
      answer: `<p>AgentSession 持久化到 Redis，包含对话历史、工具调用记录、当前状态。用户恢复时从 Redis 加载，继续推理。TTL 设为 24 小时，超时自动清理。</p>`
    },
    {
      q: "多轮对话中的状态管理方案？",
      answer: `<p>CoreAgent 的 AgentSession 管理状态：对话历史（messages）、工具调用记录（toolCalls）、当前步骤（stepCount）。状态随 AgentExecutor 的 ReAct 循环更新，每步都写入 AgentTracer 用于可观测性。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qyw3wox"] = {
  question: "如何构建一个可复用的Prompt模板体系？",
  level: "Core",
  why: "JD: 要求可复用智能体应用",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>Prompt 模板不能硬编码在代码里。我们的做法是 Prompt 模板化 + 版本管理 + 效果评估。</p>

<h3>模板体系设计</h3>
<pre>
prompt-templates/
├── ops/
│   ├── diagnosis.system.txt    ← 运维诊断 System Prompt
│   └── diagnosis.fewshot.json  ← Few-Shot 示例
├── rag/
│   ├── qa.system.txt           ← RAG 问答 System Prompt
│   └── qa.constraints.json     ← 约束规则
└── common/
    └── output-format.json      ← 通用输出格式模板
</pre>

<h3>设计要点</h3>
<ol>
<li><strong>模板与代码分离</strong>：Prompt 存在独立文件里，代码通过模板引擎渲染（变量替换）</li>
<li><strong>继承与组合</strong>：common/ 放通用约束，业务 Prompt 继承并扩展</li>
<li><strong>版本管理</strong>：Git 管理，每次修改有 diff，可回滚</li>
<li><strong>效果评估</strong>：自动化测试——固定输入、检查输出是否符合预期</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>Prompt 膨胀</strong>：每个需求都往 Prompt 里加约束，最后 System Prompt 超 2000 Token。CoreAgent 的 ContextManager 做 Token 预算分配，System Prompt 占 20%，超了就裁剪非关键约束。</li>
<li><strong>版本回退</strong>：改了 Prompt 后效果变差，但不知道改了什么。Git 管理后每次修改有 diff，可以快速回滚。</li>
</ul>

<h2>加分项</h2>
<p>Prompt 模板的价值不只是复用，更是<strong>可测试</strong>。模板化之后可以写自动化测试：给定输入，检查 LLM 输出是否符合预期。这是 Prompt 质量保障的关键。</p>`,
  followups: [
    {
      q: "Prompt模板如何做版本管理？",
      answer: `<p>Git 管理，跟代码一起走 CI/CD。每次修改有 diff，可以回滚。Prompt 变更需要经过 Code Review，跟代码变更同等对待。</p>`
    },
    {
      q: "如何评估Prompt的效果？",
      answer: `<p>自动化测试：固定输入集，检查输出是否符合预期（格式、关键词、准确性）。人工评估：抽样检查 LLM 输出质量。A/B 测试：新旧 Prompt 对比效果。</p>`
    },
    {
      q: "Prompt模板的继承和组合如何设计？",
      answer: `<p>common/ 放通用约束（输出格式、安全规则），业务 Prompt 通过 include 机制继承通用约束并扩展业务规则。类似 Java 的接口继承——base 定义规范，子类实现细节。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qrb6kyl"] = {
  question: "SaaS多租户系统中，数据隔离有哪些常见方案？各有什么优缺点？",
  level: "Core",
  why: "JD: 要求SaaS系统开发能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>三种方案，隔离程度递增：</p>
<pre>
┌─────────────────┬────────────┬────────────┬──────────────┐
│ 方案             │ 隔离程度   │ 成本        │ 适用场景      │
├─────────────────┼────────────┼────────────┼──────────────┤
│ 共享库+行隔离    │ 低         │ 低          │ 中小租户      │
│ 独立 Schema      │ 中         │ 中          │ 中大租户      │
│ 独立数据库       │ 高         │ 高          │ 大客户/合规   │
└─────────────────┴────────────┴────────────┴──────────────┘
</pre>

<h3>我的实践：共享 Collection + Payload 过滤</h3>
<p>RAG 知识库用 Qdrant 共享 Collection，通过 tenant_id Payload 过滤实现租户隔离。应用层二次校验确保不跨租户访问。优势：新租户 onboarding 从 2 天压缩至 5 分钟（只需创建 Payload 标记，不需要建新 Collection）。</p>
<p>流程引擎用五层租户隔离：入口限流 → 分片调度 → 分层执行 → 实例配额 → 租户熔断。大客户独立 Lane，中小客户按套餐分组。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>行隔离的查询性能</strong>：共享表数据量大时，带 tenant_id 的查询变慢。解法：tenant_id 作为联合索引前缀。</li>
<li><strong>租户间资源抢占</strong>：某个租户的大量请求影响其他租户。CoreAgent 的 TenantCtrl 做租户级 Token 配额 + QPS 限流。</li>
</ul>

<h2>加分项</h2>
<p>选方案要看租户数量和合规要求。120 租户 + 无特殊合规 → 共享库够用；金融/医疗客户 → 独立数据库。我们的应急安全平台 120 租户用共享库，4 家世界 500 强客户走独立 Schema。</p>`,
  followups: [
    {
      q: "如何处理租户级别的资源限制？",
      answer: `<p>CoreAgent 的 TenantCtrl 做三层管控：Token 月度配额（防止成本失控）、QPS 令牌桶限流（防止流量洪峰）、工具调用频率限制（防止单工具滥用）。配额按租户套餐差异化配置。</p>`
    },
    {
      q: "新租户上线（onboarding）流程如何设计？",
      answer: `<p>RAG 知识库：创建 tenant_id 标记 + 初始化默认配置 + 文档导入，5 分钟完成。流程引擎：创建租户配置 + 分配分片资源 + 初始化流程模板，2 天压缩至 30 分钟。</p>`
    },
    {
      q: "如何实现租户级别的配置隔离？",
      answer: `<p>配置中心（Nacos）按租户命名空间隔离。公共配置放 default namespace，租户专属配置放 tenant_{id} namespace。启动时合并，租户配置覆盖公共配置。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q124q8u9"] = {
  question: "微服务架构下，如何设计服务间的熔断和降级策略？",
  level: "Core",
  why: "JD: 要求微服务架构能力",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>熔断是"发现下游挂了就别调了"，降级是"调不通就用兜底方案"。两者配合使用。</p>

<h3>Resilience4j 核心组件</h3>
<pre>
熔断器状态机:
CLOSED（正常）→ 失败率超阈值 → OPEN（熔断）
OPEN → 超时 → HALF_OPEN（试探）→ 成功 → CLOSED
                              → 失败 → OPEN
</pre>

<h3>在平台中的实践</h3>
<ul>
<li><strong>流程引擎</strong>：节点调用下游服务时熔断保护。失败率 > 50% 触发熔断，HALF_OPEN 时放行 10% 流量试探。</li>
<li><strong>CoreAgent</strong>：AgentExecutor 调用 LLM 和工具时都有超时和重试。工具调用超时由 ToolMeta.timeoutMs 控制，LLM 调用超时由 Spring AI 的 ChatClient 配置。</li>
<li><strong>优雅关闭</strong>：服务下线前等待当前推理完成（最多 30 秒），不接受新请求，已发出去的工具调用等回调。</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>熔断粒度太粗</strong>：整个服务一个熔断器，某个接口异常导致所有接口被熔断。解法：按接口粒度配置熔断器。</li>
<li><strong>降级返回值</strong>：降级返回空对象还是错误？我们选择返回"降级标识"——告诉调用方这是降级结果，不是正常结果，让调用方决定怎么处理。</li>
</ul>

<h2>加分项</h2>
<p>熔断不是万能的。熔断解决的是"快速失败"，但不解决"根本原因"。我们的做法是熔断触发后自动告警（接入 Prometheus），运维团队收到告警后排查根因。CoreAgent 的 AgentTracer 记录每次熔断的上下文，方便事后分析。</p>`,
  followups: [
    {
      q: "Resilience4j的核心组件有哪些？",
      answer: `<p>CircuitBreaker（熔断器）、RateLimiter（限流器）、Retry（重试）、Bulkhead（舱壁隔离）、TimeLimiter（超时控制）。我们主要用 CircuitBreaker + TimeLimiter，配合 Redis 令牌桶做限流。</p>`
    },
    {
      q: "熔断器的状态机是怎样的？",
      answer: `<p>三态：CLOSED（正常通过）→ 失败率超阈值 → OPEN（全部拒绝）→ 超时 → HALF_OPEN（放行部分请求试探）→ 成功 → CLOSED；失败 → OPEN。关键参数：失败率阈值、OPEN 持续时间、HALF_OPEN 放行比例。</p>`
    },
    {
      q: "如何设计优雅关闭（Graceful Shutdown）？",
      answer: `<p>三步：1）收到 SIGTERM，标记为 shutting down，不接受新请求；2）等待当前请求完成（最多 30 秒）；3）强制关闭剩余连接。CoreAgent 的 AgentExecutor 在 shutdown 时等待当前推理轮次完成。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1hpk50l"] = {
  question: "如何构建Agent系统的可观测性体系？需要监控哪些关键指标？",
  level: "Advanced",
  why: "JD: 要求可观测性建设",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>Agent 系统的可观测性比传统服务更复杂——不只是看请求成功/失败，还要看 LLM 每一步推理了什么。</p>

<h3>四支柱</h3>
<pre>
┌────────────┬──────────────────────────────────────────┐
│ 支柱       │ Agent 场景的关键指标                       │
├────────────┼──────────────────────────────────────────┤
│ Metrics    │ 调用量、延迟P99、Token消耗、缓存命中率     │
│ Logs       │ 每步推理的 Thought/Action/Observation      │
│ Traces     │ 一次推理的完整链路（AgentTrace）            │
│ Evaluation │ Faithfulness、Recall@K、回答准确率          │
└────────────┴──────────────────────────────────────────┘
</pre>

<h3>CoreAgent 的 AgentTracer</h3>
<p>纯平台组件，记录每次 Agent 调用的完整链路：traceId、tenantId、scene、每步的 Thought/Action/Observation、Token 消耗、延迟。接入 Prometheus + Grafana：</p>
<ul>
<li><code>agent_call_total{tenant, scene, status}</code> — 调用总量</li>
<li><code>agent_latency_seconds{tenant, scene, quantile}</code> — 延迟分位数</li>
<li><code>agent_token_usage_total{tenant}</code> — Token 消耗（成本追踪）</li>
<li><code>agent_tool_call_total{tool_name}</code> — 各工具调用频次</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>Trace 数据量大</strong>：每个推理步骤一条 Trace，120 租户每天产生大量数据。解法：采样率 10%，异常请求 100% 采样。</li>
<li><strong>成本可观测</strong>：LLM 调用按 Token 计费，必须按租户追踪。CoreAgent 的 TenantCtrl.recordUsage() 记录每次调用的 Token 消耗，Grafana 看板按租户展示。</li>
</ul>

<h2>加分项</h2>
<p>传统可观测性三支柱（Metrics/Logs/Traces）不够，Agent 系统需要第四支柱：<strong>Evaluation</strong>。Faithfulness、Recall@K 这些指标反映的是"Agent 答得对不对"，不只是"系统跑得快不快"。我们用自动化评估管线定期抽样检查。</p>`,
  followups: [
    {
      q: "Agent的调用链如何追踪？",
      answer: `<p>CoreAgent 的 AgentTracer 为每次调用生成 traceId，每步推理记录为 TraceStep（type、thought、toolName、toolOutput、latencyMs、tokensUsed）。整条链路通过 traceId 串联，接入 Jaeger/Prometheus 可视化。</p>`
    },
    {
      q: "如何定位Agent的性能瓶颈？",
      answer: `<p>看 Trace 的时间分布。常见瓶颈：LLM 调用（通常占 60-70% 延迟）、工具调用（占 20-30%）、预处理（占 5-10%）。优化方向：LLM 调用用小模型替代、工具调用并行化、预处理结果缓存。</p>`
    },
    {
      q: "LLM调用的成本如何监控和优化？",
      answer: `<p>CoreAgent 的 TenantCtrl 按租户追踪 Token 消耗，Prometheus 指标 + Grafana 看板。优化：三层缓存降本 82%、模型分级（简单任务用小模型）、Prompt 压缩（减少无用 Token）。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q5jbykc"] = {
  question: "AI Coding工具（如Cursor、Claude Code）如何提升研发效能？有哪些最佳实践？",
  level: "Core",
  why: "JD: 要求AI编程工具经验",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>AI Coding 工具不是"自动写代码"，是"加速开发循环"。核心价值：减少重复编码、加速理解陌生代码、辅助 Review。</p>

<h3>我的实践</h3>
<ul>
<li><strong>主力工具：Claude Code</strong>。用于代码生成、重构、Review。结合 MCP 协议实现"规范即代码"——YAPI 接口定义通过 MCP 暴露给 Claude Code，自动生成 Controller/Service/Request/Response，重复编码减少 60%。</li>
<li><strong>Cursor</strong>：用于快速原型和探索性编码。Tab 补全对重复模式（getter/setter、模板代码）效率提升明显。</li>
<li><strong>日常工作流</strong>：需求理解 → Claude Code 生成骨架代码 → 人工 Review + 调整 → Claude Code 辅助写测试 → 提交</li>
</ul>

<h3>最佳实践</h3>
<ol>
<li><strong>给 AI 足够上下文</strong>：MCP 暴露接口规范、项目 CLAUDE.md 描述代码规范、Context 文件记录架构决策</li>
<li><strong>AI 生成 + 人工 Review</strong>：AI 生成的代码必须 Review，重点关注安全、边界条件、错误处理</li>
<li><strong>小步迭代</strong>：不要让 AI 一次生成整个模块，按函数/类粒度生成，每步验证</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>AI 生成的代码不符合项目规范</strong>：命名风格、异常处理方式跟项目不一致。解法：CLAUDE.md 里写清规范，MCP 暴露现有代码作为参考。</li>
<li><strong>过度依赖</strong>：AI 生成的代码不理解就直接用，后面出问题找不到原因。解法：AI 生成的代码必须能解释清楚才能提交。</li>
</ul>

<h2>加分项</h2>
<p>AI Coding 的实际价值是<strong>减少重复编码的时间</strong>。CRUD 类接口、模板代码、测试用例这些 AI 生成得又快又好。但架构设计、复杂业务逻辑、性能调优这些，AI 目前帮不上太多忙。别指望 AI 能替代架构师，它更像一个高效的代码助手。</p>`,
  followups: [
    {
      q: "AI生成的代码如何做质量保障？",
      answer: `<p>三层：1）CLAUDE.md 定义代码规范，AI 生成时自动遵循；2）人工 Review 重点关注安全和边界条件；3）自动化测试覆盖——AI 生成代码的同时生成对应测试用例。</p>`
    },
    {
      q: "如何避免AI Coding的常见陷阱？",
      answer: `<p>最大陷阱是"不理解就用"。解法：AI 生成的代码必须能解释清楚才能提交。其次是"过度工程"——AI 喜欢加抽象层，要人工控制复杂度。</p>`
    },
    {
      q: "AI辅助开发的工作流如何设计？",
      answer: `<p>我们的工作流：需求理解 → MCP 暴露接口规范 → Claude Code 生成骨架 → 人工 Review → Claude Code 辅助写测试 → 提交。关键原则：AI 做生成，人做决策。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1j31pxs"] = {
  question: "你设计的流程引擎采用DAG编排+状态机驱动，这种架构有什么优势？",
  level: "Core",
  why: "CV: 自研流程引擎",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>DAG 负责"有哪些步骤、先后顺序是什么"，状态机负责"当前在哪个步骤、下一步怎么走"。两者配合解决了确定性业务流程的编排问题。</p>

<h3>架构</h3>
<pre>
DAG 定义:
  A(访客登记) → B(安全检查) → C(审批)
                ↘ D(自动放行) ↗
                (条件分支)

状态机:
  PENDING → RUNNING → WAITING → COMPLETED
              ↓          ↓
           FAILED    TIMEOUT → 人工介入
</pre>

<h3>优势</h3>
<ol>
<li><strong>可视化</strong>：DAG 天然可画成流程图，业务方能看懂</li>
<li><strong>条件分支</strong>：状态机在节点间做路由决策，支持 if/else/parallel</li>
<li><strong>异步回调</strong>：状态机在 WAITING 状态等待外部事件（人工审批、第三方回调），不阻塞线程</li>
<li><strong>故障恢复</strong>：状态持久化后，服务重启可以从断点恢复，不需要重新执行</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>循环依赖</strong>：DAG 不允许环，但业务方有时候会画出循环。启动时做拓扑排序检测，有环直接拒绝部署。</li>
<li><strong>状态持久化频率</strong>：每步都落盘太慢，攒一批再落盘有丢数据风险。最终选择主键更新同步落盘 + 乐观锁，性能可接受（单机 5000+ TPS）。</li>
</ul>

<h2>加分项</h2>
<p>DAG+状态机的组合跟 CoreAgent 的 AgentExecutor 有相似之处——都是任务编排。区别是流程引擎编排确定性节点，AgentExecutor 编排非确定性的 LLM 推理步骤。本质都是<strong>复杂任务的调度、编排与容错</strong>。</p>`,
  followups: [
    {
      q: "DAG的拓扑排序如何实现？",
      answer: `<p>Kahn 算法（BFS）：统计每个节点的入度，入度为 0 的入队，逐个处理并更新后继节点入度。如果处理完的节点数 < 总节点数，说明有环。</p>`
    },
    {
      q: "如何处理循环依赖？",
      answer: `<p>启动时拓扑排序检测，有环拒绝部署。业务层面：流程图不允许画环，如果需要"重试"逻辑，用状态机的 FAILED→PENDING 转换实现，不是 DAG 循环。</p>`
    },
    {
      q: "状态机的状态转换如何持久化？",
      answer: `<p>主键更新同步落盘 + 乐观锁。每次状态转换执行 UPDATE ... SET state=?, version=version+1 WHERE id=? AND version=?。乐观锁保证并发安全，同步落盘保证不丢数据。</p>`
    }
  ]
};

window.PREPME_ANSWERS["ql5k1k5"] = {
  question: "你提到参考Netty EventLoop设计分片调度器，这种设计解决了什么问题？",
  level: "Advanced",
  why: "CV: 流程引擎分片调度",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>核心问题是：多实例部署时，同一个流程实例的多个节点不能并发执行（会状态冲突），但不同实例可以并行。</p>

<h3>设计</h3>
<pre>
instance_id → hash → shard_id → EventLoop 线程
                                  (串行执行)
不同 shard → 不同 EventLoop → 并行执行
</pre>
<p>参考 Netty EventLoop：每个分片绑定一个线程，分片内的任务串行执行，不同分片并行。按 instance_id 哈希分片，天然保证同一个实例的所有节点在同一个分片内串行化，<strong>无需分布式锁</strong>。</p>

<h3>解决了什么问题</h3>
<ol>
<li><strong>无锁化</strong>：传统方案用分布式锁保证实例级串行，性能差。分片调度天然串行，不需要锁。</li>
<li><strong>可扩展</strong>：增加分片数就能提升吞吐。单机 16 分片，支撑 5000+ TPS。</li>
<li><strong>负载均衡</strong>：哈希分片均匀分布，不会出现热点。</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>分片热点</strong>：大客户的 instance_id 集中在某个分片，导致该分片过载。解法：大客户独立 Lane，不参与公共分片。</li>
<li><strong>分片数变更</strong>：分片数从 8 扩到 16，哈希映射变了，正在执行的任务会路由到错误分片。解法：分片数变更时暂停新任务，等存量任务执行完再切换。</li>
</ul>

<h2>加分项</h2>
<p>分片调度器的设计思想跟 CoreAgent 的 TenantCtrl 有呼应——都是通过哈希分片实现无锁化。不同的是流程引擎按 instance_id 分片保证实例级串行，TenantCtrl 按 tenant_id 分片保证租户级隔离。</p>`,
  followups: [
    {
      q: "分片数量如何确定？",
      answer: `<p>经验值：CPU 核心数的 1-2 倍。16 核机器 → 16-32 分片。太少并行度不够，太多线程切换开销大。我们单机 16 分片，5000+ TPS。</p>`
    },
    {
      q: "分片之间如何保证负载均衡？",
      answer: `<p>哈希分片本身是均匀的。大客户走独立 Lane 不参与公共分片，避免热点。监控每个分片的队列深度，如果某个分片持续偏高，调整哈希算法。</p>`
    },
    {
      q: "如何处理分片热点问题？",
      answer: `<p>两层解法：1）大客户独立 Lane，不跟中小客户混用分片；2）分片内再做优先级队列，紧急任务插队执行。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qp2ihyr"] = {
  question: "你提到五层租户隔离机制，请详细解释每一层的作用。",
  level: "Advanced",
  why: "CV: 流程引擎五层租户隔离",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>五层隔离从外到内层层递进，每一层解决不同的问题：</p>
<pre>
入口限流 → 分片调度 → 分层执行 → 实例配额 → 租户熔断
  │           │          │          │          │
  ▼           ▼          ▼          ▼          ▼
防流量洪峰  防跨租户    防大客户    防资源耗尽  防级联故障
            互相干扰    挤压小客户
</pre>

<h3>逐层解释</h3>
<ol>
<li><strong>入口限流</strong>：Redis 令牌桶，按租户限流。防止单个租户的流量洪峰打垮整个系统。</li>
<li><strong>分片调度</strong>：按 instance_id 哈希分片，不同租户的实例分散在不同分片，天然隔离。</li>
<li><strong>分层执行</strong>：大客户独立 Lane（专属线程池+专属资源），中小客户按套餐分组，冷租户 LRU 淘汰。大客户的流量洪峰不影响小客户。</li>
<li><strong>实例配额</strong>：每个租户同时运行的流程实例数有上限。超限排队，不拒绝。</li>
<li><strong>租户熔断</strong>：某个租户的失败率超过阈值，熔断该租户的所有请求，不影响其他租户。HALF_OPEN 时放行少量请求试探。</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>大客户独立 Lane 的资源浪费</strong>：大客户空闲时 Lane 闲置。解法：Lane 支持动态缩容，空闲时释放资源给公共池。</li>
<li><strong>冷租户 LRU 淘汰的数据一致性</strong>：淘汰后租户重新上线，状态可能不一致。淘汰前确保所有流程实例已完成或持久化。</li>
</ul>

<h2>加分项</h2>
<p>五层隔离的设计跟 CoreAgent 的 TenantCtrl 有共通之处——都是多租户场景下的资源管控。流程引擎侧重计算资源（线程池、分片），CoreAgent 侧重 LLM 资源（Token 配额、QPS 限流）。设计理念一脉相承。</p>`,
  followups: [
    {
      q: "大客户独立Lane如何实现？",
      answer: `<p>独立线程池 + 独立队列。大客户的流程实例路由到专属 Lane，不跟中小客户共享资源。Lane 支持动态扩缩容，根据负载自动调整。</p>`
    },
    {
      q: "冷租户LRU淘汰的具体策略？",
      answer: `<p>租户 30 天无活动触发 LRU 淘汰，释放其在分片中的资源。重新上线时从持久化状态恢复，冷启动保护期 24 小时（限流阈值降低）。</p>`
    },
    {
      q: "租户熔断的触发条件是什么？",
      answer: `<p>单租户 5 分钟内失败率 > 50% 触发熔断。HALF_OPEN 超时 30 秒，放行 10% 流量试探。连续 3 次成功恢复 CLOSED。</p>`
    }
  ]
};

window.PREPME_ANSWERS["ql9gpqn"] = {
  question: "你设计的双轨检索架构（Qdrant + ES + RRF融合）是如何工作的？为什么选择Flat索引而不是HNSW？",
  level: "Advanced",
  why: "CV: RAG知识库双轨检索",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>双轨检索是同时用两种方式检索，然后融合结果。Dense 向量管语义理解，BM25 关键词管精确匹配，两者互补。</p>

<h3>架构</h3>
<pre>
用户提问
  ↓ Embedding
┌─────────────┐  ┌─────────────┐
│ Qdrant      │  │ ES BM25     │
│ Dense 向量  │  │ 关键词匹配   │
│ Flat 索引   │  │             │
└──────┬──────┘  └──────┬──────┘
       │                │
       └─── RRF 融合 ───┘
              ↓
         Top-K 结果 → LLM 生成
</pre>

<h3>RRF 融合公式</h3>
<p><code>RRF_score = Σ 1/(k + rank_i)</code>，k 通常取 60。两个排序列表中排名都靠前的文档得分最高。</p>

<h3>为什么选 Flat</h3>
<p>单租户 200~2000 份文档，Flat 查询延迟 1ms，100% 召回率，零参数零维护。HNSW 在这个规模下优势不明显，反而需要调参（ef_construction、M），调不好召回率反而下降。Flat 的 100% 召回率是 HNSW 永远达不到的。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>RRF 参数调优</strong>：k 值太大，所有文档得分趋同；k 值太小，排名靠后的文档被过度惩罚。k=60 是经验值，实测效果最好。</li>
<li><strong>Embedding 模型选择</strong>：用 BGE 而不是 OpenAI Embedding，因为本地部署场景数据不出域。</li>
</ul>

<h2>加分项</h2>
<p>双轨检索的价值不只是"两种检索取并集"，而是<strong>互补</strong>。用户问"应急预案更新频率"——向量检索能找到语义相关的文档，BM25 能精确匹配"应急预案"这个关键词。单用任何一种都有漏召回的风险。</p>`,
  followups: [
    {
      q: "RRF融合的参数如何调优？",
      answer: `<p>k=60 是经验值。调优方法：准备一组标注好的 query-document 对，遍历 k 值（10-100），选 Recall@K 最高的。我们测过 k=60 最优。</p>`
    },
    {
      q: "Flat索引在数据量增大后如何处理？",
      answer: `<p>预留了迁移路径：Qdrant 支持在线切换索引类型，业务层无感知。如果单租户到 10 万份文档，切换到 HNSW。当前 2000 份文档，Flat 完全够用。</p>`
    },
    {
      q: "有没有考虑过其他向量数据库？",
      answer: `<p>考虑过 Milvus 和 Weaviate。选 Qdrant 原因：Rust 实现性能好、REST API 简单、支持 Payload 过滤（租户隔离）。Milvus 功能更强但运维复杂，我们的场景不需要。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1dbsvq1"] = {
  question: "你提到三层缓存策略使LLM调用成本降82%，请详细解释这三层缓存的设计。",
  level: "Advanced",
  why: "CV: RAG知识库三层缓存",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>三层缓存在不同粒度上缓存，粒度越粗命中率越高：</p>
<pre>
Layer 1: Query 缓存 (命中率 55%)
  → 完全相同的问题，直接返回缓存答案
Layer 2: HyDE 缓存 (命中率 30%)
  → 缓存假想答案向量，跳过 LLM 生成步骤
Layer 3: LLM 响应缓存 (命中率 40%)
  → 相同检索结果+问题，缓存最终答案
</pre>
<p>三层叠加后，LLM 调用次数只剩 18%，成本降 82%。平均延迟从 800ms 降至 220ms。</p>

<h3>缓存键设计</h3>
<ul>
<li>Query 缓存：<code>hash(tenant_id + question)</code></li>
<li>HyDE 缓存：<code>hash(tenant_id + question)</code>（假想答案稳定）</li>
<li>LLM 响应缓存：<code>hash(tenant_id + question + top_k_doc_ids)</code>（文档变了答案可能变）</li>
</ul>

<h3>失效策略</h3>
<p>文档更新时，清除该租户下所有缓存（异步清除，允许短暂不一致）。TTL 加随机偏移（±10%）防止缓存雪崩。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>缓存穿透</strong>：随机问题反复穿透。解法：租户级 QPS 限流。</li>
<li><strong>缓存一致性</strong>：文档更新后缓存没清，返回旧答案。解法：Redis keyspace notification 监听更新事件，异步清除。</li>
</ul>

<h2>加分项</h2>
<p>三层缓存的核心思想：在<strong>问题级、中间结果级、最终结果级</strong>三个粒度上缓存。粒度越粗命中率越高，但失效成本也越高。根据业务特点选择合适的粒度组合。</p>`,
  followups: [
    {
      q: "缓存命中率分别是如何统计的？",
      answer: `<p>CoreAgent 的 AgentTracer 接入 Prometheus，每层缓存命中/未命中都有 counter 指标。Grafana 看板按租户、时间维度展示。定期分析未命中查询，优化缓存策略。</p>`
    },
    {
      q: "缓存失效策略如何设计？",
      answer: `<p>两种：TTL 时间失效 + 文档更新主动清除。TTL 加随机偏移防雪崩。文档更新时异步清除该租户所有缓存，允许短暂不一致。</p>`
    },
    {
      q: "如何处理缓存一致性问题？",
      answer: `<p>接受最终一致性——文档更新后几秒内可能返回旧答案。安全文档更新频率低，这个代价可接受。强一致性需要按文档 ID 缓存，命中率会下降。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q8qnnj9"] = {
  question: "你提到新租户冷启动检索质量差（Faithfulness 0.55→0.82），这个问题的根因是什么？如何解决的？",
  level: "Advanced",
  why: "CV: RAG知识库Bad Case治理",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>根因：新租户文档少（几十份），用户的提问用词跟文档用词不匹配。比如用户问"着火了怎么办"，文档里写的是"火灾应急处置流程"——语义相近但关键词不同，BM25 检索不到。</p>

<h3>解决方案</h3>
<ol>
<li><strong>租户级同义词库</strong>：为每个租户维护一份同义词映射（"着火"→"火灾"、"断电"→"停电"）。Query 阶段做同义词扩展，提高 BM25 召回率。</li>
<li><strong>冷启动保护期</strong>：新租户上线前 30 天，检索策略放宽（向量相似度阈值降低、BM25 取更多结果），宁可多召回不可漏召回。30 天后根据实际查询日志收紧策略。</li>
<li><strong>查询日志反馈</strong>：记录用户查询和点击的文档，自动补充同义词库。用户搜"断电"但点了"停电"的文档 → 自动添加"断电"→"停电"映射。</li>
</ol>
<p>效果：Faithfulness 从 0.55 提升至 0.82。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>同义词库维护成本</strong>：手动维护 120 个租户的同义词库不现实。解法：种子同义词（行业通用）+ 自动学习（查询日志反馈）。</li>
<li><strong>检索策略放宽的副作用</strong>：放宽阈值后不相关文档也召回了，LLM 被干扰。解法：Reranker 精排过滤不相关结果。</li>
</ul>

<h2>加分项</h2>
<p>冷启动问题的本质是<strong>数据稀疏</strong>。同义词库是"人工补充语义"，查询日志反馈是"自动学习语义"。两者结合解决冷启动，随着数据积累逐步过渡到正常策略。</p>`,
  followups: [
    {
      q: "Faithfulness指标是如何计算的？",
      answer: `<p>Faithfulness = LLM 回答中有多少内容能在检索结果中找到依据。计算方式：把回答拆成句子，每句检查是否有对应的支持文档。0.55 意味着 45% 的回答内容没有文档支持（可能是幻觉）。</p>`
    },
    {
      q: "同义词库如何构建和维护？",
      answer: `<p>三层：1）行业通用同义词（应急管理术语）；2）租户专属同义词（从文档中提取高频词对）；3）查询日志自动学习（用户搜索→点击反馈）。</p>`
    },
    {
      q: "冷启动保护期的具体机制是什么？",
      answer: `<p>新租户上线 30 天内：向量相似度阈值从 0.8 降至 0.6，BM25 Top-K 从 10 增至 20，Reranker 做最终过滤。30 天后根据查询日志自动收紧。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q5s1191"] = {
  question: "你提到异常流量攻击导致LLM费用暴涨300%，是如何发现和解决的？",
  level: "Core",
  why: "CV: RAG知识库安全防护",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>发现：Grafana 监控看板显示某租户的 LLM 调用量在 2 小时内暴涨 300%，而该租户平时调用量很低。排查发现是有人用脚本高频调用问答接口。</p>

<h3>三层防御</h3>
<pre>
┌─────────────────────────────────────┐
│ Layer 1: 租户级 QPS 限流            │ ← Redis 令牌桶
│ 单租户 QPS 上限 10，超限直接拒绝    │
├─────────────────────────────────────┤
│ Layer 2: 缓存兜底                   │ ← 相同问题直接返回缓存
│ 恶意请求的重复问题被缓存拦截        │
├─────────────────────────────────────┤
│ Layer 3: Token 配额                 │ ← 月度上限
│ 超配额拒绝，防止成本失控            │
└─────────────────────────────────────┘
</pre>

<h3>处理过程</h3>
<ol>
<li><strong>紧急止血</strong>：对该租户临时降 QPS 到 1</li>
<li><strong>排查根因</strong>：分析访问日志，发现是脚本高频调用，非正常用户行为</li>
<li><strong>长期方案</strong>：CoreAgent 的 TenantCtrl 实现三层防御，按租户差异化配置</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>正常流量 vs 攻击流量</strong>：不能简单按 QPS 判断，大客户正常业务量也高。解法：按租户历史基线动态调整阈值，偏离基线 3 倍触发告警。</li>
<li><strong>限流误伤</strong>：QPS 限流太严导致正常用户被拒。解法：令牌桶允许短时突发，长期均值受控。</li>
</ul>

<h2>加分项</h2>
<p>安全防护不能只靠限流，还要有<strong>成本可观测性</strong>。CoreAgent 的 TenantCtrl 按租户追踪 Token 消耗，Grafana 看板实时展示，异常时自动告警。防得住的前提是看得见。</p>`,
  followups: [
    {
      q: "如何区分正常流量和攻击流量？",
      answer: `<p>按租户历史基线动态调整。正常大客户 QPS 高但稳定，攻击流量是突增。解法：偏离基线 3 倍触发告警，人工确认后决定是否限流。</p>`
    },
    {
      q: "Token配额如何设计？",
      answer: `<p>按租户套餐差异化：基础版 10 万 Token/月，专业版 100 万，企业版不限。超配额拒绝请求，提前 80% 时告警提醒。</p>`
    },
    {
      q: "如何防止租户之间的资源抢占？",
      answer: `<p>CoreAgent 的 TenantCtrl 三层隔离：QPS 限流（防流量洪峰）+ Token 配额（防成本失控）+ 工具调用频率限制（防单工具滥用）。配额按租户套餐差异化配置。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qy4ne4d"] = {
  question: "你在平台中设计了Agent能力层，支撑运维自愈和业务智能化两个场景。请介绍整体架构设计。",
  level: "Core",
  why: "CV: Agent能力层架构",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>在 Spring AI 之上封装 CoreAgent 平台层，解决生产环境中"可靠调用 LLM"的问题。核心设计理念：<strong>平台层解决"怎么调 LLM"，业务层解决"用什么工具、什么策略"</strong>。</p>

<h3>CoreAgent 七模块架构</h3>
<pre>
┌──────────────── CoreAgent 平台层 ────────────────────┐
│                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ ToolRegistry │  │ PreProcessor │  │ Context    │ │
│  │ 工具注册中心  │  │ 返回值预处理  │  │ Manager    │ │
│  │              │  │              │  │ 窗口管理   │ │
│  └──────────────┘  └──────────────┘  └────────────┘ │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ GuardRail    │  │ TenantCtrl   │  │ Agent      │ │
│  │ 安全护栏      │  │ 租户管控      │  │ Tracer     │ │
│  └──────────────┘  └──────────────┘  └────────────┘ │
│  ┌──────────────────────────────────────────────┐   │
│  │         AgentExecutor (ReAct 引擎)            │   │
│  └──────────────────────────────────────────────┘   │
├──────────────────────────────────────────────────────┤
│  Spring AI 框架层（Function Calling / ChatClient）    │
├──────────────────────────────────────────────────────┤
│  基础设施（Qwen本地 / Qdrant / ES / Redis / Prometheus）│
└──────────────────────────────────────────────────────┘
</pre>

<h3>平台层 vs 业务层</h3>
<p>7 个模块中 4 个需要业务实现接口，3 个纯平台复用：</p>
<ul>
<li><strong>ToolRegistry</strong>：平台提供注册中心，业务实现 CoreTool 接口。运维注册日志/指标/重启工具，RAG 注册向量/BM25/精排工具。</li>
<li><strong>PreProcessor</strong>：平台提供路由链，业务实现预处理逻辑。运维做日志聚合去重，RAG 做文档截断。</li>
<li><strong>ContextManager</strong>：平台提供 Token 计数和裁剪机制，业务通过 ContextStrategy 决定优先级。</li>
<li><strong>GuardRail</strong>：平台提供检查框架和人工确认服务，业务通过 RiskRule 定义风险等级。</li>
<li><strong>TenantCtrl</strong>：纯平台，Token 配额 + QPS 限流 + 成本追踪。</li>
<li><strong>AgentTracer</strong>：纯平台，调用链追踪，接入 Prometheus。</li>
<li><strong>AgentExecutor</strong>：纯平台，ReAct 推理引擎。</li>
</ul>

<h3>两个业务场景</h3>
<ul>
<li><strong>运维自愈</strong>：故障诊断→自动修复。夜间人工介入率从 100% 降至 15%，MTTR 从 45 分钟压缩至 8 分钟。</li>
<li><strong>RAG 问答</strong>：Agent 化检索生成。Faithfulness 0.72→0.89（Multi-Agent 协同后）。</li>
<li><strong>运营数据查询</strong>：运营人员用自然语言查到访人数、Token 用量、数据用量，不需要写 SQL。接入只需实现 5 个查询工具 + 1 个预处理器，不改平台代码。</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>平台层边界不清</strong>：一开始把太多业务逻辑放进平台层，导致平台层臃肿。后来严格分离——平台层只做调度和管控，业务逻辑全部下沉到接口实现。</li>
<li><strong>新场景接入成本</strong>：最初接入新场景要改平台代码。改为接口化后，新场景只需实现 4 个接口 + @Component 注入，不碰平台代码。</li>
</ul>

<h2>加分项</h2>
<p>CoreAgent 的设计思路跟流程引擎有相似之处——都是任务编排。流程引擎编排确定性节点（DAG），CoreAgent 编排非确定性推理步骤（ReAct）。两者共享同一套多租户隔离体系和可观测性基础设施，复用了不少流程引擎积累的工程经验。</p>`,
  followups: [
    {
      q: "Agent的推理链路是如何设计的？",
      answer: `<p>CoreAgent 的 AgentExecutor 执行 ReAct 循环：Thought（LLM 决策）→ Action（Function Calling 调用工具）→ Observation（工具返回值经 PreProcessor 预处理后作为上下文）→ 循环或终止。最多 5 轮，防死循环。</p>`
    },
    {
      q: "工具编排的统一Schema规范是什么样的？",
      answer: `<p>CoreAgent 的 CoreTool 接口：name()、description()、inputSchema()、execute()。ToolMeta 元数据承载风险等级、租户可见性、超时配置。业务方实现接口，平台自动注册到 Spring AI。</p>`
    },
    {
      q: "为什么选择本地部署Qwen而不是用云端API？",
      answer: `<p>TOB 客户安全合规要求数据不出域。本地部署 Qwen 模型，数据在客户环境内闭环。代价是推理速度比云端慢，通过模型分级（简单任务小模型）和缓存优化延迟。</p>`
    },
    {
      q: "为什么选Spring AI而不是LangGraph/LangChain？",
      answer: `<p>技术上，基于 LangGraph 封装确实是更好的选择——编排能力更强，社区更成熟。但三个约束让我们选了 Spring AI 自研：</p>
<p><strong>第一，运行时隔离</strong>。LangGraph 是 Python，我们平台是 Java。跨语言调用每次 Agent 调用多一次网络延迟，还要维护两套运行时（两套部署流水线、两套监控、两套依赖管理）。</p>
<p><strong>第二，团队</strong>。5 个 Java 工程师维护一个 Python 生产服务，长期风险大。排查线上问题、性能调优、依赖升级，没有 Python 工程化经验兜不住。</p>
<p><strong>第三，场景复杂度</strong>。我们当前是 ReAct 线性循环 + 固定流水线多 Agent，LangGraph 的 State Graph 用不上——是用大炮打蚊子。</p>
<p>如果重新来过，我会考虑两个方案：一是用 LangGraph 做独立的 Agent 服务，Java 平台通过 API 调用；二是等 Spring AI 的编排能力成熟。目前我们选了第二条路，自己在 Spring AI 上补编排能力（CoreAgent）。</p>
<p>说实话，CoreAgent 的编排能力确实不如 LangGraph。但 CoreAgent 有 LangGraph 没有的东西——多租户隔离、成本管控、工具返回值预处理。这是 SaaS 场景的刚需，LangGraph 不管这些。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q3epkq2"] = {
  question: "你提到使用了多Agent协同架构（Retriever/Reranker/Writer），这种设计解决了什么问题？",
  level: "Advanced",
  why: "CV: Multi-Agent协同",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>单 Agent 做"检索+重排+生成"，职责太杂，每个环节都不够精细。拆成三个 Agent 各司其职，Faithfulness 从 0.72 提升到 0.89，代价是延迟从 300ms 增到 450ms。</p>

<h3>架构</h3>
<pre>
用户提问
  ↓
┌──────────────┐
│ Retriever    │ ← 检索：双轨检索（Qdrant+ES+RRF）
│ Agent        │   输出：候选文档集
└──────┬───────┘
       ↓
┌──────────────┐
│ Reranker     │ ← 精排：语义相关性重排序
│ Agent        │   输出：Top-K 文档
└──────┬───────┘
       ↓
┌──────────────┐
│ Writer       │ ← 生成：基于精排结果生成答案
│ Agent        │   输出：最终回答 + 引用来源
└──────────────┘
</pre>

<h3>效果</h3>
<ul>
<li>单 Agent Faithfulness: 0.72</li>
<li>三 Agent 协同: 0.89（+23%）</li>
<li>延迟: 300ms → 450ms（+50%）</li>
<li>准确性收益 > 延迟代价</li>
</ul>

<h3>为什么有效</h3>
<p>单 Agent 的问题是 LLM 同时处理检索和生成，注意力分散。拆分后每个 Agent 的 Prompt 更聚焦：Retriever 只关心"找到相关文档"，Reranker 只关心"排序准确性"，Writer 只关心"生成质量"。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>信息传递丢失</strong>：Retriever 的检索理由没传给 Writer，Writer 无法溯源。解法：每个 Agent 输出结构化摘要，作为下一个 Agent 的输入上下文。</li>
<li><strong>延迟叠加</strong>：三个 Agent 串行，延迟翻三倍。解法：Retriever 和 Reranker 的工具调用并行化，总延迟控制在 450ms。</li>
</ul>

<h2>加分项</h2>
<p>Multi-Agent 不是万能的。职责差异大（检索 vs 生成）适合拆分，职责相似（两个检索）不适合。我们试过拆成 5 个 Agent，延迟翻 5 倍但准确性只提升 3%，不划算。3 个是最优平衡点。</p>`,
  followups: [
    {
      q: "多个Agent之间如何通信？",
      answer: `<p>串行编排，每个 Agent 的输出作为下一个 Agent 的输入。输出是结构化摘要（不是原始文本），包含检索结果、排序理由、关键信息。通过 AgentSession 传递，不需要额外通信机制。</p>`
    },
    {
      q: "如何处理Agent之间的状态同步？",
      answer: `<p>不需要复杂的状态同步——串行编排，前一个完成才启动下一个。每个 Agent 的输出是自包含的摘要，不依赖全局状态。这是固定流水线的优势：简单、可预测。</p>`
    },
    {
      q: "单Agent和多Agent架构如何选择？",
      answer: `<p>看职责差异和延迟预算。职责差异大（检索 vs 生成）→ 拆分；职责相似 → 不拆。延迟预算充裕 → 可以拆；延迟敏感 → 单 Agent。我们 3 Agent 是最优平衡点（Faithfulness 0.89，延迟 450ms）。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qxnfan3"] = {
  question: "你提到夜间告警人工介入率从100%降至15%，这个数据是如何统计的？有哪些优化手段？",
  level: "Core",
  why: "CV: Agent运维自愈效果",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>统计方式：夜间（20:00-08:00）所有告警中，需要人工介入处理的比例。</p>

<h3>统计口径</h3>
<pre>
总告警数: 100 条/晚
├── Agent 自动处理: 85 条（85%）
│   ├── 自动诊断+修复: 60 条
│   └── 自动诊断+建议: 25 条
└── 人工介入: 15 条（15%）
    ├── 高风险操作（需确认）: 8 条
    └── Agent 无法诊断: 7 条
</pre>

<h3>优化手段</h3>
<ol>
<li><strong>工具集扩充</strong>：从 3 个工具扩到 8 个，覆盖更多诊断场景。之前只能查日志，后来加了指标监控、配置检查、依赖服务健康检查。</li>
<li><strong>Prompt 优化</strong>：Few-Shot 示例从 1 个增到 3 个，覆盖更多故障模式。LLM 诊断准确率从 60% 提升到 85%。</li>
<li><strong>工具返回值预处理</strong>：CoreAgent 的 PreProcessor 对日志聚合去重、指标趋势提取，LLM 不再被原始数据干扰，推理准确率提升。</li>
<li><strong>缓存常见故障模式</strong>：高频告警（如 CPU 飙高、磁盘满）的诊断路径缓存，直接执行已验证的修复方案。</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>剩 15% 是什么</strong>：高风险操作（服务重启、配置变更）必须人工确认，这类无法自动化。Agent 无法诊断的复杂故障（多因素叠加）也需人工。</li>
<li><strong>统计口径争议</strong>：Agent 给出建议但人工确认执行，算"自动"还是"人工"？我们定义：Agent 完成诊断+给出方案 = 自动处理，人工只需确认执行。</li>
</ul>

<h2>加分项</h2>
<p>100%→15% 的核心不是"让 Agent 替代人"，而是<strong>把人从重复劳动中释放</strong>。85% 的告警是常见故障，Agent 可以自动处理；15% 是复杂或高风险的，人才需要介入。</p>`,
  followups: [
    {
      q: "剩15%的告警是什么类型？",
      answer: `<p>两类：1）高风险操作（服务重启、配置变更）——CoreAgent 的 GuardRail 要求人工确认；2）多因素叠加的复杂故障——Agent 诊断不出来，需要人分析。</p>`
    },
    {
      q: "如何处理Agent无法自愈的情况？",
      answer: `<p>Agent 诊断失败时输出"无法确定根因"，附上已排查的信息和建议的下一步。人工接手时不需要从零开始，可以直接看 Agent 的推理链路（AgentTracer 记录了每步 Thought/Action/Observation）。</p>`
    },
    {
      q: "如何持续优化Agent的自愈能力？",
      answer: `<p>两个方向：1）扩充工具集——新工具覆盖新场景；2）查询日志反馈——人工处理的案例反哺 Prompt 和同义词库。每季度做一次 Bad Case 分析，针对性优化。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q4rzaf0"] = {
  question: "你提到工具返回值做结构化预处理，避免撑爆context window。具体是怎么做的？",
  level: "Advanced",
  why: "CV: Agent工具编排优化",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>CoreAgent 的 PreProcessor 是平台层组件，解决的问题是：工具原始返回值（几万条日志）直接塞进 context window 会撑爆或稀释关键信息。</p>

<h3>设计：平台层 + 业务层分离</h3>
<pre>
平台层: PreProcessorChain（路由链）
  按 toolName 匹配到对应处理器
  无匹配时透传原始结果

业务层: 具体预处理实现
  LogPreProcessor   → 日志聚合去重
  MetricPreProcessor → 指标趋势提取
  DocPreProcessor    → 文档截断+引用提取
</pre>

<h3>具体实现</h3>
<ul>
<li><strong>日志预处理</strong>：按错误模式聚合（相同 stack trace 合并计数），取最近 N 条 + 各模式计数。原始 5000 行 → ~20 行摘要。</li>
<li><strong>指标预处理</strong>：提取当前值、5分钟趋势、是否超阈值。原始 JSON dump → 结构化描述。</li>
<li><strong>文档预处理</strong>：截断到指定长度，保留引用来源。原始长文档 → 精华摘要。</li>
</ul>

<h3>效果</h3>
<p>预处理后 LLM 的推理准确率提升——因为不被无关信息干扰，注意力集中在关键数据上。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>信息损失</strong>：聚合去重可能丢失细节。解法：原始数据存日志系统，LLM 只看摘要，需要细节时可以再查。</li>
<li><strong>预处理延迟</strong>：大量日志的聚合去重本身有延迟。解法：异步预处理，结果缓存。</li>
</ul>

<h2>加分项</h2>
<p>PreProcessor 的核心思想是<strong>给 LLM 看摘要，不给 LLM 看原始数据</strong>。这不只是省 Token，更是提升推理质量——LLM 面对结构化摘要比面对原始 JSON dump 推理更准确。</p>`,
  followups: [
    {
      q: "日志聚合去重的策略是什么？",
      answer: `<p>按 errorPattern（异常类名+消息前 100 字符）聚合。相同 pattern 的日志合并计数，只保留最近一条的完整信息。原始 5000 行 → ~20 行摘要。</p>`
    },
    {
      q: "指标趋势提取是如何实现的？",
      answer: `<p>取最近 5 分钟的数据点，计算斜率判断趋势（上升/下降/平稳），标注是否超阈值。输出格式："CPU 使用率 85%，过去 5 分钟上升趋势，状态: 异常"。</p>`
    },
    {
      q: "如何平衡信息损失和context window限制？",
      answer: `<p>分级策略：最近 3 轮工具结果保留完整，更早的压缩为一行摘要。关键信息（错误模式、异常指标）始终保留，普通日志可以压缩。CoreAgent 的 ContextManager + ContextStrategy 控制这个平衡。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qum4wqd"] = {
  question: "你提到通过MCP协议打通YAPI接口规范与AI代码生成链路。请详细介绍这个方案。",
  level: "Core",
  why: "CV: MCP驱动的规范即代码",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>核心理念：接口规范不只是文档，而是可以直接被 AI 消费的结构化数据。</p>

<h3>链路</h3>
<pre>
YAPI 接口定义
  ↓ 同步
MCP Server（Resource 暴露）
  ↓ MCP 协议
Claude Code（读取规范）
  ↓ 自动生成
Controller / Service / Request / Response
</pre>

<h3>实现细节</h3>
<ol>
<li><strong>MCP Resource 定义</strong>：按模块分组，每个 Resource 对应一个 YAPI 接口分组，包含接口路径、请求参数、响应结构。</li>
<li><strong>自动同步</strong>：YAPI 接口更新后 Webhook 触发 MCP Resource 更新，保证规范实时性。</li>
<li><strong>AI 生成</strong>：Claude Code 读取 MCP Resource，自动生成 Controller（路由）、Service（业务逻辑骨架）、Request/Response（DTO）。</li>
</ol>

<h3>效果</h3>
<p>重复编码工作量减少约 60%。接口规范成为 Single Source of Truth——改 YAPI 就是改代码，不会出现文档和代码不一致的问题。</p>

<h2>踩过的坑</h2>
<ul>
<li><strong>Resource 粒度</strong>：一开始整个 YAPI 项目作为一个 Resource，AI 读取时 context 太大。改成按接口分组。</li>
<li><strong>生成质量</strong>：AI 生成的 Service 层只有骨架，业务逻辑需要人工填充。这是预期的——AI 做重复工作，人做业务决策。</li>
</ul>

<h2>加分项</h2>
<p>这个方案的本质是<strong>把规范变成可执行的代码模板</strong>。MCP 协议是桥梁——它让 AI 能"读懂"接口规范，而不只是"看到"一段文本。实际效果：CRUD 类接口重复编码减少约 60%，但复杂业务逻辑还是得人写。</p>`,
  followups: [
    {
      q: "MCP的Resource是如何定义的？",
      answer: `<p>按 YAPI 接口分组定义。每个 Resource 包含：接口路径、HTTP 方法、请求参数 JSON Schema、响应结构 JSON Schema。用 MCP 协议的 Resource 格式暴露，Claude Code 可以直接读取。</p>`
    },
    {
      q: "AI生成的代码质量如何保障？",
      answer: `<p>三层：1）MCP Resource 提供完整的接口规范，AI 不会猜；2）CLAUDE.md 定义项目代码规范，AI 自动遵循；3）人工 Review 重点关注业务逻辑部分。</p>`
    },
    {
      q: "这个方案的局限性是什么？",
      answer: `<p>只适合 CRUD 类接口。复杂业务逻辑（多表关联、状态机流转）AI 生成的只是骨架，需要大量人工补充。另外 MCP Resource 需要维护同步，有运维成本。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qqjrmq2"] = {
  question: "你设计的API网关基于Netty实现，日调用量7000W，单机7K并发，是如何实现的？",
  level: "Core",
  why: "CV: API网关项目",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>选 Netty 而不是 Zuul 的核心原因：Netty 是异步非阻塞的，Zuul 1.0 是阻塞的。在高并发场景下，Netty 的线程模型更高效。</p>

<h3>架构</h3>
<pre>
客户端 → Nginx → Netty 网关集群 → 后端服务（Dubbo）
              ↓
         网关控制台（服务发现、路由管理）
              ↓
         阿里云日志服务（调用统计）
</pre>

<h3>核心技术点</h3>
<ul>
<li><strong>Netty EventLoop</strong>：Boss 线程接收连接，Worker 线程处理 I/O。单机 7K 并发，线程数控制在 CPU 核心数的 2 倍。</li>
<li><strong>连接池管理</strong>：跟后端服务的连接用连接池复用，避免频繁建连。借鉴 MyBatis 连接池的实现逻辑。</li>
<li><strong>泛化调用</strong>：网关不需要依赖后端的 API Jar，通过泛化调用直接发 Dubbo 请求。借鉴 MyBatis ORM 模型做协议解析和结果封装。</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>GC 停顿</strong>：Netty 的 ByteBuf 如果没及时释放会内存泄漏。严格遵守谁申请谁释放原则，加 LeakDetector 监控。</li>
<li><strong>慢服务拖垮网关</strong>：后端某个服务响应慢，Worker 线程被阻塞。解法：每个后端调用设超时（2 秒），超时直接返回错误。</li>
</ul>

<h2>加分项</h2>
<p>网关的本质是<strong>反向代理 + 协议转换 + 治理</strong>。Netty 管网络 I/O，泛化调用管协议转换，熔断降级管治理。日调用 7000W 的关键是异步非阻塞——一个线程可以同时处理多个请求，不像阻塞模型一个请求占一个线程。</p>`,
  followups: [
    {
      q: "为什么选择Netty而不是Zuul？",
      answer: `<p>Zuul 1.0 是阻塞 I/O，一个请求占一个线程，7K 并发需要 7K 线程，上下文切换开销大。Netty 是异步非阻塞，少量线程就能处理大量并发。我们横向对比后选 Netty。</p>`
    },
    {
      q: "如何处理连接池管理？",
      answer: `<p>借鉴 MyBatis 连接池：最大连接数、最小空闲数、连接超时、空闲回收。后端服务的 Dubbo 连接池化复用，避免频繁建连。</p>`
    },
    {
      q: "网关的熔断降级如何实现？",
      answer: `<p>Resilience4j 熔断器，按后端服务粒度配置。失败率 > 50% 熔断，HALF_OPEN 放行 10% 流量试探。降级返回缓存数据或默认值。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qjdi4rb"] = {
  question: "你提到借鉴MyBatis的ORM模型设计网关通信框架，这个设计思路是什么？",
  level: "Advanced",
  why: "CV: API网关架构设计",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>MyBatis 的 ORM 核心是：接口方法 → SQL 映射 → 数据库执行 → 结果封装。网关通信框架的思路类似：接口定义 → 协议映射 → 泛化调用 → 结果封装。</p>

<h3>类比</h3>
<pre>
MyBatis:  Mapper 接口 → XML SQL → JDBC → ResultMap
网关:     API 定义   → 协议映射 → Dubbo 泛化调用 → 响应封装
</pre>

<h3>实现</h3>
<ol>
<li><strong>接口定义</strong>：用注解定义 API 路径、请求参数、响应结构（类似 MyBatis 的 @Select 注解）</li>
<li><strong>协议映射</strong>：HTTP 请求 → Dubbo 泛化调用参数（类似 XML SQL 映射）</li>
<li><strong>泛化调用</strong>：不依赖后端 API Jar，直接通过接口名+方法名+参数调用（类似 JDBC 执行）</li>
<li><strong>结果封装</strong>：Dubbo 返回值 → HTTP 响应 JSON（类似 ResultMap）</li>
</ol>

<h2>踩过的坑</h2>
<ul>
<li><strong>泛化调用的类型丢失</strong>：泛化调用传参时，复杂对象的类型信息丢失。解法：自定义类型解析器，保留类型信息。</li>
<li><strong>协议解析性能</strong>：HTTP→Dubbo 的协议转换有开销。解法：缓存映射关系，避免重复解析。</li>
</ul>

<h2>加分项</h2>
<p>这个设计的好处是<strong>解耦</strong>。网关不需要依赖后端的 API Jar，只需要知道接口定义就能调用。新增后端服务不需要改网关代码，只需要注册接口定义。实际局限：泛化调用比直接调用慢，复杂对象类型信息可能丢失。</p>`,
  followups: [
    {
      q: "泛化调用的实现原理？",
      answer: `<p>Dubbo 泛化调用不需要依赖接口 Jar，通过 interfaceName + methodName + parameterTypes + arguments 四元组调用。底层是 Dubbo 的 GenericService，序列化/反序列化由框架处理。</p>`
    },
    {
      q: "如何处理协议解析？",
      answer: `<p>HTTP 请求 → JSON 解析 → Dubbo 泛化参数组装。映射关系缓存在内存里，避免重复解析。响应反向：Dubbo 返回值 → JSON → HTTP 响应。</p>`
    },
    {
      q: "这个设计有什么局限性？",
      answer: `<p>泛化调用的性能比直接调用差（多了序列化/反序列化开销）。复杂对象的类型信息可能丢失。另外网关需要维护接口定义的同步。</p>`
    }
  ]
};

window.PREPME_ANSWERS["qyucn2v"] = {
  question: "你设计的抽奖系统使用DDD分层结构，并自研规则引擎，能详细介绍一下吗？",
  level: "Core",
  why: "CV: 抽奖系统项目",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>NX-Lottery 是年尾/年中大促的抽奖系统，秒杀峰值 TPS 3000。DDD 分层 + 规则引擎的设计让它能快速适配不同活动规则。</p>

<h3>DDD 四层</h3>
<pre>
┌─────────────────────────────────┐
│ Interface 层（Controller）       │ ← 接收请求
├─────────────────────────────────┤
│ Application 层（Service）        │ ← 编排业务流程
├─────────────────────────────────┤
│ Domain 层（领域模型+规则引擎）    │ ← 核心业务逻辑
├─────────────────────────────────┤
│ Infrastructure 层（DB/Redis/MQ） │ ← 技术实现
└─────────────────────────────────┘
</pre>

<h3>规则引擎</h3>
<p>通过组合模式设计，规则分为两类：</p>
<ul>
<li><strong>逻辑过滤器</strong>：AND/OR/NOT 组合，如"新用户 AND 首次参与"</li>
<li><strong>引擎过滤器</strong>：具体规则实现，如"活动时间校验"、"参与次数校验"、"黑名单校验"</li>
</ul>
<p>业务方通过配置组合规则，不需要改代码。新增活动只需要配置新的规则组合。</p>

<h3>高并发设计</h3>
<ul>
<li>Redis 分布式锁代替 MySQL 行锁，扣减参与次数</li>
<li>Kafka 解耦发奖流程，XXL-JOB 补偿失败消息</li>
<li>秒杀 TPS 3000，支持横向扩容</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>Redis 锁超时</strong>：业务执行时间超过锁 TTL，导致并发冲突。解法：锁续期（看门狗机制）。</li>
<li><strong>发奖失败</strong>：Kafka 消费者处理失败，奖品没发出去。解法：XXL-JOB 定时补偿，查数据库中"待发奖"状态的记录重试。</li>
</ul>

<h2>加分项</h2>
<p>规则引擎的设计思路跟 CoreAgent 的 ToolRegistry 有相似之处——都是通过组合模式实现可扩展。规则引擎组合过滤器，ToolRegistry 组合工具。本质是<strong>策略模式 + 组合模式</strong>的工程化应用。</p>`,
  followups: [
    {
      q: "DDD的四层架构如何划分？",
      answer: `<p>Interface（接收请求、参数校验）→ Application（编排业务流程、事务管理）→ Domain（领域模型、业务规则、规则引擎）→ Infrastructure（数据库、Redis、MQ）。核心原则：Domain 层不依赖任何技术组件。</p>`
    },
    {
      q: "规则引擎的过滤器如何组合？",
      answer: `<p>组合模式：逻辑过滤器（AND/OR/NOT）是组合节点，引擎过滤器是叶子节点。配置时用 JSON 定义规则树，运行时递归执行。新增规则只需要实现叶子节点接口。</p>`
    },
    {
      q: "如何保证秒杀场景下的数据一致性？",
      answer: `<p>Redis 分布式锁 + 数据库乐观锁双保险。Redis 锁控制并发，数据库兜底。Kafka 解耦发奖流程，失败消息 XXL-JOB 补偿。最终一致性，允许短暂延迟。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q1a3g5zc"] = {
  question: "你在水利平台使用Flink同步数据到ES，这个数据管道是如何设计的？",
  level: "Core",
  why: "CV: 水利平台项目",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>数据管道：设备上报 → Netty 接收 → Kafka → Flink 清洗 → ES 索引。</p>

<h3>架构</h3>
<pre>
水位设备 → Netty Server → Kafka（设备数据 Topic）
                              ↓
                         Flink Job
                         ├── 数据清洗（去重、格式化）
                         ├── 窗口聚合（5秒滚动窗口）
                         └── 写入 ES
                              ↓
                         ES 索引（搜索、自动补全）
</pre>

<h3>Flink 设计要点</h3>
<ul>
<li><strong>窗口策略</strong>：5 秒滚动窗口。太短（1秒）写入频率高、ES 压力大；太长（30秒）数据延迟高。5 秒是平衡点。</li>
<li><strong>数据倾斜</strong>：某些设备上报频率远高于其他设备，导致某个并行度过载。解法：按 device_id 哈希重分区，均匀分散到各并行度。</li>
<li><strong>Exactly-Once</strong>：Flink Checkpoint + Kafka 偏移量提交 + ES 幂等写入（doc_id = device_id + timestamp）。</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>ES 写入瓶颈</strong>：Flink 并行度 4，每秒写入 ES 几千条，ES 节点扛不住。解法：批量写入（bulk API），每 500 条或 5 秒 flush 一次。</li>
<li><strong>数据延迟</strong>：窗口太长导致数据延迟。解法：5 秒窗口 + 允许 1 秒乱序（watermark）。</li>
</ul>

<h2>加分项</h2>
<p>这个数据管道每天处理 720 万条数据（约 20G），1000+ 设备。关键是<strong>流式处理</strong>——不攒批、不延迟，5 秒内数据从设备到可搜索。</p>`,
  followups: [
    {
      q: "Flink的窗口策略如何选择？",
      answer: `<p>看延迟要求和 ES 写入能力。延迟敏感 → 滚动窗口（1-5秒），允许延迟 → 滑动窗口（30秒-1分钟）。我们选 5 秒滚动窗口，平衡延迟和 ES 压力。</p>`
    },
    {
      q: "如何处理数据倾斜？",
      answer: `<p>按 device_id 哈希重分区，均匀分散到各并行度。某些高频设备单独处理（Side Output），不跟普通设备混用并行度。</p>`
    },
    {
      q: "ES的索引mapping如何设计？",
      answer: `<p>device_id 为 keyword（精确匹配），timestamp 为 date（范围查询），value 为 float（数值查询）。自动补全用 completion suggester。索引按天分割（index-{date}），方便数据生命周期管理。</p>`
    }
  ]
};

window.PREPME_ANSWERS["q16pm8ez"] = {
  question: "你提到自写DBRouter组件实现分表功能，这个组件的设计思路是什么？",
  level: "Core",
  why: "CV: 水利平台分表设计",
  date: "2026-06-18",
  answer: `<h2>实战回答</h2>
<p>720 万条/天的数据量，单表扛不住，需要分表。自写 DBRouter 而不是用 ShardingJDBC，原因是需求简单（只按 device_id 哈希分 64 引入 ShardingJDBC 太重。</p>

<h3>设计</h3>
<pre>
MyBatis Interceptor
  ├── 拦截 SQL 语句
  ├── 解析表名
  ├── 根据分表键（device_id）计算哈希
  ├── 替换表名（device_data → device_data_23）
  └── 放行执行
</pre>

<h3>核心逻辑</h3>
<ul>
<li><strong>分表键</strong>：device_id。90% 查询带 device_id，哈希后直接定位到具体表，不需要扫全表。</li>
<li><strong>分表数</strong>：64 张。2 的幂次方便哈希取模。</li>
<li><strong>MyBatis Interceptor</strong>：拦截 Executor 的 query/update 方法，SQL 改写表名。业务代码无感知。</li>
</ul>

<h2>踩过的坑</h2>
<ul>
<li><strong>跨表查询</strong>：不带 device_id 的查询需要扫 64 张表。解法：这类查询走 ES（Flink 同步到 ES 的数据），不走 MySQL。</li>
<li><strong>扩容问题</strong>：64 张表不够了要扩到 128 张，数据需要迁移。解法：一开始用 2 的幂次，扩容时翻倍迁移（只迁移一半数据）。</li>
<li><strong>分表键选择</strong>：选错分表键代价很大——改分表键等于重建整个数据库。必须花时间分析查询模式。</li>
</ul>

<h2>加分项</h2>
<p>自写 DBRouter 的价值是<strong>轻量</strong>。需求简单时不需要引入 ShardingJDBC 这种重量级方案。MyBatis Interceptor 几百行代码就解决了问题，维护成本低。</p>`,
  followups: [
    {
      q: "哈希分表的扩容问题如何解决？",
      answer: `<p>用 2 的幂次方（64、128、256）。扩容翻倍时，只需要迁移一半数据——原来 mod 64 = 0 的数据，mod 128 要么是 0 要么是 64，只需要把 64 的那部分迁到新表。</p>`
    },
    {
      q: "分表后的跨表查询如何处理？",
      answer: `<p>不走 MySQL，走 ES。Flink 实时同步数据到 ES，非 device_id 维度的查询全部走 ES。MySQL 只负责按 device_id 的精确查询。</p>`
    },
    {
      q: "分表键如何选择？",
      answer: `<p>分析查询模式：90% 查询带 device_id → 选 device_id 为分表键。原则：分表键必须出现在绝大多数查询的 WHERE 条件中，否则分表没意义。</p>`
    }
  ]
};
