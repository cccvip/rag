1 RAG 的全流程细节：从文档摄入 → 解析 → 分块(Chunking) → Embedding → 索引构建 → 查询重写(Query Rewriting) → 向量检索 → Re-rank → Prompt 组装 → LLM 生成 → 引用溯源(Citation)，每一步的选型与权衡。
2 检索优化：BM25 vs Dense Retrieval vs Hybrid Search 的原理与适用场景；Re-ranker（Cross-Encoder）为什么比 Bi-Encoder 更准但更慢；ColBERT 的晚期交互机制。
3 长上下文与多跳推理：如何处理超出模型上下文窗口的文档（Map-Reduce、Refine、RAPTOR）；多跳问题的迭代检索（IRCoT、Self-Ask、ReAct）。
4 评估与迭代：RAGAS 的各个指标含义；如何建立 Bad Case 分析流水线（区分是 Retrieval 问题还是 Generation 问题）。
5 实际项目深挖：你之前的 RAG 项目 QPS 多少？延迟多少？检索召回率多少？最头疼的 Bad Case 是什么？如何解决？