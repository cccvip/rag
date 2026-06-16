# 多租户 Agent 平台隔离设计 — 面试深度 QA

> 面试题：在一个多租户 Agent 平台上，租户级的模型配置隔离、知识库隔离、调用配额、计费统计分别怎么做？
> 候选人背景：8年 Java 后端，120 租户（含 4 家世界 500 强），五层租户隔离体系，RAG 多租户架构
> **这是你的核心优势。流程引擎的五层隔离 + RAG 的共享 Collection 方案，都是真实生产经验。**

---

## 一、最佳答案

多租户 Agent 平台的隔离是**架构的第一性原则**——不是功能，是底线。四个维度各解决不同的问题：

### 1.1 模型配置隔离

**问题**：租户 A 用 GPT-4，租户 B 用 Qwen-14B，租户 C 要自定义 temperature，配置怎么管？

**方案：租户级模型配置表 + 运行时动态路由**

```sql
CREATE TABLE tenant_model_config (
  tenant_id     VARCHAR(32) PRIMARY KEY,
  model_id      VARCHAR(64),        -- gpt-4 / qwen-14b / glm-4
  temperature   DECIMAL(2,1),       -- 0.1 ~ 1.0
  max_tokens    INT,                -- 最大生成长度
  system_prompt TEXT,               -- 租户级系统 Prompt
  tool_set      JSON,               -- 可用工具白名单
  updated_at    TIMESTAMP
);
```

**运行时流程**：

```
请求进入 → 从 header 提取 tenant_id
  → 查询 tenant_model_config（带缓存，TTL 5min）
  → 路由到对应的模型服务
  → 用该租户的 temperature/max_tokens/system_prompt 发起推理
```

**关键设计点**：
- **配置缓存**：每次请求都查数据库太慢，用 Redis 缓存租户配置，TTL 5 分钟。配置变更时主动失效缓存。
- **模型路由**：不同模型走不同的推理服务端点（GPT-4 走 OpenAI API，Qwen 走本地 vLLM），路由层做抽象。
- **兜底配置**：新租户如果没有配置，使用平台级默认配置（default tenant config）。

### 1.2 知识库隔离

**问题**：租户 A 的安全手册不能出现在租户 B 的检索结果里。

**方案：共享 Collection + Payload 过滤 + 应用层二次校验**

```
方案评估:
  ❌ 独立 Collection：每个租户一个 Collection → 120 个 Collection，运维噩梦
  ❌ 独立实例：每个租户一套 Qdrant → 资源浪费，成本撑不住
  ✅ 共享 Collection + Payload 过滤：所有租户共用一个 Collection，tenant_id 作为 Payload 字段
```

**实现**：

```python
# 写入时
qdrant.upsert(
  collection="knowledge_base",
  points=[PointStruct(
    id=chunk_id,
    vector=embedding,
    payload={
      "tenant_id": "T001",          # 租户标识
      "doc_id": "doc_001",
      "chunk_index": 0,
      "source": "消防手册.pdf",
      "page": 15
    }
  )]
)

# 检索时
results = qdrant.search(
  collection="knowledge_base",
  query_vector=query_embedding,
  query_filter=Filter(
    must=[FieldCondition(key="tenant_id", match=MatchValue(value="T001"))]
  ),
  limit=20
)
```

**应用层二次校验**：检索结果返回后，代码层面再检查每条结果的 `tenant_id` 是否等于当前租户。防止 Qdrant 过滤 bug 导致数据泄露。

**新租户 onboarding**：自动分配 `tenant_id`，上传文档时自动注入 `tenant_id` 到 Payload。从 2 天压缩到 5 分钟。

### 1.3 调用配额

**问题**：租户 A 每月 10 万次调用，租户 B 每月 1 万次，超了怎么办？

**方案：Redis 滑动窗口限流 + Token 配额 + 熔断降级**

**三层防护**：

```
Layer 1: API 网关层 — QPS 限流
  Redis + Lua 令牌桶
  Key: rate_limit:{tenant_id}
  单租户上限: 20 QPS（可按套餐配置）
  超限 → 返回 429 Too Many Requests

Layer 2: 调用计数层 — 月度配额
  Redis INCR + 过期时间
  Key: quota:{tenant_id}:{YYYY-MM}
  配额: 按套餐（基础版 1 万次/月，专业版 10 万次/月）
  超限 → 降速或只走缓存

Layer 3: Token 配额层 — 成本控制
  Redis 累加 Token 消耗
  Key: token_usage:{tenant_id}:{YYYY-MM}
  配额: 按套餐（基础版 100 万 Token/月）
  超限 → 降级到轻量模型或拒绝服务
```

**配额配置表**：

```sql
CREATE TABLE tenant_quota (
  tenant_id       VARCHAR(32) PRIMARY KEY,
  package_type    VARCHAR(16),      -- basic / pro / enterprise
  qps_limit       INT,              -- 每秒请求数上限
  monthly_calls   INT,              -- 月度调用次数
  monthly_tokens  BIGINT,           -- 月度 Token 上限
  overflow_policy VARCHAR(16)       -- reject / throttle / degrade
);
```

### 1.4 计费统计

**问题**：每个租户用了多少 Token、调了多少次、费用怎么算？

**方案：异步计费流水 + 聚合统计**

```
每次请求完成后 → 异步写入计费流水表

CREATE TABLE billing_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(32),
  request_id    VARCHAR(64),
  model_id      VARCHAR(64),
  input_tokens  INT,
  output_tokens INT,
  total_tokens  INT,
  cost_cents    INT,               -- 本次费用（分）
  created_at    TIMESTAMP,
  INDEX idx_tenant_time (tenant_id, created_at)
);

-- 月度聚合（定时任务，每天凌晨跑）
CREATE TABLE billing_monthly (
  tenant_id      VARCHAR(32),
  month          VARCHAR(7),       -- 2026-06
  total_calls    INT,
  total_tokens   BIGINT,
  total_cost     DECIMAL(10,2),
  PRIMARY KEY (tenant_id, month)
);
```

**计费规则**：

| 模型 | 输入 Token 价格 | 输出 Token 价格 |
|------|----------------|----------------|
| GPT-4 | $0.03 / 1K | $0.06 / 1K |
| Qwen-14B（本地） | ¥0.01 / 1K | ¥0.02 / 1K |
| GLM-4 | ¥0.05 / 1K | ¥0.10 / 1K |

**关键设计点**：
- **异步写入**：计费流水不阻塞主请求——请求完成后发 MQ 消息，计费服务异步消费写入。
- **幂等**：同一个 request_id 只计费一次，防止重复计费。
- **对账**：每日对比流水表汇总和月度聚合表，发现差异告警。

---

## 二、完整架构图

### 多租户 Agent 平台隔离架构

```
  用户请求 (带 tenant_id)
       │
       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  API Gateway                                                │
  │  ├─ 认证: 验证 tenant_id + token                            │
  │  ├─ 限流: Redis 令牌桶 (20 QPS/tenant)                      │
  │  └─ 配额检查: Redis INCR (月度调用次数)                      │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  模型路由层                                                  │
  │  ├─ 读取 tenant_model_config (Redis 缓存, TTL 5min)         │
  │  ├─ 路由到对应模型服务                                       │
  │  │   ├─ GPT-4 → OpenAI API                                 │
  │  │   ├─ Qwen → 本地 vLLM                                   │
  │  │   └─ GLM-4 → 智谱 API                                   │
  │  └─ 注入该租户的 system_prompt / temperature / max_tokens    │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  Agent 执行层                                                │
  │  ├─ 意图识别 → 工具调用 → 结果回传 → LLM 生成               │
  │  ├─ 知识库检索: RAG (tenant_id 过滤)                        │
  │  └─ 工具白名单: 按租户配置过滤可用工具                       │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  后处理层                                                    │
  │  ├─ Token 统计: 累加到 Redis token_usage:{tenant_id}:{月}   │
  │  ├─ 计费流水: 异步写入 billing_log (MQ)                     │
  │  └─ 配额更新: Redis INCR monthly_calls                      │
  └──────────────────────────────────────────────────────────────┘
```

### 知识库隔离数据流

```
  ┌─────────────────────────────────────────────────────────────┐
  │                    Qdrant 共享 Collection                    │
  │                    "knowledge_base"                          │
  │                                                             │
  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐      │
  │  │ T001    │  │ T002    │  │ T003    │  │ T004    │      │
  │  │ chunks  │  │ chunks  │  │ chunks  │  │ chunks  │      │
  │  │ (500)   │  │ (1200)  │  │ (800)   │  │ (2000)  │      │
  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘      │
  │                                                             │
  │  检索时强制过滤: filter = { tenant_id == 当前租户 }         │
  │  应用层二次校验: 遍历结果确认 tenant_id 一致                │
  └─────────────────────────────────────────────────────────────┘

  新租户 onboarding:
    ① 分配 tenant_id (自动)
    ② 上传文档 → Embedding → 写入 Qdrant (带 tenant_id Payload)
    ③ 配置租户级同义词库 (Nacos)
    ④ 冷启动保护期 7 天 (BM25 权重提高)
    耗时: 5 分钟
```

### 配额与计费数据流

```
  请求完成
     │
     ├─ 同步: Redis INCR quota:{tenant_id}:{月} (调用计数)
     ├─ 同步: Redis INCR token_usage:{tenant_id}:{月} (Token 累加)
     │
     └─ 异步: MQ → 计费服务
                   │
                   ▼
              billing_log (每次请求一条记录)
                   │
                   ▼ (每日凌晨定时任务)
              billing_monthly (聚合统计)
                   │
                   ▼
              账单生成 + 配额预警 (接近上限时通知租户)
```

---

## 三、结合项目的实际回答

"这套设计在我的应急安全平台上有完整的生产实践。我讲一下四个维度分别怎么做的：

**模型配置隔离**：我们的运维 Agent 和 RAG 知识库用的是不同的模型——运维 Agent 用本地部署的 Qwen（数据不出域），RAG 用 GPT-4（生成质量更好）。租户级配置存在 MySQL 的 `tenant_model_config` 表里，Redis 缓存 5 分钟。不同租户可以自定义 Prompt 模板——化工厂和写字楼的安全管理场景完全不同，Prompt 需要定制。

**知识库隔离**：120 个租户共享一个 Qdrant Collection，tenant_id 作为 Payload 字段，查询时强制过滤。应用层再加一层二次校验。这个方案让新租户 onboarding 从 2 天压缩到 5 分钟。

**调用配额**：三层防护——API 网关层 Redis 令牌桶限流（单租户 20 QPS），月度调用次数配额（按套餐），Token 配额（防止 LLM 费用失控）。超限后的降级策略是只走缓存，不调 LLM。

**计费统计**：每次请求完成后异步写计费流水（MQ 解耦），每日定时任务聚合月度数据。计费粒度是**按 Token**，不是按请求——因为一个复杂请求可能消耗 10 倍 Token，按请求计费不公平。"

---

## 四、加分项

1. **讲清楚"为什么共享而不是独立"。** "120 个租户如果每个都独立 Collection，运维噩梦——升级、备份、监控都要乘以 120。共享方案让运维成本和租户数量无关。"——说明你做过方案对比。

2. **提到应用层二次校验。** "Payload 过滤是 Qdrant 做的，但我不能完全信任它——万一 Qdrant 有 bug，租户 A 的数据出现在租户 B 的结果里，就是数据泄露。所以应用层再校验一次，每条结果的 tenant_id 必须等于当前租户。"——说明你有安全纵深思维。

3. **讲到配额的降级策略，不只是拒绝。** "超限后不是直接拒绝服务——基础版超限后降速（5 QPS），专业版超限后降级到轻量模型。只有企业版超限才拒绝。这样客户体验不会断崖式下跌。"——说明你理解 TOB 产品的运营思维。

4. **提到计费的幂等和对账。** "计费流水用 request_id 做幂等——同一次请求不会重复计费。每日对账对比流水汇总和聚合表，发现差异自动告警。计费出错对 TOB 产品是信任危机。"——说明你理解计费系统的严肃性。

5. **提到工具白名单隔离。** "不同套餐的租户可用工具不同——基础版只能用查询类工具，专业版可以用执行类工具（比如服务重启）。工具白名单存在租户配置里，Agent 执行前先过滤。"——这是 Agent 平台特有的隔离维度。

6. **提到"冷租户"淘汰。** "我的流程引擎有五层隔离，其中一层是冷租户 LRU 淘汰——长时间没有请求的租户，其缓存和热数据被淘汰，释放资源给活跃租户。再次请求时从数据库重新加载，有几十毫秒的冷启动延迟，但不影响功能。"

---

## 五、追问应对

### Q1：租户 A 的 Prompt 模板和租户 B 的怎么隔离？

"两个层面：

**存储层**：Prompt 模板存在配置中心（Nacos），按租户 ID 分组：
```
/prompts/tenant/T001/agent_diagnosis.txt   → 化工厂专用
/prompts/tenant/T002/agent_diagnosis.txt   → 写字楼专用
/prompts/common/agent_diagnosis.txt         → 通用默认
```

**运行时**：请求进来后，先查该租户是否有专属模板，没有就用通用模板。模板内容注入到 system_prompt 里，和用户的 query 一起发给 LLM。

**模板管理**：
- 通用模板由平台维护，所有租户共享
- 租户专属模板由租户管理员在后台编辑，平台审核后生效
- 模板变更记录版本历史，可以一键回滚

**我的实践**：运维 Agent 的 Prompt 模板按告警类型分组（MQ 堆积用一套、DB 慢查询用另一套），同时支持租户级定制。化工厂客户的安全规程和写字楼完全不同，Prompt 需要定制化。"

### Q2：Token 配额超限后的降级策略是什么？

"不是一刀切拒绝，而是**分级降级**：

```
配额使用率      策略
──────────      ────
< 80%           正常服务
80% ~ 95%       预警通知（邮件/短信通知租户管理员）
95% ~ 100%      降速（QPS 从 20 降到 5）
> 100%          降级（只走缓存，不调 LLM；或降级到轻量模型）
```

**降级到缓存的逻辑**：
- 配额用完后，Query 答案缓存仍然可用（不消耗 Token）
- 缓存未命中时，返回'配额已用完，请联系管理员升级套餐'
- 不是完全断服务——缓存命中率 55%，意味着超过一半的请求还是能正常响应

**紧急通道**：企业版租户有紧急配额——配额用完后可以临时申请额外额度，24 小时内审批。这个设计是因为我们的场景是应急安全管理，不能让客户在关键时刻完全无法使用。

**我的实践**：有一次某租户遭到异常流量攻击，1 分钟内 2000 次请求，Token 配额瞬间耗尽。降级到缓存后，重复 query 直接命中缓存返回，既保护了 LLM 费用，又没有完全断服务。事后加了租户级 QPS 限流，从源头解决。"

### Q3：计费粒度是按 Token 还是按请求？怎么防止计费逃逸？

"**按 Token 计费**，不是按请求——因为一个复杂请求可能消耗 10 倍 Token，按请求计费不公平。

**计费公式**：
```
单次费用 = input_tokens × 输入单价 + output_tokens × 输出单价

示例 (GPT-4):
  输入 500 Token × $0.03/1K = $0.015
  输出 200 Token × $0.06/1K = $0.012
  合计: $0.027
```

**防止计费逃逸的四道防线**：

```
① Token 统计在网关层，不信任客户端
   → 客户端报的 Token 数不算，以服务端 tiktoken 计数为准

② 请求级幂等
   → 同一个 request_id 只计费一次，防止重试导致重复计费

③ 异步计费 + 对账
   → 计费流水异步写入（MQ），每日定时对账
   → 流水表汇总 vs 月度聚合表，差异 > 0.1% 自动告警

④ 防刷机制
   → 租户级 QPS 限流 + 相同 query 缓存拦截
   → 异常流量模式检测（布隆过滤器）
```

**我的实践**：我们出过一次计费事故——某租户被攻击，2000 次相同 query 每次都走 LLM，月费暴涨 300%。事后加了缓存拦截 + QPS 限流 + Token 配额，三层防御。现在缓存命中率 55%，重复 query 直接返回不计费。"

---

## 六、面试策略总结

| 场景 | 怎么说 |
|------|--------|
| 面试官问整体方案 | 讲四个维度各一段，每段带技术方案 + 你的实际做法 |
| 面试官问知识库隔离 | 讲共享 Collection + Payload 过滤 + 应用层二次校验 |
| 面试官问配额 | 讲三层防护（QPS/调用次数/Token）+ 分级降级策略 |
| 面试官问计费 | 讲按 Token 计费 + 四道防线防逃逸 |
| 面试官追问 Prompt 隔离 | 讲 Nacos 按租户分组 + 通用模板兜底 |
| 面试官追问降级策略 | 讲分级降级 + 紧急通道 + 缓存兜底 |
| 面试官追问计费安全 | 讲幂等 + 对账 + 防刷 |

**核心优势：你有 120 个租户的生产经验——模型配置隔离、知识库隔离、配额管控、计费统计都做过。不是设计出来的方案，是跑出来的方案。面试时带数据、带踩坑、带修复动作。**
