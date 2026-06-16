window.PREPME_QUESTIONS = {
  title: "Interview Prep — Agent系统架构师 — 智谱AI",
  meta: {
    role: "Agent系统架构师（算法工程一体化方向）",
    candidate: "8年Java后端 · TOB SaaS平台",
    language: "zh",
    generated: "2026-06-16",
    coverage: {
      jd: [
        "Agent系统架构设计（意图识别、任务拆解、记忆管理、推理策略、工具编排）",
        "大模型应用创新（对话取数、智能营销、自动化运营）",
        "模型微调基础（SFT/RLHF/DPO）",
        "推理优化（高并发低延迟、Continuous Batching、KV Cache、量化）",
        "稳定性与工程效能（可观测性、自动化评测、Bad Case归集）",
        "MCP协议与Multi-Agent协作",
        "Function Calling与工具编排",
        "Prompt Engineering与Context Engineering",
        "RAG系统全流程与检索优化",
        "NL2SQL与场景设计"
      ],
      cv: [
        "应急安全智能平台整体架构（流程引擎 + RAG知识库 + 智能运维Agent）",
        "智能运维Agent：LLM驱动的故障自动诊断与自愈",
        "RAG知识库：双轨检索、多租户隔离、三层缓存、Bad Case治理",
        "自研流程引擎：分片调度、五层租户隔离、状态持久化",
        "社区团购营销系统（拼团、优惠券、积分）",
        "容器化与CI/CD改造",
        "Prometheus + Grafana可观测体系",
        "MySQL索引优化与慢查询治理"
      ]
    }
  },
  ui: {
    progress: "{done} / {total} 已完成",
    of: "/",
    processed: "已掌握",
    copy: "复制 Prompt",
    copied: "已复制！",
    filter_category: "分类筛选",
    filter_level: "难度筛选",
    all: "全部",
    hide_processed: "隐藏已掌握",
    why: "考察原因",
    followups: "追问",
    foundational: "基础",
    core: "核心",
    advanced: "进阶",
    filter_jd: "JD 驱动",
    filter_cv: "CV 驱动",
    footer: "prepme — 面试准备刷题页",
    view_answer: "查看答案",
    answered: "已作答",
    back: "返回题目列表",
    fu_handle: "追问应对",
    answer_footer: "由 anslog 生成"
  },
  promptTemplate: "你是一位资深的技术面试官，正在面试一位 8 年经验的 Java 后端工程师（目标岗位：Agent系统架构师）。\n\n请对以下面试题进行深度分析：\n\n{{QUESTION}}\n\n请按以下结构输出：\n\n## 最佳答案\n给出一个结构清晰、层次分明的高质量回答，包含具体的技术细节和设计权衡。\n\n## 加分项\n列出哪些回答角度能体现高级工程师 / 架构师的思维深度，让面试官眼前一亮。\n\n## 追问应对\n面试官大概率会追问以下问题，请逐一给出应对策略：\n{{FOLLOWUPS}}\n\n## 架构图\n如果涉及系统架构、数据流、模块交互，请用 ASCII 图辅助说明。\n\n---\n请用中文回答，技术术语保留英文原文。",
  data: [
    {
      category: "Agent系统架构基础",
      questions: [
        {
          q: "意图识别有哪些主流技术方案？在不同业务场景下如何选型？",
          level: "Core",
          source: "JD: Agent系统架构设计 → 意图识别模块",
          followups: [
            "多意图消歧怎么处理？用户一句话里包含多个意图时怎么办？",
            "模糊意图的澄清策略是什么？如何设计多轮引导？",
            "意图识别的准确率怎么评估？你们的线上指标是多少？"
          ]
        },
        {
          q: "对比 ReAct、Plan-and-Execute、ToT（思维树）三种任务拆解范式，各自的适用场景、优势和局限是什么？",
          level: "Advanced",
          source: "JD: Agent系统架构设计 → 任务拆解",
          followups: [
            "实际项目中你用的是哪种范式？为什么选它？",
            "ReAct 的循环次数怎么控制？如何避免死循环？",
            "Plan-and-Execute 的规划步骤如果出错了，执行阶段怎么纠错？"
          ]
        },
        {
          q: "Agent 的记忆管理怎么设计？短期记忆、长期记忆、外部记忆各自的存储方案和检索策略是什么？",
          level: "Core",
          source: "JD: Agent系统架构设计 → 记忆管理",
          followups: [
            "对话窗口太长时，你会用滑动窗口还是摘要压缩？各自的 tradeoff 是什么？",
            "长期记忆用向量检索的话，怎么处理记忆的更新和淘汰？",
            "多轮对话的上下文截断策略有哪些？"
          ]
        },
        {
          q: "CoT（思维链）、Self-Consistency、Reflexion（自我反思）这几种推理策略，在精度、延迟、成本上分别有什么权衡？",
          level: "Advanced",
          source: "JD: Agent系统架构设计 → 推理策略",
          followups: [
            "你们实际项目用的哪种推理策略？为什么？",
            "Self-Consistency 需要多次采样，延迟和成本怎么控制？",
            "Reflexion 的自我反思会不会导致推理链越来越长？怎么限制？"
          ]
        },
        {
          q: "Function Calling 的完整链路是什么？从用户输入到最终回复，每一步发生了什么？工具 Schema 设计有哪些最佳实践？",
          level: "Core",
          source: "JD: Agent系统架构设计 → 工具编排",
          followups: [
            "并行工具调用怎么处理？结果聚合的策略是什么？",
            "工具调用超时或失败时，降级策略怎么设计？",
            "工具的版本管理和权限控制怎么做？"
          ]
        },
        {
          q: "MCP（Model Context Protocol）的 Server/Client 架构是什么？它和 Function Calling 有什么区别？生态价值在哪？",
          level: "Core",
          source: "JD: MCP协议",
          followups: [
            "MCP 的资源订阅机制是怎么工作的？",
            "为什么不直接用 Function Calling，而要引入一个独立协议？",
            "你在项目中用过 MCP 吗？遇到过什么坑？"
          ]
        },
        {
          q: "多 Agent 协作有哪些主流模式？Agent 之间的通信机制和冲突解决策略怎么设计？",
          level: "Advanced",
          source: "JD: Multi-Agent协作",
          followups: [
            "AutoGen 的对话模式和 CrewAI 的角色分工模式，分别适合什么场景？",
            "LangGraph 的状态机编排相比 DAG 编排，优势在哪？",
            "多 Agent 系统的调试和可观测性怎么做？"
          ]
        }
      ]
    },
    {
      category: "大模型应用与推理优化",
      questions: [
        {
          q: "Prompt Engineering 和 Context Engineering 的区别是什么？为什么说 Context Engineering 比 Prompt Engineering 更重要？",
          level: "Core",
          source: "JD: 大模型应用创新 → Prompt/Context Engineering",
          followups: [
            "Few-shot 和 CoT 提示分别在什么场景下使用？",
            "上下文窗口有限时，信息的优先级怎么排序？",
            "你实际项目中的 Prompt 模板是怎么管理的？有版本控制吗？"
          ]
        },
        {
          q: "SFT、RLHF、DPO 三种模型微调方式，各自的原理、适用场景和投入产出比是什么？",
          level: "Core",
          source: "JD: 模型微调基础",
          followups: [
            "什么场景用 Prompt Engineering 就够了，什么时候必须微调？",
            "SFT 的训练数据怎么准备？质量怎么保证？",
            "DPO 相比 RLHF 省掉了什么？效果差距大吗？"
          ]
        },
        {
          q: "推理优化中，Continuous Batching 和 KV Cache 分别解决什么问题？量化（INT8/INT4）的精度损失怎么评估？",
          level: "Advanced",
          source: "JD: 推理优化",
          followups: [
            "vLLM 的 PagedAttention 核心思想是什么？解决了什么问题？",
            "流式输出（SSE）的首字延迟（TTFT）怎么优化？",
            "多模型路由的策略是什么？简单任务和复杂任务怎么分流？"
          ]
        },
        {
          q: "设计一个 RAG 系统的完整数据流，从文档摄入到最终生成，每个环节的技术选型和优化点是什么？",
          level: "Core",
          source: "JD: RAG系统全流程",
          followups: [
            "Hybrid Search（BM25 + Dense + RRF）的设计原因是什么？比纯 Dense 好在哪？",
            "Cross-Encoder 精排和 ColBERT 的 tradeoff 是什么？",
            "RAG 的效果怎么评估？RAGAS 指标体系包含哪些维度？"
          ]
        }
      ]
    },
    {
      category: "工程化与稳定性",
      questions: [
        {
          q: "设计一个模型调用网关，需要支持流式 SSE、超时重试、熔断降级、多模型路由，整体架构怎么画？",
          level: "Advanced",
          source: "JD: 推理优化 + 稳定性保障",
          followups: [
            "指数退避重试的策略怎么设计？最大重试次数和间隔怎么定？",
            "熔断的阈值怎么设？半开状态的探测策略是什么？",
            "多模型路由的决策依据是什么？按任务复杂度还是按成本？"
          ]
        },
        {
          q: "如何设计一个 Agent 全链路可观测性系统？一次 Agent 请求涉及多次工具调用和模型推理，怎么追踪每个环节？",
          level: "Advanced",
          source: "JD: 稳定性与工程效能 → 全链路追踪",
          followups: [
            "TraceId 在异步工具调用中怎么传递？",
            "Token 消耗怎么按租户、按功能模块统计？",
            "Agent 执行链路的可视化怎么做？你用过什么工具？"
          ]
        },
        {
          q: "如何设计一个 Agent 自动化评测与 Bad Case 归集系统？从发现到修复的闭环怎么建？",
          level: "Core",
          source: "JD: 稳定性与工程效能 → 自动化评测",
          followups: [
            "任务成功率、用户满意度、Token 成本，这些指标怎么采集和聚合？",
            "Bad Case 的自动分类和归因怎么做？",
            "人工标注流水线怎么设计？标注效率怎么提升？"
          ]
        },
        {
          q: "在一个多租户 Agent 平台上，租户级的模型配置隔离、知识库隔离、调用配额、计费统计分别怎么做？",
          level: "Core",
          source: "JD: 稳定性与工程效能 → 多租户",
          followups: [
            "租户 A 的 Prompt 模板和租户 B 的怎么隔离？",
            "Token 配额超限后的降级策略是什么？",
            "计费粒度是按 Token 还是按请求？怎么防止计费逃逸？"
          ]
        }
      ]
    },
    {
      category: "系统设计题",
      questions: [
        {
          q: "设计一个智能自然语言对话取数系统（NL2SQL），从用户输入自然语言到返回查询结果，整体架构怎么设计？",
          level: "Advanced",
          source: "JD: 大模型应用创新 → 对话取数",
          followups: [
            "Schema 理解怎么做？表结构太复杂时模型看不懂怎么办？",
            "生成的 SQL 怎么校验准确性？怎么防止 SQL 注入？",
            "权限控制怎么做？不同用户只能查自己权限范围内的数据。"
          ]
        },
        {
          q: "设计一个支持长周期任务的 Agent 工作流引擎，需要支持 DAG 编排、状态持久化、断点续行、人工介入，怎么设计？",
          level: "Advanced",
          source: "JD: 大模型应用创新 → 自动化运营",
          followups: [
            "长时间运行的任务，中间状态怎么持久化？崩溃后怎么恢复？",
            "人工介入点（HITL）怎么设计？等待人工审批时任务状态怎么管理？",
            "异常回滚策略是什么？已完成的步骤怎么撤销？"
          ]
        }
      ]
    },
    {
      category: "应急安全智能平台 — 整体架构",
      questions: [
        {
          q: "你在应急安全智能平台中设计了三个核心模块（流程引擎、RAG知识库、智能运维Agent），它们之间是怎么协作的？共享的多租户隔离体系具体怎么实现？",
          level: "Core",
          source: "CV: 应急安全智能平台整体架构",
          followups: [
            "三个模块的数据存储是怎么隔离的？共享 Collection 和独立 Collection 的选型依据是什么？",
            "如果一个租户只买了 RAG 模块没买流程引擎，权限怎么控制？",
            "120 个活跃租户的 onboarding 流程是怎样的？新租户从注册到可用要多久？"
          ]
        },
        {
          q: "你提到平台支撑 120 个活跃租户（含 4 家世界 500 强）、7×24 小时服务，整体的高可用架构是怎么设计的？故障自愈机制是什么？",
          level: "Core",
          source: "CV: 应急安全智能平台 → 高可用",
          followups: [
            "限流、熔断、优雅关闭分别怎么实现？用的什么组件？",
            "故障自愈是自动触发还是需要人工确认？自愈动作有哪些？",
            "有没有做过故障演练？怎么模拟线上故障？"
          ]
        }
      ]
    },
    {
      category: "智能运维 Agent 与 RAG 知识库",
      questions: [
        {
          q: "你的智能运维 Agent 整合了 ELK、Prometheus、告警收敛为工具集，通过 Function Calling 实现动态调用。工具注册、Schema 设计、结果回传的完整链路是怎么实现的？",
          level: "Core",
          source: "CV: 智能运维Agent → 工具编排",
          followups: [
            "工具调用失败时怎么处理？有重试或降级吗？",
            "Function Calling 的参数是怎么从 LLM 输出映射到工具调用的？",
            "工具集的版本管理怎么做？新增一个工具需要改多少代码？"
          ]
        },
        {
          q: "你的运维 Agent 用本地部署的 Qwen 模型做故障根因分析，针对 MQ 堆积、DB 慢查询、Redis 热点 Key 等场景设计了结构化 Prompt 模板。能讲讲 Prompt 的设计思路和输出格式约束吗？",
          level: "Core",
          source: "CV: 智能运维Agent → 推理策略",
          followups: [
            "为什么选本地部署而不是用云端 API？数据不出域的成本是什么？",
            "Prompt 模板的版本管理和效果追踪怎么做？",
            "模型输出的诊断结论如果不靠谱，兜底机制是什么？"
          ]
        },
        {
          q: "夜间告警人工介入率从 100% 降至 15%，MTTR 从 45 分钟压缩至 8 分钟。这个效果是怎么达成的？剩下 15% 需要人工介入的场景是什么？",
          level: "Core",
          source: "CV: 智能运维Agent → 成果",
          followups: [
            "自愈动作（服务重启、流量切换、资源扩容）的触发条件和安全边界怎么定？",
            "有没有出现过 Agent 误判导致错误自愈的情况？怎么防范？",
            "Bad Case 归集机制具体怎么运作？修复周期是多久？"
          ]
        },
        {
          q: "你的 RAG 系统采用 Qdrant（Dense）+ Elasticsearch（BM25）+ 应用层 RRF 融合的双轨检索架构。为什么不选一个数据库同时做 Sparse + Dense？RRF 融合的权重怎么调？",
          level: "Advanced",
          source: "CV: RAG知识库 → 双轨检索架构",
          followups: [
            "Flat 索引和 HNSW 索引的切换阈值怎么定？你说 5000 条是经验还是压测得出的？",
            "RRF 融合的 k 值怎么选？不同 query 类型需要不同的权重吗？",
            "HyDE 查询重写具体怎么实现？对检索质量提升有多大？"
          ]
        },
        {
          q: "RAG 多租户隔离你用的是共享 Collection + Payload 过滤（tenant_id），应用层二次校验。这种方案相比独立 Collection 的优劣势是什么？有没有遇到过数据泄露的风险？",
          level: "Core",
          source: "CV: RAG知识库 → 多租户隔离",
          followups: [
            "Payload 过滤的性能开销大吗？120 个租户的过滤条件会不会拖慢查询？",
            "应用层二次校验具体校验什么？防止什么场景的泄露？",
            "新租户 onboarding 从 2 天压缩到 5 分钟，具体自动化了哪些步骤？"
          ]
        },
        {
          q: "三层缓存（Query 答案缓存 55% → HyDE 缓存 30% → LLM 响应缓存 40%）叠加后 LLM 调用成本降 82%。每层缓存的 key 设计、失效策略、命中率波动怎么处理？",
          level: "Advanced",
          source: "CV: RAG知识库 → 三层缓存",
          followups: [
            "Query 归一化怎么做？'灭火器怎么用'和'怎么使用灭火器'能命中同一份缓存吗？",
            "文档更新后缓存怎么失效？全量清空还是按 key 精确失效？",
            "为什么不在向量检索层加缓存？你说 ROI 太低，具体怎么算的？"
          ]
        },
        {
          q: "新租户冷启动检索质量差（Faithfulness 0.55→0.82），你用了租户级同义词库 + 冷启动保护期。同义词库怎么自动构建？保护期的检索策略切换逻辑是什么？",
          level: "Advanced",
          source: "CV: RAG知识库 → Bad Case治理",
          followups: [
            "同义词库的覆盖率怎么保证？会不会引入错误的同义词？",
            "冷启动保护期 7 天是经验值还是有数据支撑？",
            "异常流量攻击导致 LLM 费用暴涨 300% 的那次事故，布隆过滤器具体拦截了什么？"
          ]
        }
      ]
    },
    {
      category: "自研流程引擎与工程实践",
      questions: [
        {
          q: "你的流程引擎参考 Netty EventLoop 设计了分片调度器，按 instance_id 哈希分片。这个设计的核心思想是什么？相比分布式锁方案，优势在哪？",
          level: "Advanced",
          source: "CV: 自研流程引擎 → 分片调度",
          followups: [
            "分片数量怎么定？动态扩缩容时分片怎么重新分配？",
            "单分片内是单线程还是线程池？怎么保证实例级串行化？",
            "大客户独立 Lane 的设计思路是什么？和普通分片有什么区别？"
          ]
        },
        {
          q: "五层租户隔离（入口限流 → 分片调度 → 分层执行 → 实例配额 → 租户熔断），每层分别防什么？大客户流量洪峰 10 倍时，哪一层最先起作用？",
          level: "Core",
          source: "CV: 自研流程引擎 → 五层租户隔离",
          followups: [
            "入口限流用的什么算法？Redis Lua 令牌桶的具体实现逻辑是什么？",
            "冷租户 LRU 淘汰的触发条件是什么？淘汰后租户再次请求怎么恢复？",
            "租户熔断和应用级熔断的区别是什么？熔断粒度怎么选？"
          ]
        },
        {
          q: "状态持久化用主键更新同步落盘 + 乐观锁，超时率 < 0.1%。为什么选主键更新而不是追加写？乐观锁冲突时的重试策略是什么？",
          level: "Core",
          source: "CV: 自研流程引擎 → 状态持久化",
          followups: [
            "节点超时告警的检测机制是什么？轮询还是事件驱动？",
            "人工介入节点的等待超时怎么处理？",
            "如果数据库挂了，流程引擎的状态怎么恢复？"
          ]
        },
        {
          q: "你在社区团购负责拼团、优惠券、积分等营销模块。拼团的状态机设计是怎样的？开团→成团→履约/超时关闭的每个状态转换，异常怎么处理？",
          level: "Core",
          source: "CV: 社区团购营销系统",
          followups: [
            "拼团的并发问题怎么解决？多人同时参团时库存怎么扣减？",
            "优惠券的防刷策略是什么？",
            "营销活动配置化具体怎么实现？新活动上线从 3 天缩短到半天，自动化了什么？"
          ]
        },
        {
          q: "你推动团队从手工部署迁移至 Docker 容器化 + Jenkins CI/CD，发布耗时从 40 分钟降至 8 分钟。迁移过程中最大的技术挑战是什么？零停机滚动更新怎么实现？",
          level: "Core",
          source: "CV: 容器化与CI/CD改造",
          followups: [
            "数据库 schema 变更和应用代码变更的部署顺序怎么协调？",
            "回滚策略是什么？容器回滚和数据库回滚分别怎么做？",
            "Jenkins 流水线的具体 stage 有哪些？"
          ]
        },
        {
          q: "Prometheus + Grafana 监控体系的核心指标（成功率、RT、错误码）怎么定义？告警规则怎么设计才不会'狼来了'？",
          level: "Core",
          source: "CV: 可观测体系搭建",
          followups: [
            "指标的采集粒度是多少？15s 还是更细？存储成本怎么控制？",
            "告警收敛怎么做？同一个故障发 100 条告警怎么合并？",
            "Grafana 的核心 Dashboard 有哪些？你最关注哪几个面板？"
          ]
        },
        {
          q: "针对营销活动高峰期慢查询，核心查询 P99 从 400ms 降至 100ms。你的索引优化策略是什么？能讲一个具体的 SQL 优化案例吗？",
          level: "Core",
          source: "CV: 数据库性能优化",
          followups: [
            "EXPLAIN 的执行计划你看哪些字段？怎么判断是不是走了索引？",
            "联合索引的最左前缀原则，能举一个你实际优化的例子吗？",
            "营销活动的读写比例大概是多少？读写分离有没有必要？"
          ]
        }
      ]
    }
  ]
};
