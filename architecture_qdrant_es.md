# SaaS 多租户应急安全 RAG 架构 — Qdrant + ES 双轨方案

## 完整流程图

```mermaid
flowchart TB
    subgraph Tenant["租户空间（租户 A / 租户 B / 租户 N...）"]
        direction TB
        
        subgraph Ingestion["数据摄入层"]
            DOC["安全手册 PDF<br/>逃生通道图<br/>应急预案"]
            IOT["IoT 传感器<br/>告警上报"]
            CHECKIN["人员签到记录"]
            ASSET["空间资产数据<br/>楼层/区域/坐标"]
        end
        
        subgraph Preprocess["预处理层"]
            DOC -->|OCR/布局识别| PARSE["文档解析<br/>Unstructured / Marker"]
            PARSE -->|提取标题/表格/段落| CHUNK["分块 Chunking<br/>chunk_size=256<br/>overlap=20%"]
            IOT -->|结构化| SQL[(MySQL<br/>tenant_id)]
            CHECKIN -->|结构化| SQL
            ASSET -->|结构化| SQL
        end
        
        subgraph Embedding["向量化层"]
            CHUNK -->|BGE-large<br/>768维| DENSE_VEC["稠密向量"]
            CHUNK -->|关键词提取<br/>TF-IDF / SPLADE| SPARSE_VEC["稀疏向量"]
        end
        
        subgraph Storage["双轨存储层"]
            DENSE_VEC -->|写入| QD[(Qdrant<br/>Collection: safety_knowledge<br/>索引: Flat<br/>Payload: tenant_id)]
            SPARSE_VEC -->|写入| ES[(Elasticsearch<br/>Index: safety_knowledge<br/>BM25 分词)]
        end
    end
    
    subgraph Query["查询层（单次请求）"]
        direction TB
        
        USER["用户 Query<br/>'3楼A区火灾逃生路线'"]
        
        USER --> ROUTER["Query Router<br/>意图识别 + 实体提取"]
        ROUTER -->|提取: floor=3, area=A区, event=火灾| SQL_FILTER["MySQL 标量过滤<br/>WHERE tenant_id='A'<br/>AND floor=3 AND area='A区'"]
        SQL_FILTER -->|返回: 逃生通道坐标<br/>消防设施位置| STRUCT_DATA["结构化数据"]
        
        ROUTER -->|改写后的 Query| REWRITE["查询重写<br/>规则层同义词扩展<br/>'火灾'→'失火/着火/起火'"]
        
        REWRITE -->|Dense 向量| QD_QUERY["Qdrant 检索<br/>filter: tenant_id='A'"]
        REWRITE -->|关键词| ES_QUERY["ES BM25 检索<br/>filter: tenant_id='A'"]
        
        QD_QUERY -->|召回 Top 50| FUSION["RRF 融合层<br/>score = Σ 1/(60+rank)"]
        ES_QUERY -->|召回 Top 50| FUSION
        
        FUSION -->|Top 10 chunks| RERANK["可选 Re-rank<br/>bge-reranker-base<br/>Top 10 → Top 5"]
        
        RERANK -->|文本 chunks| PROMPT["Prompt 组装<br/>结构化数据 + 文本 chunks<br/>+ 引用来源标注"]
        STRUCT_DATA --> PROMPT
    end
    
    subgraph Generation["生成与输出层"]
        PROMPT --> LLM["LLM 生成<br/>GPT-4 / Qwen-14B<br/>temperature=0"]
        LLM --> ANSWER["自然语言答案<br/>+ 引用标注 [^1^][^2^]<br/>+ 地图/坐标标注"]
        
        ANSWER --> VALIDATE["后处理校验层"]
        VALIDATE -->|引用真实性校验| CITATION_CHECK{"引用 chunk 的<br/>tenant_id 是否匹配?"}
        CITATION_CHECK -->|否| ALERT["触发告警<br/>丢弃异常引用"]
        CITATION_CHECK -->|是| OUTPUT["最终输出"]
        
        VALIDATE -->|楼层一致性校验| FLOOR_CHECK{"答案中的楼层<br/>与 query 楼层一致?"}
        FLOOR_CHECK -->|否| WARNING["返回: '请核实楼层信息'"]
        FLOOR_CHECK -->|是| OUTPUT
    end
    
    subgraph Fallback["降级与兜底"]
        direction LR
        CACHE["Redis 缓存<br/>高频 Query 命中 55%"]
        OFFLINE["离线静态卡片<br/>断网可用"]
        DISCLAIMER["免责声明<br/>'仅供参考，遵循现场指挥'"]
    end
    
    %% 缓存短路
    REWRITE -->|缓存命中| CACHE
    CACHE -->|直接返回| OUTPUT
    
    %% 降级路径
    RERANK -.->|应急模式跳过 CE<br/>Bi-Encoder Top 3 直接送 LLM| LLM
    
    %% 离线兜底
    OUTPUT --> OFFLINE
    OUTPUT --> DISCLAIMER
```

---

## 流程说明

### 1. 数据摄入层
- **静态文档**（PDF/扫描件）：安全手册、逃生通道图、应急预案
- **实时数据**：IoT 告警、签到记录、巡检隐患
- **空间资产**：楼层、区域、坐标、消防设施位置

### 2. 预处理层
- 文档解析：Unstructured / Marker 提取结构
- 分块：256 tokens，overlap 20%，按章节条款切分
- 结构化数据直接入库 MySQL（带 tenant_id）

### 3. 双轨存储层（核心）

| 存储 | 数据 | 作用 | 索引 |
|------|------|------|------|
| **Qdrant** | Dense 向量（BGE-large 768维） | 语义匹配、口语化查询 | Flat（单租户 <5000 条） |
| **Elasticsearch** | Sparse 向量（BM25 关键词） | 精确匹配楼层/区域/设备号 | 倒排索引 |
| **MySQL** | 空间资产、IoT 数据、签到记录 | 结构化查询、标量过滤 | B+Tree |

### 4. 查询层

```
用户 Query
    ↓
Query Router（提取实体：楼层/区域/事件）
    ↓
├─→ MySQL 标量过滤（精确约束）
│       └─→ 逃生通道坐标、消防设施位置
│
└─→ 查询重写（同义词扩展）
        ├─→ Qdrant（Dense）召回 Top 50
        ├─→ ES（BM25）召回 Top 50
        └─→ RRF 融合 → Top 10
                ↓
        可选 CE 精排 → Top 5
                ↓
        Prompt 组装（结构化数据 + 文本 chunks）
```

### 5. 生成与校验层

| 校验层 | 作用 | 失败处理 |
|--------|------|---------|
| **引用真实性校验** | 确认 chunk 的 tenant_id 与当前租户一致 | 丢弃异常引用，触发告警 |
| **楼层一致性校验** | 答案中的楼层与 query 楼层一致 | 返回"请核实楼层信息" |
| **安全关键词校验** | 应急 query 是否包含关键防护词 | 触发二次检索或降级 |

### 6. 降级策略

```
正常流程: Query → 重写 → Hybrid Search → CE 精排 → LLM → 输出
                    ↓
应急降级: Query → 重写 → Hybrid Search → 跳过 CE → LLM → 输出
                    （延迟 500ms → 150ms，精度掉 2%）
                    ↓
缓存短路: Query → Redis 命中 → 直接返回
                    （延迟 50ms）
                    ↓
离线兜底: 网络断开 → 本地静态卡片 → 直接返回
```

---

## 关键设计决策

```
┌─────────────────────────────────────────────────────────────┐
│  决策 1：为什么 Qdrant + ES 分开，而不是单一数据库？           │
│  ───────────────────────────────────────────────            │
│  • ES 的 BM25 经过十几年验证，精确匹配无可替代                 │
│  • Qdrant 的 Dense 检索轻量、快速、多租户友好                  │
│  • RRF 融合在应用层做，可控性强，不依赖单一数据库的成熟度       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  决策 2：为什么 Qdrant 用 Flat 不用 HNSW？                   │
│  ─────────────────────────────────────                      │
│  • 单租户 200~2000 条，Flat 检索 < 1ms，够快                 │
│  • Flat 召回率 100%，应急场景不丢关键信息                     │
│  • 零建索引时间，新租户 onboarding 秒级完成                   │
│  • 单租户 >5000 条时自动切 HNSW（目前仅 3 个大客户）          │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  决策 3：多租户隔离怎么做？                                   │
│  ─────────────────────                                      │
│  • Qdrant：共享 Collection + Payload tenant_id 过滤          │
│  • ES：共享 Index + tenant_id 字段过滤                       │
│  • MySQL：独立表 + tenant_id 字段                            │
│  • 应用层：二次校验，确保检索结果的 tenant_id 与 query 一致    │
│  • 缓存：Redis key 带 tenant_id 前缀，物理隔离                │
└─────────────────────────────────────────────────────────────┘
```

---

## 数据流时序图（一次完整请求）

```mermaid
sequenceDiagram
    actor User as 安全管理员
    participant API as API Gateway
    participant Router as Query Router
    participant Cache as Redis Cache
    participant ES as Elasticsearch
    participant QD as Qdrant
    participant SQL as MySQL
    participant RRF as RRF Fusion
    participant CE as Re-ranker
    participant LLM as GPT-4
    participant Validate as 校验层

    User->>API: "3楼A区火灾逃生路线"
    API->>Router: 转发 Query + tenant_id
    Router->>Router: 意图识别 + 实体提取
    
    par 结构化查询
        Router->>SQL: SELECT 逃生通道 WHERE floor=3 AND area='A区' AND tenant_id='A'
        SQL-->>Router: 逃生通道坐标、消防设施位置
    and 缓存检查
        Router->>Cache: GET cache_key(tenant_id, query_hash)
        Cache-->>Router: MISS
    and 文本检索
        Router->>ES: BM25 检索 + tenant_id 过滤，Top 50
        Router->>QD: Dense 检索 + tenant_id 过滤，Top 50
        ES-->>Router: BM25 结果列表
        QD-->>Router: Dense 结果列表
    end
    
    Router->>RRF: BM25 ranks + Dense ranks
    RRF-->>Router: Top 10 (RRF 融合后)
    
    Router->>CE: (Query, Top 10 chunks) 精排
    CE-->>Router: Top 5 chunks
    
    Router->>Router: Prompt 组装<br/>(结构化数据 + Top 5 chunks + 引用标注)
    Router->>LLM: 生成请求
    LLM-->>Router: 自然语言答案
    
    Router->>Validate: 引用真实性校验 + 楼层一致性校验
    Validate-->>Router: 校验通过
    
    Router->>Cache: SET cache_key (TTL 1小时)
    Router-->>API: 答案 + 引用 + 地图标注
    API-->>User: 响应
```

---

## 一句话总结

> **Qdrant 管语义（Dense），ES 管精确（BM25），MySQL 管结构化（坐标/楼层），应用层做融合（RRF）和校验（租户隔离）。Flat 索引支撑中小租户的低延迟，HNSW 作为大客户超规模的备选。三层校验（引用真实 + 楼层一致 + 安全关键词）确保应急场景下的安全底线。**
