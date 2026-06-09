# MQ — 消息消费失败后的重试机制设计

> **核心原则**：先分类、再分级、有上限、有兜底。业务异常不重试，系统异常指数退避，最终进死信队列人工介入。

---

## 一、异常分类：一切重试的前提

消费失败不能一概而论，必须先分两类，否则会导致死循环、资源浪费、甚至脏数据。

| 类型 | 特征 | 示例 | 处理策略 |
|------|------|------|----------|
| **业务异常** | 参数/状态/逻辑错误，重试无法自愈 | 库存不足、余额不够、状态机不合法、参数校验失败 | **不重试或有限重试**，直接进死信/补偿 |
| **系统异常** | 外部依赖故障，可能随时间恢复 | DB连接超时、网络抖动、下游服务熔断、Redis宕机 | **可以重试**，指数退避，最终仍失败则进死信 |

**错误示范**：库存不足还重试 16 次，白白占用 MQ 和消费端资源，且 16 次后仍失败。

---

## 二、三级重试架构

```
消费消息
    │
    ▼
┌──────────────────┐
│  1. 幂等校验      │  ──► 已处理过？直接返回成功
│  (biz_id 去重)    │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  2. 业务执行      │
└────────┬─────────┘
         │
    ┌────┴────┐
    ▼         ▼
 成功      失败
    │         │
    │    ┌────┴──────────┐
    │    ▼               ▼
    │ 业务异常          系统异常
    │    │               │
    │    ▼               ▼
    │  进DLQ         本地即时重试
    │ (直接补偿)          │
    │                 ┌──┴──┐
    │              成功    失败
    │                 │      │
    │                 ▼      ▼
    │              提交    延迟重试（指数退避）
    │              offset      │
    │                       ┌──┴──┐
    │                    成功    失败
    │                       │      │
    │                       ▼      ▼
    │                    提交    达到最大重试次数？
    │                    offset      │
    │                             是/否
    │                           ┌──┴──┐
    │                          否     是
    │                           │      │
    │                           ▼      ▼
    │                       继续    转入死信队列（DLQ）
    │                       退避      │
    │                                  ▼
    │                            告警 + 人工介入
    │                            自动补偿 / 可视化重投
    ▼
 正常提交 offset
```

---

## 三、重试策略设计

### 3.1 指数退避（Exponential Backoff）

系统异常时立即重试，可能正好打在下游故障点上，越重试越雪崩。

```java
public class ExponentialBackoffPolicy {
    
    private final long baseDelayMs;   // 基础延迟，如 1000ms
    private final long maxDelayMs;    // 最大延迟封顶，如 30000ms
    private final double jitterRate;  // 抖动比例，如 0.2
    
    /**
     * 计算第 n 次重试的延迟时间
     * 公式：min(base * 2^n, max) + random(0, min * jitter)
     */
    public long getDelayMs(int retryCount) {
        // 指数计算：1s, 2s, 4s, 8s, 16s, 30s(封顶)
        long exponential = baseDelayMs * (1L << retryCount);
        long delay = Math.min(exponential, maxDelayMs);
        
        // 随机抖动（0% ~ 20%），防止多个失败消息同时重试形成脉冲
        long jitter = (long)(delay * jitterRate * Math.random());
        return delay + jitter;
    }
}
```

**重试时间线示例**（base=1s, max=30s, jitter=20%）：

| 重试次数 | 基础延迟 | 抖动范围 | 实际延迟 | 累计等待 |
|----------|----------|----------|----------|----------|
| 第1次 | 1s | 0~200ms | ~1.1s | ~1.1s |
| 第2次 | 2s | 0~400ms | ~2.2s | ~3.3s |
| 第3次 | 4s | 0~800ms | ~4.4s | ~7.7s |
| 第4次 | 8s | 0~1.6s | ~8.8s | ~16.5s |
| 第5次 | 16s | 0~3.2s | ~17.6s | ~34.1s |
| 第6次 | 30s（封顶） | 0~6s | ~33s | ~67s |
| ... | 30s | 0~6s | ~33s | ... |

**最大重试次数**：通常 **5 ~ 16 次**，根据业务容忍度调整。金融类设低（3~5次），日志类可设高。

---

### 3.2 本地重试 vs MQ 延迟重试

| 方式 | 实现 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|----------|
| **本地重试** | try-catch + sleep，消费线程自己重试 | 简单、不依赖MQ、可精细控制重试逻辑 | 阻塞消费线程，影响吞吐；重试时无法拉取新消息 | 顺序消息（不能跳过） |
| **MQ 延迟重试** | 失败消息发回延迟队列/重试Topic | 不阻塞当前消费、可跨消费者重试、支持分布式 | 依赖MQ支持延迟消息；实现稍复杂 | 非顺序消息 |

**推荐组合**：
- **顺序消息**：本地即时重试 1~2 次，仍失败则暂停分区消费，告警人工介入
- **非顺序消息**：本地不重试，立即发送到延迟重试队列

---

## 四、死信队列（DLQ）设计

当消息达到最大重试次数仍未成功，必须**强制剥离主线**，避免无限循环消耗资源。

### 4.1 DLQ 消息结构

```java
public class DeadLetterMessage {
    private String originalTopic;       // 原 Topic
    private String originalKey;         // 原 Key
    private String payload;             // 原消息体（JSON）
    private int retryCount;             // 已重试次数
    private String lastErrorClass;      // 最后一次异常类型
    private String lastErrorMsg;        // 最后一次异常信息
    private long firstFailTime;         // 首次失败时间戳
    private long lastFailTime;          // 最后失败时间戳
    private String consumerGroup;       // 消费组名
    private String traceId;             // 链路追踪ID
    private RetryType failType;         // 失败类型：BUSINESS / SYSTEM / EXHAUSTED
}
```

### 4.2 DLQ 处理流程

```
DLQ 接收消息
    │
    ├──► 实时告警通知（钉钉/飞书/企业微信）
    │        "消息消费重试耗尽，bizId=xxx，请尽快处理"
    │
    ├──► 可视化后台展示
    │        - 可查看消息内容、异常堆栈、重试历史
    │        - 支持手动重投原队列
    │        - 支持标记已处理/直接丢弃
    │
    ├──► 自动补偿（配置化策略）
    │        - 订单类：自动关闭订单 + 退款
    │        - 库存类：自动释放预占库存
    │        - 通知类：自动切换通知渠道（短信转APP Push）
    │
    └──► 人工介入（兜底）
             - 修复数据后手动重投
             - 确认无需处理后直接归档
```

**关键原则**：DLQ 不是垃圾桶，必须有**告警 + 人工兜底 + 自动补偿**三板斧。

---

## 五、主流 MQ 重试机制对比

### 5.1 RocketMQ

```java
@RocketMQMessageListener(
    topic = "order_topic",
    consumerGroup = "order_consumer",
    maxReconsumeTimes = 5,           // 最大重试次数（默认16次）
    consumeTimeout = 15              // 单条消息消费超时时间（分钟）
)
public class OrderConsumer implements RocketMQListener<OrderMessage> {
    @Override
    public void onMessage(OrderMessage msg) {
        try {
            process(msg);
        } catch (BusinessException e) {
            // 业务异常：不抛异常，直接返回，避免进入重试
            log.error("业务异常，不再重试, orderId={}", msg.getOrderId(), e);
        } catch (SystemException e) {
            // 系统异常：抛异常，RocketMQ 自动进入重试流程
            throw e;
        }
    }
}
```

**RocketMQ 重试机制内部实现**：

```
消费失败
    │
    ▼
发送到 %RETRY%order_consumer  Topic（RocketMQ 内置重试 Topic）
    │
    ▼
内置 18 级延迟队列：
    1s, 5s, 10s, 30s, 1m, 2m, 3m, 4m, 5m, 6m, 7m, 8m, 9m, 10m, 20m, 30m, 1h, 2h
    │
    ▼
达到 maxReconsumeTimes 后
    │
    ▼
发送到 %DLQ%order_consumer  死信队列
```

**特点**：
- 重试 Topic 是**集群级别**，同一消费组共享重试进度
- 延迟级别固定 18 档，不支持自定义连续退避
- 消费超时（默认 15min）也会触发重试

---

### 5.2 Kafka

Kafka **没有原生消费重试机制**，消费失败只有两种选择：

```java
public class KafkaOrderConsumer {
    
    public void pollAndProcess() {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        
        for (ConsumerRecord<String, String> record : records) {
            try {
                process(record);
                commitSync(record);  // 成功才提交 offset
                
            } catch (BusinessException e) {
                // 业务异常：记录日志，发送到自建 DLQ，提交 offset 跳过
                sendToDLQ(record, e);
                commitSync(record);
                
            } catch (SystemException e) {
                // 系统异常：不提交 offset，下次 poll 会重新消费
                // 但会阻塞该分区后续消息（顺序消费场景）
                log.error("系统异常，不提交offset, offset={}", record.offset(), e);
                break; // 跳出循环，不处理后续消息
            }
        }
    }
}
```

**Kafka 重试需要自己实现**：

1. **暂停消费 + 指数退避**：捕获异常后 sleep，再重试当前消息（简单但阻塞）
2. **发送到自建重试 Topic**：消费端将失败消息发送到 `order_topic_retry`，用延迟消费者轮询
3. **Spring Kafka 注解式重试**：
   ```java
   @RetryableTopic(
       attempts = "5",
       backoff = @Backoff(delay = 1000, multiplier = 2),
       include = SystemException.class  // 只重试系统异常
   )
   @KafkaListener(topics = "order_topic")
   public void listen(OrderMessage msg) { ... }
   ```

**特点**：
- 灵活性高，但需自行实现重试框架
- 不提交 offset 会导致分区阻塞（顺序场景）
- 建议配合 Spring Kafka 的 RetryableTopic 使用

---

### 5.3 RabbitMQ

RabbitMQ 支持**死信交换机（DLX, Dead Letter Exchange）**：

```java
// 1. 声明原队列，绑定死信参数
Queue queue = QueueBuilder.durable("order_queue")
    .withArgument("x-dead-letter-exchange", "dlx_exchange")
    .withArgument("x-dead-letter-routing-key", "dlx_routing_key")
    .withArgument("x-message-ttl", 30000)          // 消息 TTL 30s
    .build();

// 2. 消费失败时，nack(requeue=false)，消息进入死信队列
public void onMessage(Message message, Channel channel, long deliveryTag) {
    try {
        process(message);
        channel.basicAck(deliveryTag, false);
    } catch (BusinessException e) {
        // 业务异常：直接进死信，不重试
        channel.basicNack(deliveryTag, false, false);
    } catch (SystemException e) {
        // 系统异常：可选择 requeue=true 让 RabbitMQ 立即重试
        // 但 RabbitMQ 重试是立即的，没有退避
        channel.basicNack(deliveryTag, false, true);
    }
}
```

**特点**：
- 原生支持死信交换机，DLQ 机制完善
- 但没有内置指数退避，需要配合**延迟队列插件**或**TTL + DLX 组合**实现退避重试
- 重试次数控制需要自建计数（如消息头 `x-retry-count`）

---

## 六、顺序消息消费失败的特殊处理

顺序消息的重试是最难的，因为**不能跳过失败消息**去处理后续消息。

### 6.1 问题场景

```
Partition-0: [M1, M2, M3, M4, M5]
               │
               ▼
Consumer 顺序处理：
    M1 (创建订单)   ✓ 成功
    M2 (支付订单)   ✗ 失败（DB超时）
    M3 (发货订单)   ? 不能处理！如果跳过 M2 处理 M3，状态机不合法
```

### 6.2 方案对比

| 方案 | 实现 | 优点 | 缺点 | 适用 |
|------|------|------|------|------|
| **阻塞重试** | 当前消息一直重试直到成功或耗尽 | 简单，严格保证顺序 | 一条消息卡死，整个分区阻塞 | 分区粒度小、重试概率低 |
| **拆分分区** | 增加分区数，如 userId % 16 代替 % 4 | 降低单分区阻塞影响面 | 不能解决单 Key 卡死问题 | 降低故障半径 |
| **内存重试队列** | 失败消息放入本地优先队列，继续处理后续消息，但重试成功后再按序执行 | 不阻塞主线 | 实现极复杂，需维护消息依赖关系 | 不建议自研 |

**推荐方案（生产级）**：

```java
public class OrderedConsumer {
    private final BlockingQueue<Message> mainQueue;      // 主消费队列
    private final PriorityQueue<DelayedMessage> retryQueue; // 按重试时间排序
    private final Object lock = new Object();
    
    public void run() {
        while (running) {
            synchronized (lock) {
                // 1. 先处理已到期的重试消息
                while (!retryQueue.isEmpty() 
                       && retryQueue.peek().getRetryTime() <= now()) {
                    Message msg = retryQueue.poll().getMessage();
                    if (!tryProcess(msg)) {
                        scheduleRetry(msg); // 再次放入重试队列
                    }
                }
                
                // 2. 处理新消息
                Message msg = mainQueue.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    if (!tryProcess(msg)) {
                        scheduleRetry(msg); // 失败放入重试队列，不阻塞后续
                    }
                }
            }
        }
    }
    
    private void scheduleRetry(Message msg) {
        if (msg.getRetryCount() >= MAX_RETRY) {
            sendToDLQ(msg);
            return;
        }
        long delay = retryPolicy.getDelayMs(msg.getRetryCount());
        msg.incrementRetryCount();
        retryQueue.offer(new DelayedMessage(msg, now() + delay));
    }
}
```

**关键**：顺序消息场景下，如果某条消息重试耗尽，**必须暂停该分区消费并告警**，不能自动跳过，否则顺序性被破坏。

---

## 七、自研重试框架设计

如果让我从零设计一个生产级消费端重试框架：

```java
@Component
public class ReliableMessageConsumer {
    
    @Autowired
    private MessageProcessor processor;
    @Autowired
    private DLQSender dlqSender;
    @Autowired
    private RetryPolicy retryPolicy;
    @Autowired
    private IdempotencyChecker idempotencyChecker;
    @Autowired
    private MeterRegistry metrics;  // Micrometer 指标
    
    public void consume(Message msg) {
        String bizId = msg.getBizId();
        
        // 1. 幂等校验
        if (idempotencyChecker.isProcessed(bizId)) {
            metrics.counter("consume.idempotent.hit").increment();
            return;
        }
        
        // 2. 业务执行
        try {
            processor.process(msg);
            idempotencyChecker.markProcessed(bizId);
            metrics.counter("consume.success").increment();
            
        } catch (BusinessException e) {
            // 业务异常：不重试，直接进死信
            metrics.counter("consume.fail.business").increment();
            dlqSender.send(msg, e, FailType.BUSINESS);
            
        } catch (SystemException e) {
            // 系统异常：走重试流程
            metrics.counter("consume.fail.system").increment();
            handleSystemFailure(msg, e);
        }
    }
    
    private void handleSystemFailure(Message msg, Exception e) {
        int retryCount = msg.getRetryCount();
        
        if (retryCount >= MAX_RETRY) {
            // 重试耗尽
            metrics.counter("consume.retry.exhausted").increment();
            dlqSender.send(msg, e, FailType.EXHAUSTED);
            alert("消息重试耗尽, bizId=" + msg.getBizId());
            
        } else {
            // 延迟重试
            long delay = retryPolicy.getDelayMs(retryCount);
            msg.incrementRetryCount();
            scheduleRetry(msg, delay);
            
            metrics.counter("consume.retry.scheduled").increment();
            metrics.distributionSummary("consume.retry.delay").record(delay);
        }
    }
    
    private void scheduleRetry(Message msg, long delayMs) {
        // 发送到延迟队列，或本地定时器触发
        delayedQueue.send(msg, delayMs);
    }
}
```

**框架核心能力矩阵**：

| 能力 | 实现要点 |
|------|----------|
| 异常分类 | `BusinessException` / `SystemException` 显式区分 |
| 幂等保证 | `bizId` + 数据库幂等表（带过期清理） |
| 指数退避 | `RetryPolicy` 计算延迟，支持基础值/最大值/抖动配置 |
| 死信处理 | `DLQSender` 统一封装，带完整上下文信息 |
| 顺序保持 | 顺序消息场景下，失败消息阻塞分区或进入内存重试队列 |
| 监控埋点 | Micrometer 指标：成功/失败/重试/耗尽/延迟分布 |
| 可视化 | 后台查看 DLQ、手动重投、标记处理、异常堆栈追踪 |

---

## 八、监控与告警

重试机制必须配套监控，否则黑盒运行无法发现问题。

| 指标名 | 类型 | 说明 | 告警阈值建议 |
|--------|------|------|-------------|
| `consume.success.rate` | Rate | 消费成功率 | < 95% |
| `consume.retry.count` | Counter | 单位时间重试次数 | > 100/min |
| `consume.retry.exhausted` | Counter | 重试耗尽进入DLQ的次数 | > 0（立即告警） |
| `dlq.message.backlog` | Gauge | 死信队列积压数 | > 10 |
| `consume.latency.p99` | Timer | 消费处理延迟 P99 | > 5s |
| `consume.retry.delay` | Distribution | 重试延迟时间分布 | 观察退避是否正常 |

**告警分级**：

| 级别 | 触发条件 | 通知方式 | 响应时效 |
|------|----------|----------|----------|
| **P0-紧急** | DLQ 有新消息进入 | 电话 + 钉钉 | 5分钟内 |
| **P1-重要** | 重试次数突增（环比 > 300%） | 钉钉 | 15分钟内 |
| **P2-一般** | 消费成功率下降（< 95%） | 钉钉 | 30分钟内 |

---

## 九、面试 2 分钟口述版

> "消息消费失败的重试，我的设计核心是**先分类、再分级、有上限、有兜底**。
>
> 首先，异常一定要分两类：业务异常和系统异常。业务异常比如库存不足、参数错误，重试多少次都不会好，应该直接进死信走补偿流程。系统异常比如网络超时、DB连接失败，这种可以重试。
>
> 其次，系统异常的重试要用**指数退避**，比如 1秒、2秒、4秒、8秒，封顶30秒，避免打在下游故障点上形成雪崩。同时加**随机抖动**，防止多个失败消息同时重试形成脉冲。
>
> 然后要设**最大重试次数**，通常5到16次，达到上限后强制转入**死信队列**，触发告警通知人工介入。DLQ不是垃圾桶，必须有可视化后台能查看、重投、丢弃，最好还有自动补偿策略。
>
> 如果是顺序消息，重试会更复杂，因为不能跳过失败消息。我通常会在消费者内部维护一个**内存重试队列**，失败的消息延迟重试，但同分区的后续消息也必须在它成功后才能继续处理，或者直接把分区拆得更细来降低阻塞影响。"

---

## 十、速查表

```
┌─────────────────────────────────────────────────────────────┐
│                    消费失败处理决策树                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  消费消息                                                   │
│     │                                                       │
│     ▼                                                       │
│  幂等校验 ──► 已处理？──► 是 ──► 直接返回成功               │
│     │                    否                                 │
│     ▼                                                       │
│  执行业务                                                   │
│     │                                                       │
│     ├──► 成功 ──► 标记幂等 ──► 提交 offset                  │
│     │                                                       │
│     └──► 失败                                               │
│           │                                                 │
│      ┌────┴────┐                                            │
│      ▼         ▼                                            │
│  业务异常    系统异常                                       │
│      │         │                                            │
│      ▼         ▼                                            │
│   进DLQ    指数退避重试                                     │
│   直接补偿      │                                            │
│              ┌─┴─┐                                          │
│           成功  失败                                        │
│              │    │                                         │
│              ▼    ▼                                         │
│         提交  达到上限？                                    │
│         offset   │                                          │
│               是/否                                         │
│              ┌──┴──┐                                        │
│             否     是                                       │
│              │      │                                       │
│              ▼      ▼                                       │
│           继续   转入DLQ                                    │
│           退避   告警+人工                                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 十一、不同 MQ 重试特性速查

| 特性 | RocketMQ | Kafka | RabbitMQ |
|------|----------|-------|----------|
| **原生重试** | ✅ 内置 | ❌ 需自建 | ⚠️ 需配合DLX |
| **延迟重试** | ✅ 18级固定延迟 | ❌ 需自建 | ⚠️ 需延迟插件 |
| **死信队列** | ✅ %DLQ%Topic | ❌ 需自建 | ✅ DLX原生支持 |
| **异常分类** | 需代码区分 | 需代码区分 | 需代码区分 |
| **重试次数控制** | `maxReconsumeTimes` | 需自建 | 需自建消息头计数 |
| **消费超时重试** | ✅ 默认15min | ❌ 需自建 | ❌ 需自建 |
| **顺序消息重试** | ✅ 分区阻塞重试 | ⚠️ 需暂停消费 | ⚠️ 需暂停消费 |
| **最佳实践** | 业务异常直接返回，系统异常抛异常 | Spring RetryableTopic + 自建DLQ | DLX + TTL 延迟队列 |

---

*整理时间：2026-06-07*
*适用：消息队列、分布式系统、高可用架构面试*
