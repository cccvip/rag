# ShardingSphere-JDBC 高频面试点

> **核心定位**：轻量级 Java 框架，在 JDBC 层拦截 SQL，自动完成分片路由、SQL 改写、分布式执行和结果归并。对业务零侵入（理论上）。

---

## 一、核心架构（面试必问）

```
业务代码
    │
    ▼
ShardingSphere-JDBC（jar包）
    │
    ├── SQL 解析（Parser）：ANTLR4 生成 AST
    │
    ├── SQL 路由（Router）：根据分片键计算目标库/表
    │
    ├── SQL 改写（Rewriter）：改写表名、补充分页逻辑
    │
    ├── SQL 执行（Executor）：多线程并发执行到真实数据源
    │
    └── 结果归并（Merger）：内存排序/分组/聚合
    │
    ▼
真实 JDBC（HikariCP / Druid）
    │
    ▼
MySQL / PostgreSQL ...
```

**五个核心模块**：

| 模块 | 作用 | 面试考点 |
|------|------|----------|
| **SQL 解析** | 将 SQL 解析为 AST | 解析引擎（ANTLR4）支持的 SQL 方言 |
| **SQL 路由** | 根据分片策略确定目标 | 标准路由、Hint 路由、广播路由 |
| **SQL 改写** | 改写表名、分页、聚合 | LIMIT 改写、ORDER BY 改写 |
| **SQL 执行** | 多线程执行到真实库 | 连接模式（内存限制/连接限制）|
| **结果归并** | 合并多库结果 | 流式归并 vs 内存归并 |

---

## 二、高频面试题

### 2.1 分片策略有哪些？你们怎么选的？

| 策略 | 配置 | 适用场景 | 缺点 |
|------|------|----------|------|
| **标准分片** | `StandardShardingStrategy` | 单一分片键，精确路由 | 范围查询需全库广播 |
| **复合分片** | `ComplexShardingStrategy` | 多字段联合分片 | 配置复杂 |
| **Hint 强制路由** | `HintShardingStrategy` | 分片键不在 SQL 中 | 侵入业务代码 |
| ** inline 表达式** | `INLINE` | 简单取模/范围 | 不支持复杂算法 |
| **自定义算法** | 实现 `ShardingAlgorithm` | 特殊业务逻辑 | 开发成本高 |

**标准分片算法**：

```java
// 精确分片算法（=、IN）
PreciseShardingAlgorithm

// 范围分片算法（BETWEEN、>、<）
RangeShardingAlgorithm

// 示例：user_id % 4
public class UserIdShardingAlgorithm implements PreciseShardingAlgorithm<Long> {
    @Override
    public String doSharding(Collection<String> availableTargetNames, 
                             PreciseShardingValue<Long> shardingValue) {
        Long userId = shardingValue.getValue();
        long suffix = userId % 4;
        return "t_order_" + suffix;
    }
}
```

---

### 2.2 分片键怎么选？

**选择原则**：

| 原则 | 说明 | 反例 |
|------|------|------|
| **高 Cardinality** | 字段值分散，避免热点 | 用性别（只有男女）做分片键 → 两个库压力不均 |
| **查询必带** | 80% 查询条件包含该字段 | 用 create_time 分片，但查询都用 order_id → 全库广播 |
| **数据分布均匀** | 避免长尾分布 | 用地区分片，但 90% 订单来自广东 → 广东库热点 |
| **避免频繁变更** | 分片键变更 = 数据迁移 | 用手机号分片，用户换手机号 → 需要数据搬迁 |

**常见选择**：
- 用户类：user_id
- 订单类：order_id（或 user_id + 时间组合）
- 日志类：时间戳

---

### 2.3 分库分表后，那些 SQL 会有问题？怎么解决？

| SQL 类型 | 问题 | 解决方案 |
|----------|------|----------|
| **分页查询** | `LIMIT 10, 10` 需要查询所有库的 LIMIT 0, 20，内存归并 | 改写为 `LIMIT 0, 20`，归并后取第 10-20 条；深分页性能极差 |
| **聚合查询** | `SUM/AVG/COUNT` 需要汇总多库结果 | ShardingSphere 自动改写 + 内存归并计算 |
| **排序查询** | `ORDER BY create_time DESC` 需要多路归并排序 | 流式归并（优先级队列），但如果数据量大仍可能 OOM |
| **跨库 JOIN** | 不支持（或性能极差） | 避免跨库 JOIN；小表广播（每个库一份）；应用层组装 |
| **全局 ID** | 自增主键在分片后冲突 | 雪花算法、UUID、Leaf（美团）|
| **事务** | 跨库事务 = 分布式事务 | Seata AT、XA、柔性事务（最终一致）|

**分页问题详解**（面试最爱追问）：

```sql
-- 原始 SQL
SELECT * FROM t_order ORDER BY create_time DESC LIMIT 1000000, 10;

-- 4 个分片库，ShardingSphere 改写成：
SELECT * FROM t_order_0 ORDER BY create_time DESC LIMIT 0, 1000010;
SELECT * FROM t_order_1 ORDER BY create_time DESC LIMIT 0, 1000010;
SELECT * FROM t_order_2 ORDER BY create_time DESC LIMIT 0, 1000010;
SELECT * FROM t_order_3 ORDER BY create_time DESC LIMIT 0, 1000010;

-- 4 个库各查 1000010 条，共 4000040 条数据回传到内存归并
-- 然后取第 1000000-1000010 条
-- 性能灾难！深分页在分库分表后是致命伤
```

**深分页解决方案**：

```sql
-- 方案 1：禁止跳页，只支持上一页/下一页（业务妥协）
-- 用上一页最后一条记录的 create_time 作为游标
SELECT * FROM t_order 
WHERE create_time < '2024-01-01 12:00:00' 
ORDER BY create_time DESC 
LIMIT 10;

-- 方案 2：ES / ClickHouse 异构查询（大数据量分页走搜索引擎）

-- 方案 3：生成全局有序索引表（order_id 包含时间戳，按 order_id 范围查）
```

---

### 2.4 分布式主键怎么生成？

ShardingSphere 内置三种策略：

| 策略 | 类型 | 优点 | 缺点 |
|------|------|------|------|
| **SNOWFLAKE** | 雪花算法 | 趋势递增，性能高 | 时钟回拨问题 |
| **UUID** | 随机字符串 | 全局唯一，无中心 | 无序，占用空间大，索引效率低 |
| **LEAF** | 美团开源 | 号段模式 + 雪花 | 需要额外部署 Leaf 服务 |

**雪花算法时钟回拨问题**：

```
雪花算法依赖系统时钟生成递增 ID

时间点 T1：生成 ID = ...1000
时间点 T2：NTP 同步导致时钟回退到 T1-1s
时间点 T3：生成 ID = ...0999  ← 比 T1 的 ID 小！

结果：ID 不递增，插入数据库时可能破坏索引顺序性
```

**解决方案**：
- 时钟回拨 < 5ms：等待时钟恢复
- 时钟回拨 > 5ms：抛出异常或借用未来时间（记录偏移量）
- 美团 Leaf：双 buffer + 号段模式，完全不依赖时钟

---

### 2.5 事务怎么处理？

ShardingSphere 支持三种事务模式：

| 模式 | 原理 | 一致性 | 性能 | 适用场景 |
|------|------|--------|------|----------|
| **本地事务** | 单库事务，多库时自动退化为逐个提交 | 最终一致 | 最高 | 单库操作、可接受不一致 |
| **XA 事务** | 2PC，强一致 | 强一致 | 最低（阻塞）| 金融转账、强一致场景 |
| **BASE 事务** | Seata AT 模式，undo_log 补偿 | 最终一致 | 中 | 大多数业务场景 |

**XA 事务的问题**：

```sql
-- XA 两阶段提交
XA START 'xid_1';
INSERT INTO db1.t_order ...;
INSERT INTO db2.t_order ...;
XA END 'xid_1';
XA PREPARE 'xid_1';     -- 第一阶段：所有库预提交，锁定资源
XA COMMIT 'xid_1';      -- 第二阶段：所有库真正提交

问题：
1. 第一阶段锁定资源时间长，并发度低
2. 协调者挂了，参与者一直阻塞（悬挂事务）
3. 性能极差，吞吐量下降 10 倍以上
```

**Seata AT 模式（推荐）**：

```sql
-- 业务 SQL
UPDATE t_order SET status = 'PAID' WHERE order_id = 100;

-- Seata 自动拦截，生成反向 SQL 存入 undo_log
INSERT INTO undo_log (branch_id, xid, rollback_info) 
VALUES (..., ..., '{"sql":"UPDATE t_order SET status = 'CREATED' WHERE order_id = 100"}');

-- 提交本地事务
COMMIT;

-- 如果全局事务回滚，Seata 用 undo_log 自动补偿
```

---

### 2.6 ShardingSphere-JDBC vs Proxy 怎么选？

| 维度 | ShardingSphere-JDBC | ShardingSphere-Proxy |
|------|---------------------|----------------------|
| **接入方式** | jar 包，嵌入应用 | 独立进程，类似 MySQL |
| **性能** | 高（无网络跳转） | 中（多一次网络代理）|
| **语言支持** | 仅 Java | 任意语言（MySQL 协议）|
| **运维复杂度** | 低（无额外组件） | 高（需部署、监控、高可用）|
| **升级成本** | 应用重新发版 | 代理独立升级 |
| **连接数** | 应用直接连 DB | 代理连接数可能打满 |
| **适用** | Java 单体 / 微服务 | 多语言、遗留系统、DBA 管控 |

**面试话术**：
> "我们选型时考虑了团队技术栈和运维能力。团队全是 Java，且追求极致性能，所以选了 JDBC 模式。但如果团队有多语言（PHP、Go），或者需要 DBA 统一管控 SQL 审计，Proxy 更合适。"

---

### 2.7 你们线上遇到过什么 ShardingSphere 的坑？

#### 坑 1：绑定表（Binding Table）配置遗漏

```sql
-- 订单表和订单详情表是绑定关系（同一分片键）
-- 如果忘记配置 bindingTables，这条 SQL 会全库广播：
SELECT o.*, d.item_name 
FROM t_order o JOIN t_order_detail d ON o.order_id = d.order_id
WHERE o.user_id = 100;

-- 正确配置：
spring.shardingsphere.sharding.binding-tables=t_order,t_order_detail
```

**后果**：4 个分片库 × 4 个分片表 = 16 次查询，性能暴跌。

---

#### 坑 2：默认数据源陷阱

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0, ds1
    sharding:
      default-data-source-name: ds0  # 未配置分片规则的表走默认库
```

**问题**：新加的表忘记配分片规则，全量数据打到 ds0，ds0 被打挂。

**解决**：关闭默认数据源，未配置的表直接报错。

---

#### 坑 3：Hint 强制路由滥用

```java
// 为了绕过 SQL 解析，强制指定分片
HintManager hintManager = HintManager.getInstance();
hintManager.addTableShardingValue("t_order", 0);

// 问题 1：代码侵入性强，到处散落 Hint
// 问题 2：新手误用导致数据路由错误，数据写到错误的库
// 问题 3：HintManager 是 ThreadLocal，忘记 clear() 导致下游线程污染
```

**解决**：能用标准路由就不用 Hint；必须用的话封装成 AOP 切面，自动 clear。

---

#### 坑 4：元数据加载慢

```
启动时 ShardingSphere 会加载所有表的元数据：
    - 100 个逻辑表
    - 4 个分片库 × 4 个分片表 = 1600 个真实表
    - 每个表查 INFORMATION_SCHEMA

启动时间从 30s 变成 5min
```

**解决**：
```yaml
# 关闭元数据检查（生产环境）
spring.shardingsphere.props.sql-show: false
spring.shardingsphere.schema.name: logic_db
```

或者升级 ShardingSphere 5.x，支持异步并行加载元数据。

---

## 三、面试 2 分钟口述版

> "我们用了 ShardingSphere-JDBC 做分库分表，它的核心是在 JDBC 层拦截 SQL，完成解析、路由、改写、执行、归并五个步骤。
>
> 分片键我们选的是 user_id，用取模算法分到 4 个库。选择原则是查询必带、高分散度、不频繁变更。
>
> 分库分表后最痛的是跨库 JOIN 和深分页。JOIN 我们通过小表广播和应用层组装解决；深分页禁止跳页，用游标方式下一页。聚合查询如 SUM、COUNT 由 ShardingSphere 自动改写到各分片执行，内存归并结果。
>
> 分布式主键用雪花算法，解决了时钟回拨问题（回拨 < 5ms 等待，> 5ms 抛异常）。事务用 Seata AT 模式，通过 undo_log 做最终一致，避免 XA 的 2PC 性能问题。
>
> 选型上我们用了 JDBC 模式而不是 Proxy，因为团队全 Java，追求性能，不想多维护一层代理。"

---

## 四、速查表

```
ShardingSphere-JDBC
    │
    ├── SQL 解析（ANTLR4）
    │
    ├── SQL 路由
    │       ├── 标准分片（单键）
    │       ├── 复合分片（多键）
    │       ├── Hint 强制路由
    │       └── 广播路由（无分片键）
    │
    ├── SQL 改写
    │       ├── 表名改写（t_order → t_order_0）
    │       ├── 分页改写（LIMIT 100,10 → LIMIT 0,110）
    │       └── 聚合改写（SUM → 各库 SUM → 内存汇总）
    │
    ├── SQL 执行
    │       ├── 内存限制模式（连接复用，结果集流式处理）
    │       └── 连接限制模式（每个分片一个连接，内存归并）
    │
    └── 结果归并
            ├── 流式归并（排序、分组，优先级队列）
            └── 内存归并（聚合、分页，全部加载到内存）

三大痛点：
    1. 跨库 JOIN → 小表广播 / 应用层组装 / 避免
    2. 深分页 → 游标分页 / ES 异构
    3. 分布式事务 → Seata AT / 避免跨库事务
```

---

*整理时间：2026-06-07*  
*适用：分库分表、ShardingSphere、数据库中间件面试*
