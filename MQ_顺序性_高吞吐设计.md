# MQ 设计 — 保证消息顺序性 + 高吞吐

> **核心结论**：顺序性和高吞吐天然矛盾。工业界解法是 **分区有序（Partition-level Ordering）** —— 同一业务 Key 的消息进入同一分区保证有序，不同 Key 之间并行处理实现高吞吐，整体吞吐随分区数线性扩展。

---

## 一、矛盾分析：为什么顺序性和高吞吐互斥？

| 有序级别 | 实现方式 | 并行度 | 吞吐上限 |
|----------|----------|--------|----------|
| **全局有序** | 单分区 + 单线程处理 | 1 | 单机单核处理能力（~1w QPS） |
| **分区有序** | Key 哈希到分区，分区内有序 | N（分区数） | 随分区数线性扩展 |
| **完全无序** | 随机分区 + 多线程并发 | N × M（分区×线程） | 极高 |

**本质**：顺序性要求**偏序关系**（happens-before），而高吞吐要求**并行处理**。只有将需要有序的消息限定在"同一组"内，组与组之间才能并行。

---

## 二、整体架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                        生产者集群                                 │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                         │
│  │Producer-1│  │Producer-2│  │Producer-3│                         │
│  └────┬────┘  └────┬────┘  └────┬────┘                         │
│       │            │            │                               │
│       └────────────┴────────────┘                               │
│                    │                                            │
│                    ▼ 分区路由（Key Hash）                         │
└─────────────────────────────────────────────────────────────────┘
         │          │          │          │
         ▼          ▼          ▼          ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
   │Broker-1  │ │Broker-2  │ │Broker-3  │ │Broker-4  │
   │Partition-0│ │Partition-1│ │Partition-2│ │Partition-3│
   │(userId%4=0)│ │(userId%4=1)│ │(userId%4=2)│ │(userId%4=3)│
   └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
        │            │            │            │
        └────────────┴────────────┴────────────┘
                     │
                     ▼
        ┌────────────────────────────┐
        │        消费者集群            │
        │  ┌──────┐ ┌──────┐        │
        │  │Thread│ │Thread│  ...   │
        │  │  A   │ │  B   │        │
        │  └──────┘ └──────┘        │
        │  (单线程顺序处理一个分区)    │
        └────────────────────────────┘
```

**核心原则**：
1. **同 Key 同分区**：`hash(key) % partitionNum` 决定分区，保证同一业务标识的消息进入同一分区
2. **单分区单线程**：一个分区只能被一个消费者线程顺序消费，避免多线程乱序
3. **分区间并行**：不同分区的消息完全并行，实现整体高吞吐

---

## 三、生产者端设计

### 3.1 分区路由算法

```java
public class KeyPartitioner {
    /**
     * 根据业务 Key 计算分区
     * 保证同一个 orderId / userId 始终路由到同一分区
     */
    public int partition(String topic, String key, int numPartitions) {
        if (key == null) {
            // 无 Key 的消息随机分发（无序场景）
            return ThreadLocalRandom.current().nextInt(numPartitions);
        }
        return Math.abs(key.hashCode()) % numPartitions;
    }
}

// 使用示例
Message msg = new Message("order_topic", "UPDATE", orderId, payload);
producer.send(msg);
```

**注意**：`key.hashCode()` 在 Java 中可能为负数，必须取 `abs` 后再取模。

---

### 3.2 幂等生产者（防重试乱序）

网络超时触发重试时，可能导致同一消息被发送两次，破坏顺序性。

**解决方案**：

```java
// 为每个生产者实例分配唯一 PID（Producer ID）
// 为每条消息分配单调递增的 Sequence ID
public class IdempotentProducer {
    private final long pid;           // Producer 唯一标识
    private final AtomicLong seq = new AtomicLong(0);
    
    public void send(Message msg) {
        long sequenceId = seq.incrementAndGet();
        
        Record record = new Record();
        record.setPid(pid);
        record.setSeq(sequenceId);
        record.setKey(msg.getKey());
        record.setPayload(msg.getPayload());
        
        // Broker 端维护 (pid, seq) -> offset 映射
        // 如果 seq 已存在，直接返回已写入的 offset，不重复存储
        broker.send(record);
    }
}
```

**Broker 端去重逻辑**：

```java
public class Broker {
    // 每个 Producer 的最新 Seq ID
    private Map<Long, Long> producerSeqMap = new ConcurrentHashMap<>();
    
    public boolean isDuplicate(long pid, long seq) {
        Long lastSeq = producerSeqMap.get(pid);
        return lastSeq != null && seq <= lastSeq;
    }
    
    public void append(long pid, long seq, Record record) {
        if (isDuplicate(pid, seq)) {
            return; // 重复消息，丢弃
        }
        // 追加到日志
        log.append(record);
        producerSeqMap.put(pid, seq);
    }
}
```

**Kafka 的实现**：`enable.idempotence=true`，内部维护 `PID + Sequence Number`。

---

### 3.3 批量发送（Batching）

```java
public class BatchProducer {
    private final List<Message> buffer = new ArrayList<>();
    private final int batchSize = 16 * 1024;  // 16KB
    private final int lingerMs = 5;            // 最多等待 5ms
    
    public void send(Message msg) {
        buffer.add(msg);
        
        // 触发条件 1：缓冲区满
        // 触发条件 2：linger 时间到了（定时器触发）
        if (bufferSize() >= batchSize || timerExpired()) {
            flush();
        }
    }
    
    private void flush() {
        // 同分区的消息打包成一个 Batch 发送
        // 减少网络 RTT，提升吞吐
        for (PartitionBatch batch : groupByPartition(buffer)) {
            nettyChannel.writeAndFlush(batch);
        }
        buffer.clear();
    }
}
```

**收益**：单条消息 1KB，batch 后一次发 16 条，网络 I/O 降低为 1/16。

---

## 四、Broker 端设计

### 4.1 存储结构：追加写日志（Append-Only Log）

```
/data/mq/order_topic-0/
    ├── 00000000000000000000.log       # 消息数据（顺序追加写）
    ├── 00000000000000000000.index     # 稀疏索引（offset -> 物理位置）
    ├── 00000000000000000000.timeindex # 时间索引
    ├── 00000000000001000000.log       # 滚动生成新 segment（1GB 一个）
    └── 00000000000001000000.index
```

**设计要点**：

| 特性 | 说明 |
|------|------|
| **顺序追加写** | 消息按到达顺序写入文件尾部，O(1) 复杂度 |
| **不可变** | 已写入的消息不修改、不删除，只标记过期 |
| **offset 单调递增** | 每条消息分配全局递增 offset，消费者按 offset 顺序读取 |
| **稀疏索引** | 每 4KB 数据建立一条索引，加速 offset 查找 |

---

### 4.2 零拷贝（Zero-Copy）

#### 传统方式（4 次数据拷贝 + 4 次上下文切换）

```
磁盘 ──[DMA]──► 内核缓冲区 ──[CPU拷贝]──► 用户态缓冲区 ──[CPU拷贝]──► Socket缓冲区 ──[DMA]──► 网卡
       拷贝1              拷贝2                  拷贝3               拷贝4
```

#### 零拷贝方式（2 次数据拷贝 + 2 次上下文切换）

```
磁盘 ──[DMA]──► 内核缓冲区 ───────────[DMA Gather]───────────► 网卡
       拷贝1                                          拷贝2
       
       内核直接通过 sendfile() 系统调用，
       不经过用户态缓冲区
```

**Java 实现**：

```java
// FileChannel.transferTo() 底层调用 sendfile
FileChannel fileChannel = new RandomAccessFile(logFile, "r").getChannel();
SocketChannel socketChannel = SocketChannel.open();

fileChannel.transferTo(position, count, socketChannel);
```

**收益**：消费量大时，CPU 拷贝开销从 O(n) 降到 O(1)，吞吐提升数倍。

---

### 4.3 消息压缩

```java
// 批量后对整批消息压缩，减少网络带宽
public enum CompressionType {
    NONE,    // 不压缩
    SNAPPY,  // 压缩/解压速度快，CPU 开销低
    LZ4,     // 更快的压缩速度，稍大的体积
    ZSTD,    // 压缩比最高，但 CPU 开销稍大
    GZIP     // 压缩比高，但速度慢
}
```

**权衡**：压缩增加 CPU 开销（~5-10%），但大幅降低网络带宽（~70-90%），整体吞吐提升显著。

---

### 4.4 副本同步（ISR 机制）

```
Leader (Partition-0)  ──►  Follower-1 (实时拉取，顺序同步)
      │
      └──►  Follower-2 (实时拉取，顺序同步)

ISR = {Leader, Follower-1, Follower-2}
```

**关键设计**：

| 机制 | 说明 |
|------|------|
| **Leader 负责读写** | 所有生产者写请求落到 Leader，消费者读请求也优先读 Leader |
| **Follower 顺序同步** | Follower 按 Leader 的 offset 顺序拉取，保证副本间完全一致 |
| **ISR 列表** | 只有与 Leader 差距小于阈值的副本才在 ISR 中，保证数据一致性 |
| **acks=all** | 生产者等待 ISR 中所有副本确认后才返回成功，保证消息不丢 |
| **Leader 故障切换** | 从 ISR 中选举新 Leader，保证切换后顺序不丢 |

---

## 五、消费者端设计

### 5.1 单线程顺序消费（基础方案）

```java
public class SingleThreadConsumer {
    private final MQConsumer consumer;
    private final String topic;
    private volatile boolean running = true;
    
    public void run() {
        consumer.subscribe(topic);
        
        // 关键：一个线程只消费一个分区，单线程顺序处理
        while (running) {
            List<Message> messages = consumer.poll(Duration.ofMillis(100));
            
            for (Message msg : messages) {
                try {
                    // 同步顺序处理，不提交异步任务
                    process(msg);
                    
                    // 处理成功后，同步提交 offset
                    consumer.commitSync(msg.getOffset());
                    
                } catch (Exception e) {
                    // 处理失败，不提交 offset，下次 poll 会重新消费
                    log.error("消费失败, offset={}", msg.getOffset(), e);
                    break; // 跳出循环，不处理后续消息（保证顺序）
                }
            }
        }
    }
    
    private void process(Message msg) {
        // 业务逻辑：如更新订单状态
        orderService.updateStatus(msg);
    }
}
```

**关键约束**：
- 处理失败时**不能跳过**，否则后续消息会基于错误状态处理
- 必须 `break` 跳出循环，等当前消息处理成功后再继续

---

### 5.2 内存队列 + Key 哈希分发（进阶方案）

如果消费逻辑有 IO 阻塞（如查 DB、调 RPC），纯单线程会成为瓶颈。

**优化思路**：线程间并行，线程内串行。

```java
public class KeyOrderedConsumer {
    private final int concurrency;           // 并行线程数
    private final BlockingQueue<Message>[] queues;  // 每个线程一个队列
    private final ExecutorService executor;
    
    @SuppressWarnings("unchecked")
    public KeyOrderedConsumer(int concurrency) {
        this.concurrency = concurrency;
        this.queues = new BlockingQueue[concurrency];
        for (int i = 0; i < concurrency; i++) {
            queues[i] = new LinkedBlockingQueue<>(1000);
        }
        this.executor = Executors.newFixedThreadPool(concurrency);
    }
    
    public void start() {
        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            executor.submit(() -> {
                while (running) {
                    try {
                        Message msg = queues[index].take();
                        process(msg);  // 同步处理，保证队列内有序
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
    }
    
    /**
     * 分发消息：同 Key 的消息始终进入同一个队列
     */
    public void dispatch(Message msg) {
        int queueIndex = Math.abs(msg.getKey().hashCode()) % concurrency;
        try {
            queues[queueIndex].put(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("分发中断", e);
        }
    }
}
```

**核心保证**：
- `hash(key) % concurrency` 确保同 Key 的消息始终进入同一队列
- 每个队列由独立线程顺序消费，保证该 Key 的消息有序
- 不同 Key 的消息在不同队列并行处理，提升整体吞吐

---

### 5.3 消费者 Rebalance 设计

当消费者加入或退出时，分区需要重新分配：

```
初始状态：
    Consumer-A: Partition-0, Partition-1
    Consumer-B: Partition-2, Partition-3

Consumer-C 加入后：
    Consumer-A: Partition-0
    Consumer-B: Partition-2
    Consumer-C: Partition-1, Partition-3  (重新分配)
```

**Rebalance 过程**：
1. 协调器（Coordinator）感知成员变化
2. 暂停所有消费者的拉取
3. 重新计算分区分配方案
4. 通知各消费者新的分配结果
5. 消费者按新方案从对应分区继续消费

**影响**：Rebalance 期间整个消费组暂停消费，造成延迟。优化手段：
- **Sticky Assignor**：尽量保持原有分配，减少迁移
- **静态成员**：消费者重启后保持原有分区分配（`group.instance.id`）

---

## 六、高吞吐优化手段汇总

| 优化手段 | 原理 | 收益 |
|----------|------|------|
| **批量发送** | 多条消息打包一次网络请求 | 减少 RTT，提升 5-10x 吞吐 |
| **零拷贝** | sendfile/mmap 绕过用户态 | 降低 CPU 开销 50%+ |
| **消息压缩** | Snappy/LZ4/Zstd 压缩 payload | 减少带宽 70-90% |
| **分区并行** | 多分区多消费者并行消费 | 吞吐随分区数线性扩展 |
| **异步刷盘** | 消息先写 PageCache，后台刷盘 | 降低写延迟（牺牲少量持久性） |
| **内存队列缓冲** | 消费者内用队列解耦拉取和处理 | 平滑消费速率，提升 CPU 利用率 |

---

## 七、不同有序级别的方案对比

| 有序级别 | 实现方式 | 吞吐 | 适用场景 |
|----------|----------|------|----------|
| **全局有序** | 单分区 + 单线程 | 极低（~1w QPS） | 金融对账、证券撮合（极少场景） |
| **分区有序** | Key 哈希到分区 + 分区单线程 | 高（随分区数线性扩展） | 订单状态变更、用户行为序列 |
| **组内有序** | 同 Group ID 的消息有序 | 中高 | 多租户场景、房间消息 |
| **无序** | 随机分区 + 多线程并发 | 极高 | 日志采集、metrics、监控数据 |

**设计建议**：
- 90% 的场景用**分区有序**即可满足需求
- 全局有序只有在"全量数据必须严格排队处理"时才需要，且要通过硬件加速（RDMA、NVMe）弥补性能

---

## 八、与 Kafka / RocketMQ 的对比

| 设计点 | Kafka | RocketMQ | 本设计方案 |
|--------|-------|----------|-----------|
| **有序单位** | Partition | MessageQueue（类似 Partition） | Partition |
| **分区路由** | `key.hashCode() % partitions` | `hash(key) % queueNum` | 同左 |
| **消费者并行** | 一个 Partition 只能被一个 Consumer 消费 | 一个 Queue 只能被一个 Consumer 消费 | 同左 |
| **单线程保证** | 单 Consumer 单线程处理 Partition | 同左 | 同左 |
| **零拷贝** | `sendfile()` | `mmap()` + 自定义实现 | `sendfile()` |
| **批量发送** | 支持 | 支持 | 支持 |
| **消息压缩** | Snappy/LZ4/Zstd/GZIP | LZ4/Snappy/ZSTD | Snappy/LZ4/Zstd |
| **幂等生产者** | 支持（PID + Sequence ID） | 支持 | PID + Sequence ID |
| **副本机制** | ISR（In-Sync Replicas） | 主从同步 + DLedger | ISR |
| **延迟消息** | 需借助外部组件 | 原生支持 18 级延迟 | 可扩展实现 |

---

## 九、常见面试追问

### Q1：如果一个 Key 是热点，导致某个 Partition 打满怎么办？

> 这是分区有序的固有缺陷。**单 Key 的吞吐上限 = 单分区的吞吐上限**。
>
> 解决思路：
> 1. **热点 Key 打散**：如 `orderId#0`, `orderId#1`，让同一业务的不同子流进入不同分区，但会牺牲全局有序
> 2. **内存队列优化**：在消费者端用更多线程处理该分区，同 Key 固定线程，提升单机处理能力
> 3. **硬件升级**：更快的磁盘（NVMe）、更大的网卡带宽、更高主频的 CPU
> 4. **如果必须单 Key 有序且量极大**，只能接受单分区瓶颈，这是 CAP 的必然结果

---

### Q2：消费者扩容时，Rebalance 会导致乱序吗？

> Rebalance 本身不会导致乱序，但会**暂停消费**。
>
> 过程：协调器感知成员变化 → 暂停所有消费者拉取 → 重新分配分区 → 恢复消费。期间消息积压在 Broker，恢复后消费者从最新 offset 继续，分区内顺序不变。
>
> 优化：使用 Sticky Assignor 减少分区迁移，或使用静态成员避免重启触发 Rebalance。

---

### Q3：如何保证消息不丢且有序？

> 三层保障：
> 1. **生产者**：`acks=all`（等所有 ISR 副本确认）+ 同步重试 + 幂等生产者去重
> 2. **Broker**：多副本 + ISR 机制 + 同步刷盘（或异步刷盘 + 副本冗余）
> 3. **消费者**：手动提交 offset，处理成功后才 commit；处理失败不跳过，break 等待重试

---

### Q4：如果消费者处理慢，会不会阻塞整个分区？

> **会**。这是单线程消费保证顺序的代价。
>
> 优化：
> 1. 消费者内部用**内存队列 + Key 哈希**做到线程间并行
> 2. 增加分区数，降低单个分区的流量
> 3. 优化消费逻辑本身（如批量 DB 操作、异步 RPC）
> 4. 如果某条消息一直处理失败，可以设置**最大重试次数**后转入死信队列，避免无限阻塞

---

### Q5：全局有序和分区有序，怎么选？

> 问自己一个问题：**不同 Key 之间的消息，是否真的需要严格先后关系？**
>
> - 订单 A 的创建和支付必须有序，但订单 A 和订单 B 之间谁先谁后不影响业务 → **分区有序**
> - 证券交易的撮合引擎，所有委托单必须按时间严格排序 → **全局有序**
>
> 绝大多数业务（电商、社交、物流）都是前者。

---

## 十、面试 2 分钟口述版

> "顺序性和高吞吐是天然矛盾的。我的设计核心是保证**分区有序**，而不是全局有序。
>
> 首先，生产者端用业务 Key（如订单号）做哈希路由，保证同一个 Key 的消息始终进入同一个 Partition。Partition 内部是追加写日志，天然有序。
>
> 其次，消费者端一个 Partition 只能被一个线程顺序处理，避免多线程乱序。如果消费逻辑有 IO 阻塞，我会在消费者内部用**内存队列 + Key 哈希分发**，做到线程间并行、线程内串行。
>
> 为了提升吞吐，我会用**批量发送**减少网络 RTT，**零拷贝**降低 CPU 开销，**消息压缩**减少带宽。副本同步保证 ISR 内的顺序一致性。
>
> 这样设计的吞吐可以随分区数线性扩展，同时保证业务关心的 Key 级别有序。如果是金融撮合这种必须全局有序的场景，只能退化成单队列，靠硬件加速扛吞吐。"

---

## 十一、架构速记图

```
┌─────────────────────────────────────────────┐
│                  生产者                       │
│  ┌───────────────────────────────────────┐  │
│  │  1. 分区路由：hash(key) % N            │  │
│  │  2. 批量积累（16KB / 5ms）              │  │
│  │  3. 压缩（Snappy/LZ4）                  │  │
│  │  4. 幂等：PID + Sequence ID            │  │
│  └───────────────────────────────────────┘  │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
        ┌─────────────────────────┐
        │       Broker 集群        │
        │  ┌─────┐ ┌─────┐       │
        │  │ P0  │ │ P1  │  ...   │  Partition = 顺序单元
        │  │Leader│ │Leader│      │  ISR 副本保证不丢
        │  └─────┘ └─────┘       │  追加写 + 零拷贝
        └─────────────────────────┘
                      │
                      ▼
        ┌─────────────────────────┐
        │       消费者             │
        │  ┌─────────────────┐    │
        │  │ 单线程/Key哈希队列 │    │  同 Key 串行
        │  │ 内存缓冲 + 批量提交 │    │  不同 Key 并行
        │  └─────────────────┘    │
        └─────────────────────────┘
```

---

*整理时间：2026-06-07*
*适用：分布式系统、消息队列、高并发架构面试*
