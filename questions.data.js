window.PREPME_QUESTIONS = {
  "title": "Interview Prep — Java高级开发 / AI Agent工程师",
  "meta": {
    "role": "Java高级开发 / AI Agent工程师",
    "candidate": "XIAO",
    "language": "zh-CN",
    "generated": "2025-06-17",
    "coverage": {
      "jd": [
        "AI Agent 基本原理与工作机制",
        "RAG 检索增强生成",
        "提示工程 Prompt Engineering",
        "Function Calling 与 Tool Calling",
        "多Agent协同与MCP协议",
        "Agent工作流编排",
        "Java/微服务/SaaS架构",
        "性能调优与可观测性",
        "AI编程工具与研发效能"
      ],
      "cv": [
        "智能运维 Agent 项目",
        "RAG知识库项目",
        "MCP驱动的规范即代码",
        "自研流程引擎",
        "水利平台项目",
        "抽奖系统项目",
        "API网关项目"
      ]
    }
  },
  "ui": {
    "progress": "{done} / {total} 已完成",
    "of": "/",
    "processed": "已处理",
    "copy": "复制提示",
    "copied": "已复制",
    "filter_category": "分类筛选",
    "filter_level": "难度筛选",
    "all": "全部",
    "hide_processed": "隐藏已处理",
    "why": "为什么问这个",
    "followups": "追问",
    "foundational": "基础",
    "core": "核心",
    "advanced": "进阶",
    "filter_jd": "JD要求",
    "filter_cv": "简历经历",
    "footer": "基于简历和JD生成的面试准备题库",
    "view_answer": "查看答案",
    "answered": "已回答",
    "back": "返回题库",
    "fu_handle": "可能的追问",
    "answer_footer": "答案由AI生成，请结合自身经验调整"
  },
  "promptTemplate": "你是一位资深技术面试官，正在面试一位有9年Java后端经验、最近2年转型AI Agent开发的候选人。请针对以下面试题进行深度分析：\n\n【面试题】{{QUESTION}}\n\n【可能的追问】\n{{FOLLOWUPS}}\n\n请提供：\n1. **最佳答案**：一个结构清晰、有深度的标准答案\n2. **加分项**：能让面试官眼前一亮的回答要点\n3. **追问应对**：针对上述追问，给出回答思路\n4. **架构图**：如果涉及架构或流程，请用ASCII图辅助说明\n\n请用中文回答，技术术语保留英文原文。",
  "data": [
    {
      "category": "JD: AI Agent 基础原理",
      "questions": [
        {
          "q": "请解释 ReAct Pattern 的工作原理，以及它与传统 Chain-of-Thought 的区别是什么？",
          "level": "Core",
          "source": "JD: 要求理解Agent工作机制",
          "followups": [
            "ReAct在实际工程落地时有哪些挑战？",
            "如何处理Agent推理过程中的幻觉问题？",
            "ReAct和Function Calling可以结合使用吗？"
          ]
        },
        {
          "q": "Function Calling 的实现原理是什么？如何设计一个好的 Tool Schema？",
          "level": "Core",
          "source": "JD: 要求Function Calling能力",
          "followups": [
            "如何处理Function Calling的错误和重试？",
            "如何限制LLM的工具调用频率？",
            "多工具调用时如何保证原子性？"
          ]
        },
        {
          "q": "什么是 Agent 的任务分解（Task Decomposition）？常见的分解策略有哪些？",
          "level": "Core",
          "source": "JD: 要求任务分解能力",
          "followups": [
            "如何处理子任务之间的依赖关系？",
            "任务分解失败时如何回滚？",
            "如何评估分解粒度是否合适？"
          ]
        },
        {
          "q": "请解释 MCP（Model Context Protocol）协议的设计目标和核心概念。",
          "level": "Advanced",
          "source": "JD: 要求MCP协议理解",
          "followups": [
            "MCP与传统的API Gateway有什么区别？",
            "MCP如何处理资源的权限控制？",
            "在你的项目中是如何应用MCP的？"
          ]
        }
      ]
    },
    {
      "category": "JD: RAG与检索增强",
      "questions": [
        {
          "q": "请解释 RAG（Retrieval-Augmented Generation）的基本架构，以及它解决了LLM的什么问题？",
          "level": "Foundational",
          "source": "JD: 要求RAG检索增强能力",
          "followups": [
            "RAG有哪些常见的失败模式？",
            "如何评估RAG系统的检索质量？",
            "RAG和微调应该如何选择？"
          ]
        },
        {
          "q": "向量检索中 Flat 索引和 HNSW 索引各有什么优缺点？如何选择？",
          "level": "Core",
          "source": "JD: 要求RAG技术理解",
          "followups": [
            "除了这两种，还有哪些向量索引方式？",
            "如何处理向量检索中的维度灾难？",
            "混合检索（Hybrid Search）的融合策略有哪些？"
          ]
        },
        {
          "q": "什么是 HyDE（Hypothetical Document Embeddings）？它在RAG中的作用是什么？",
          "level": "Advanced",
          "source": "JD: 要求RAG技术深度",
          "followups": [
            "HyDE会带来哪些额外的延迟？",
            "HyDE在哪些场景下效果不好？",
            "还有哪些查询改写技术？"
          ]
        },
        {
          "q": "如何设计一个生产级的RAG系统的缓存策略？",
          "level": "Advanced",
          "source": "JD: 要求性能优化能力",
          "followups": [
            "缓存命中率如何监控和优化？",
            "缓存失效策略如何设计？",
            "如何处理缓存一致性问题？"
          ]
        }
      ]
    },
    {
      "category": "JD: 提示工程与对话编排",
      "questions": [
        {
          "q": "什么是 Prompt Engineering？设计一个好的Prompt有哪些关键原则？",
          "level": "Foundational",
          "source": "JD: 要求提示工程能力",
          "followups": [
            "Few-Shot和Zero-Shot各适用什么场景？",
            "如何处理Prompt注入攻击？",
            "Prompt的版本管理如何做？"
          ]
        },
        {
          "q": "如何设计多轮对话的上下文管理策略？",
          "level": "Core",
          "source": "JD: 要求多轮对话编排",
          "followups": [
            "上下文窗口超限时如何处理？",
            "如何实现对话的断点续传？",
            "多轮对话中的状态管理方案？"
          ]
        },
        {
          "q": "如何构建一个可复用的Prompt模板体系？",
          "level": "Core",
          "source": "JD: 要求可复用智能体应用",
          "followups": [
            "Prompt模板如何做版本管理？",
            "如何评估Prompt的效果？",
            "Prompt模板的继承和组合如何设计？"
          ]
        }
      ]
    },
    {
      "category": "JD: 工程化与架构",
      "questions": [
        {
          "q": "SaaS多租户系统中，数据隔离有哪些常见方案？各有什么优缺点？",
          "level": "Core",
          "source": "JD: 要求SaaS系统开发能力",
          "followups": [
            "如何处理租户级别的资源限制？",
            "新租户上线（onboarding）流程如何设计？",
            "如何实现租户级别的配置隔离？"
          ]
        },
        {
          "q": "微服务架构下，如何设计服务间的熔断和降级策略？",
          "level": "Core",
          "source": "JD: 要求微服务架构能力",
          "followups": [
            "Resilience4j的核心组件有哪些？",
            "熔断器的状态机是怎样的？",
            "如何设计优雅关闭（Graceful Shutdown）？"
          ]
        },
        {
          "q": "如何构建Agent系统的可观测性体系？需要监控哪些关键指标？",
          "level": "Advanced",
          "source": "JD: 要求可观测性建设",
          "followups": [
            "Agent的调用链如何追踪？",
            "如何定位Agent的性能瓶颈？",
            "LLM调用的成本如何监控和优化？"
          ]
        },
        {
          "q": "AI Coding工具（如Cursor、Claude Code）如何提升研发效能？有哪些最佳实践？",
          "level": "Core",
          "source": "JD: 要求AI编程工具经验",
          "followups": [
            "AI生成的代码如何做质量保障？",
            "如何避免AI Coding的常见陷阱？",
            "AI辅助开发的工作流如何设计？"
          ]
        }
      ]
    },
    {
      "category": "CV: 智能运维Agent项目",
      "questions": [
        {
          "q": "请详细介绍你设计的智能运维Agent的架构，以及‘感知→推理→行动→闭环’的具体实现。",
          "level": "Core",
          "source": "CV: 智能运维Agent项目",
          "followups": [
            "Agent如何处理误报？",
            "自愈动作失败时如何处理？",
            "如何评估Agent的诊断准确率？"
          ]
        },
        {
          "q": "你提到使用了多Agent协同架构（Retriever/Reranker/Writer），这种设计解决了什么问题？",
          "level": "Advanced",
          "source": "CV: 智能运维Agent多Agent协同",
          "followups": [
            "多个Agent之间如何通信？",
            "如何处理Agent之间的状态同步？",
            "单Agent和多Agent架构如何选择？"
          ]
        },
        {
          "q": "你提到夜间告警人工介入率从100%降至15%，这个数据是如何统计的？有哪些优化手段？",
          "level": "Core",
          "source": "CV: 智能运维Agent成果",
          "followups": [
            "剩15%的告警是什么类型？",
            "如何处理Agent无法自愈的情况？",
            "如何持续优化Agent的自愈能力？"
          ]
        }
      ]
    },
    {
      "category": "CV: RAG知识库项目",
      "questions": [
        {
          "q": "你设计的双轨检索架构（Qdrant + ES + RRF融合）是如何工作的？为什么选择Flat索引而不是HNSW？",
          "level": "Advanced",
          "source": "CV: RAG知识库双轨检索",
          "followups": [
            "RRF融合的参数如何调优？",
            "Flat索引在数据量增大后如何处理？",
            "有没有考虑过其他向量数据库？"
          ]
        },
        {
          "q": "你提到三层缓存策略使LLM调用成本降82%，请详细解释这三层缓存的设计。",
          "level": "Advanced",
          "source": "CV: RAG知识库三层缓存",
          "followups": [
            "缓存命中率分别是如何统计的？",
            "缓存失效策略如何设计？",
            "如何处理缓存一致性问题？"
          ]
        },
        {
          "q": "你提到新租户冷启动检索质量差（Faithfulness 0.55→0.82），这个问题的根因是什么？如何解决的？",
          "level": "Advanced",
          "source": "CV: RAG知识库Bad Case治理",
          "followups": [
            "Faithfulness指标是如何计算的？",
            "同义词库如何构建和维护？",
            "冷启动保护期的具体机制是什么？"
          ]
        },
        {
          "q": "你提到异常流量攻击导致LLM费用暴涨300%，是如何发现和解决的？",
          "level": "Core",
          "source": "CV: RAG知识库安全防护",
          "followups": [
            "如何区分正常流量和攻击流量？",
            "Token配额如何设计？",
            "如何防止租户之间的资源抢占？"
          ]
        }
      ]
    },
    {
      "category": "CV: 流程引擎与架构设计",
      "questions": [
        {
          "q": "你设计的流程引擎采用DAG编排+状态机驱动，这种架构有什么优势？",
          "level": "Core",
          "source": "CV: 自研流程引擎",
          "followups": [
            "DAG的拓扑排序如何实现？",
            "如何处理循环依赖？",
            "状态机的状态转换如何持久化？"
          ]
        },
        {
          "q": "你提到参考Netty EventLoop设计分片调度器，这种设计解决了什么问题？",
          "level": "Advanced",
          "source": "CV: 流程引擎分片调度",
          "followups": [
            "分片数量如何确定？",
            "分片之间如何保证负载均衡？",
            "如何处理分片热点问题？"
          ]
        },
        {
          "q": "你提到五层租户隔离机制，请详细解释每一层的作用。",
          "level": "Advanced",
          "source": "CV: 流程引擎五层租户隔离",
          "followups": [
            "大客户独立Lane如何实现？",
            "冷租户LRU淘汰的具体策略？",
            "租户熔断的触发条件是什么？"
          ]
        }
      ]
    },
    {
      "category": "CV: 早期项目经历",
      "questions": [
        {
          "q": "你设计的API网关基于Netty实现，日调用量7000W，单机7K并发，是如何实现的？",
          "level": "Core",
          "source": "CV: API网关项目",
          "followups": [
            "为什么选择Netty而不是Zuul？",
            "如何处理连接池管理？",
            "网关的熔断降级如何实现？"
          ]
        },
        {
          "q": "你提到借鉴MyBatis的ORM模型设计网关通信框架，这个设计思路是什么？",
          "level": "Advanced",
          "source": "CV: API网关架构设计",
          "followups": [
            "泛化调用的实现原理？",
            "如何处理协议解析？",
            "这个设计有什么局限性？"
          ]
        },
        {
          "q": "你设计的抽奖系统使用DDD分层结构，并自研规则引擎，能详细介绍一下吗？",
          "level": "Core",
          "source": "CV: 抽奖系统项目",
          "followups": [
            "DDD的四层架构如何划分？",
            "规则引擎的过滤器如何组合？",
            "如何保证秒杀场景下的数据一致性？"
          ]
        },
        {
          "q": "你在水利平台使用Flink同步数据到ES，这个数据管道是如何设计的？",
          "level": "Core",
          "source": "CV: 水利平台项目",
          "followups": [
            "Flink的窗口策略如何选择？",
            "如何处理数据倾斜？",
            "ES的索引mapping如何设计？"
          ]
        },
        {
          "q": "你提到自写DBRouter组件实现分表功能，这个组件的设计思路是什么？",
          "level": "Core",
          "source": "CV: 水利平台分表设计",
          "followups": [
            "哈希分表的扩容问题如何解决？",
            "分表后的跨表查询如何处理？",
            "分表键如何选择？"
          ]
        }
      ]
    }
  ]
};