# 线上CPU飙高排查案例：锁竞争导致的性能雪崩

> **场景一**：电商大促期间，订单号生成服务CPU飙高，接口RT突增  
> **场景二**：IM统一告警平台，信号发射器（DB自增序列号）多版本混用，突发告警时CPU飙高、消息堆积  
> 核心考点：`synchronized` 锁粒度过粗 + 锁内嵌套DB查询 + 高并发突发流量下的锁竞争  
> 排查工具链：`top` → `pidstat` → `jstack` → `arthas thread -b`

---

## 一、事故背景

**系统**：订单中心 - 订单号生成服务（核心链路）  
**架构**：Spring Boot + Dubbo，单节点部署（4C8G）  
**业务逻辑**：
- 每次下单需要生成唯一订单号
- 为保证唯一性，使用数据库号段模式（先DB批量申请，内存缓存自增）
- 号段用完时触发**同步刷新**，从DB加载新号段

**核心代码（问题版本）**：
```java
@Component
public class OrderIdGenerator {
    
    private final Object lock = new Object();
    private volatile long currentId = 0;
    private volatile long maxId = 0;
    
    @Autowired
    private OrderIdSegmentMapper segmentMapper;
    
    public String nextId() {
        synchronized (lock) {  // 【问题点1】全局互斥锁
            if (currentId >= maxId) {
                // 【问题点2】锁内嵌套DB查询 + RPC调用
                OrderIdSegment segment = segmentMapper.fetchNewSegment("order");
                maxId = segment.getMaxId();
                currentId = segment.getStartId();
                
                // 异步通知库存服务预占（为了方便理解，假设是同步调用）
                inventoryService.preAllocate(segment.getStartId(), segment.getMaxId());
            }
            long id = currentId++;
            return "OD" + System.currentTimeMillis() + String.format("%06d", id);
        }
    }
}
```

---

## 二、故障现象

### 2.1 监控报警（14:32）

```
【P0告警】order-id-srv-03 CPU使用率 94.2%（阈值：70%）
【P1告警】订单创建接口 P99 RT 从 15ms → 3200ms
【P1告警】线程池活跃线程数 198/200，接近耗尽
【P1告警】接口错误率 0.8%（超时导致）
```

### 2.2 初步观察

- **只有这一台机器CPU高**，其他节点正常（20%~30%）
- 不是GC问题：GC日志正常，Full GC 未触发
- 不是流量突增：QPS 与平时持平（约 2000/s）
- 怀疑：**线程阻塞或死循环**

---

## 三、排查过程（时间线）

### 步骤1：确认是哪个进程耗CPU（14:35）

```bash
$ top -bn1 | head -20
  PID USER   PR  NI  VIRT  RES  SHR S %CPU %MEM   TIME+ COMMAND
12345 root   20   0  4.2g 1.1g  16m R 94.3 14.2  15:32 java
```

确认是 Java 进程 PID=12345 占用了 94.3% 的 CPU。

### 步骤2：定位具体线程（14:36）

```bash
$ top -Hp 12345 -bn1 | head -20
  PID USER   PR  NI  VIRT  RES  SHR S %CPU %MEM   TIME+ COMMAND
12501 root   20   0  4.2g 1.1g  16m R 12.3 14.2   0:42.32 java
12502 root   20   0  4.2g 1.1g  16m R 11.8 14.2   0:41.89 java
12503 root   20   0  4.2g 1.1g  16m R 11.5 14.2   0:41.56 java
12504 root   20   0  4.2g 1.1g  16m R 11.2 14.2   0:40.12 java
12505 root   20   0  4.2g 1.1g  16m R 10.9 14.2   0:39.88 java
...（共10+个线程占用CPU都在10%以上）
```

**关键发现**：
- 不是单个线程占满CPU，而是**十几个线程各占10%左右**
- 这是典型的**多线程竞争锁**特征：多个线程都在自旋或反复尝试获取锁

### 步骤3：线程ID转16进制（14:37）

```bash
$ printf "%x\n" 12501
30d5
$ printf "%x\n" 12502
30d6
```

### 步骤4：jstack 查看线程堆栈（14:38）

```bash
$ jstack 12345 | grep -A 50 "30d5"
"DubboServerHandler-10.0.0.5:20880-thread-198" #198 prio=5 os_prio=0 tid=0x00007f8b94003000 nid=0x30d5 **runnable** [0x00007f8b6c5a5000]
   java.lang.Thread.State: **RUNNABLE**
        at java.net.SocketInputStream.socketRead0(Native Method)
        at java.net.SocketInputStream.socketRead(SocketInputStream.java:116)
        at java.net.SocketInputStream.read(SocketInputStream.java:171)
        at java.net.SocketInputStream.read(SocketInputStream.java:141)
        at com.mysql.jdbc.util.ReadAheadInputStream.fill(ReadAheadInputStream.java:101)
        at com.mysql.jdbc.util.ReadAheadInputStream.readFromUnderlyingStreamIfNecessary(ReadAheadInputStream.java:144)
        at com.mysql.jdbc.util.ReadAheadInputStream.read(ReadAheadInputStream.java:174)
        at com.mysql.jdbc.MysqlIO.readFully(MysqlIO.java:3001)
        ...
        at com.xxx.OrderIdGenerator.nextId(OrderIdGenerator.java:42)
        - **locked <0x000000076e0486e8> (a java.lang.Object)**  【持有锁】
        at com.xxx.OrderCreateService.createOrder(OrderCreateService.java:88)
```

再看另一个线程：
```bash
$ jstack 12345 | grep -A 50 "30d6"
"DubboServerHandler-10.0.0.5:20880-thread-199" #199 prio=5 os_prio=0 tid=0x00007f8b94004000 nid=0x30d6 **runnable** [0x00007f8b6c4a4000]
   java.lang.Thread.State: **RUNNABLE**
        at sun.misc.Unsafe.park(Native Method)
        at java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)
        at java.util.concurrent.locks.AbstractQueuedSynchronizer.parkAndCheckInterrupt(AbstractQueuedSynchronizer.java:836)
        at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireQueued(AbstractQueuedSynchronizer.java:870)
        at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:1199)
        at java.util.concurrent.locks.ReentrantLock$NonfairSync.lock(ReentrantLock.java:209)
        ...
        - **waiting to lock <0x000000076e0486e8> (a java.lang.Object)**  【等待锁】
        at com.xxx.OrderIdGenerator.nextId(OrderIdGenerator.java:38)
```

### 步骤5：批量分析线程状态（14:40）

```bash
$ jstack 12345 | grep "java.lang.Thread.State" | sort | uniq -c
    198 java.lang.Thread.State: RUNNABLE
      2 java.lang.Thread.State: TIMED_WAITING
```

**198个线程全是 RUNNABLE**，但大部分不是在执行有效计算，而是在：
- 1个线程：**RUNNABLE** + `socketRead0`（持有锁，执行DB查询）
- 197个线程：**RUNNABLE** + `park`（自旋等待锁，CPU空转）

> 这就是锁竞争导致CPU飙高的典型特征：**没有BLOCKED状态的线程，全是RUNNABLE，但都在空转等锁**。

### 步骤6：Arthas 快速确认（14:42）

```bash
$ arthas 12345
[arthas@12345]$ thread -b  # 查看阻塞线程
"DubboServerHandler-10.0.0.5:20880-thread-198" Id=198 **BLOCKED** on java.lang.Object@76e0486e8 owned by "DubboServerHandler-10.0.0.5:20880-thread-197" Id=197
    at com.xxx.OrderIdGenerator.nextId(OrderIdGenerator.java:38)
    
[arthas@12345]$ thread -n 10  # 查看CPU占用top10线程
"DubboServerHandler-10.0.0.5:20880-thread-198" Id=198 cpuUsage=12.3% ...
"DubboServerHandler-10.0.0.5:20880-thread-199" Id=199 cpuUsage=11.8% ...
...
```

### 步骤7：火焰图验证（14:45）

使用 async-profiler 生成火焰图：
```bash
$ ./profiler.sh -d 30 -f cpu.html 12345
```

火焰图显示：
- `java.util.concurrent.locks.LockSupport.park` 占用 **85%**
- `sun.misc.Unsafe.park` → `AQS.acquireQueued` → `ReentrantLock.lock`
- 业务代码仅占 **8%**

**结论**：CPU不是被业务逻辑吃掉的，而是被**锁竞争的自旋消耗**掉的。

---

## 四、根因分析

### 4.1 问题链

```
号段耗尽（currentId >= maxId）
    ↓
线程A进入 synchronized，执行DB查询（耗时 ~50ms）
    ↓
线程B~Z同时请求下单，全部被挡在 synchronized 外
    ↓
线程B~Z在 RUNNABLE 状态自旋等待（ park → unpark 循环）
    ↓
CPU飙高（大量空转）+ 线程池打满（198/200）+ RT飙升
```

### 4.2 代码层面的问题

| 问题 | 说明 |
|------|------|
| **全局互斥锁** | `synchronized(lock)` 锁住了整个生成逻辑，包括无争议的内存自增 |
| **锁内嵌套IO** | DB查询（~50ms）+ RPC调用（~20ms）发生在锁内 |
| **号段过小** | 每次只申请1000个号，高频触发刷新 |
| **无降级策略** | 号段耗尽时所有请求串行，没有预加载机制 |

### 4.3 延伸场景：IM告警平台的信号发射器

这是另一个线上真实场景的变体：**IM统一告警平台的信号发射器，为告警消息生成唯一序列号，多版本混用，突发告警时触发CPU飙高**。

**业务背景**：
- 统一告警平台接收各业务系统的告警事件（监控异常、日志错误、业务阈值突破等）
- 告警通过 Kafka 异步消费，经不同通道下发（短信、钉钉、企业微信、邮件）
- 每条告警消息需要唯一序列号，用于：消息幂等去重、消费顺序追踪、链路排查
- 信号发射器经历v1/v2/v3三个版本迭代，线上同时存在

**架构链路**：
```
业务系统告警 → Kafka Topic → 告警消费服务(100并发) → 信号发射器(nextSeq()) 
                                                              ↓
                                                    短信/钉钉/企微/邮件通道
```

**问题代码（v2版本，线上流量最大）**：
```java
@Component
public class AlertSeqGenerator {
    
    // v2版本：synchronized方法 + DB自增号段
    private volatile long currentSeq = 0;
    private volatile long maxSeq = 0;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    public synchronized long nextSeq() {
        if (currentSeq >= maxSeq) {
            // 直接在锁内查DB申请号段
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT max_seq FROM alert_seq_segment WHERE channel = 'im' FOR UPDATE"
            );
            long start = (Long) row.get("max_seq");
            maxSeq = start + 500;  // 【号段只有500个】
            currentSeq = start;
            
            jdbcTemplate.update(
                "UPDATE alert_seq_segment SET max_seq = ? WHERE channel = 'im'",
                maxSeq
            );
        }
        return currentSeq++;
    }
}
```

**为什么IM告警场景特别容易触发这个问题？**

| 场景特征 | 说明 | 对信号发射器的影响 |
|---------|------|------------------|
| **突发流量** | 某系统故障时，瞬间产生10万+告警 | 消费端100线程并发消费，同时调用`nextSeq()` |
| **号段极小** | 只有500个号 | 10万条消息意味着要刷新200次号段 |
| **同步阻塞** | 每条消息必须拿到seq才能下发 | 拿不到seq就阻塞Kafka消费，lag暴涨 |
| **多版本混用** | v1/v2/v3同时在线 | v1用表 `alert_seq_v1`，v2用 `alert_seq_segment`，v3用Redis，但DB连接池共享 |

**CPU飙高的触发链路**：

```
14:23 某支付系统宕机，瞬间涌入8万条告警Kafka消息
    ↓
14:23:05 告警消费服务100个线程并发消费，同时调用 nextSeq()
    ↓
14:23:06 前500个号瞬间被抢完，线程A进入 synchronized，执行DB号段申请（~30ms）
    ↓
14:23:06 线程B~Z（剩余99个）全部阻塞在 synchronized 外，RUNNABLE自旋等待
    ↓
14:23:06~14:23:15 号段每500个耗尽一次，DB行锁 + JVM对象锁双重串行
    ↓
CPU飙高至85% + Kafka消费lag从0暴涨至6万 + 告警延迟从3秒恶化至5分钟
```

**与场景一（订单号）的核心区别**：

| 对比维度 | 订单号生成（场景一） | 告警序列号（场景二） |
|---------|-------------------|-------------------|
| **流量模式** | 平稳高并发（2000 QPS） | **突发脉冲**（平时10 QPS，故障时瞬间10万） |
| **号段大小** | 1000个 | 500个（更小） |
| **CPU形态** | 持续高位（90%+） | **脉冲式尖刺**（告警突发时飙高，平时正常） |
| **故障影响** | 订单创建变慢 | **告警延迟/丢失**（核心运维能力受损） |
| **混用问题** | 单版本 | v1/v2/v3混用，DB连接池竞争 |
| **排查难点** | 持续可复现 | 只有告警突发时才出现，平时稳定 |

**线程Dump特征（IM告警场景）**：
```
"alert-consumer-thread-87" #287 RUNNABLE
    at java.net.SocketInputStream.socketRead0(Native Method)
    at com.mysql.jdbc.MysqlIO.readFully(MysqlIO.java:3001)
    ...
    at com.xxx.AlertSeqGenerator.nextSeq(AlertSeqGenerator.java:18)
    - locked <0x000000076b5c4d58> (a com.xxx.AlertSeqGenerator)
    
"alert-consumer-thread-88" #288 RUNNABLE
    at sun.misc.Unsafe.park
    - waiting to lock <0x000000076b5c4d58> (a com.xxx.AlertSeqGenerator)
    at com.xxx.AlertSeqGenerator.nextSeq(AlertSeqGenerator.java:12)
    
"alert-consumer-thread-89" #289 RUNNABLE
    at sun.misc.Unsafe.park
    - waiting to lock <0x000000076b5c4d58> (a com.xxx.AlertSeqGenerator)
    
...（共87个消费线程在等待同一个锁）
```

**Kafka监控侧的特征**：
```
【P0告警】alert-kafka-topic lag 从 0 → 62,000（5分钟内）
【P1告警】告警下发延迟 P99 从 2s → 312s
【P1告警】短信通道成功率从 99.5% → 71%（大量超时）
```

**多版本混用如何放大问题？**

```
v1版本：直连DB，每次 INSERT INTO alert_seq_v1 ... SELECT LAST_INSERT_ID()
       → 100个线程同时 INSERT，MySQL自增锁竞争，但无JVM锁
       
v2版本：synchronized + DB号段（上面讲的问题代码）
       → JVM锁竞争 + DB行锁竞争
       
v3版本：Redis incr（本该是好的方案）
       → 但DB连接池被v1/v2占满，v3的Redis操作线程也在等连接池
```

三个版本共享同一个 **HikariCP连接池**（max=50）：
- v2申请号段时占用连接（~30ms）
- v1每次seq都占连接（~5ms）
- v3虽然用Redis，但Redis操作前可能需要查配置（走DB），也拿不到连接

**结果**：不是单一锁的问题，而是 **JVM锁 + DB行锁 + 连接池耗尽** 的三重资源竞争。

---

## 五、解决方案

### 5.1 紧急止血（14:50）

1. **重启应用**：清空线程阻塞状态（临时恢复）
2. **限流**：对订单创建接口限流至原QPS的50%
3. **扩容**：临时增加2台节点，分散压力

### 5.2 代码优化（当天修复上线）

**优化版代码**：
```java
@Component
public class OrderIdGenerator {
    
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long currentId = 0;
    private volatile long maxId = 0;
    
    @Autowired
    private OrderIdSegmentMapper segmentMapper;
    
    // 【优化1】双Buffer预加载：主号段 + 备号段
    private volatile OrderIdSegment nextSegment = null;
    
    public String nextId() {
        long id;
        
        // 【优化2】无锁快速路径：内存自增不需要锁
        while (true) {
            long current = currentId;
            long max = maxId;
            
            if (current < max) {
                // CAS自增，无需加锁
                if (UNSAFE.compareAndSwapLong(this, CURRENT_ID_OFFSET, current, current + 1)) {
                    id = current;
                    break;
                }
                // CAS失败，重试
            } else {
                // 号段耗尽，走加锁刷新路径
                id = refreshAndGetId();
                break;
            }
        }
        
        return "OD" + System.currentTimeMillis() + String.format("%06d", id);
    }
    
    private long refreshAndGetId() {
        lock.lock();
        try {
            // 双重检查
            if (currentId < maxId) {
                return currentId++;
            }
            
            // 【优化3】使用备号段，避免锁内等待DB
            if (nextSegment != null) {
                currentId = nextSegment.getStartId();
                maxId = nextSegment.getMaxId();
                nextSegment = null;
                
                // 【优化4】异步预加载下一段
                asyncPreloadNextSegment();
                return currentId++;
            }
            
            // 实在没有备号段了，才在锁内查DB（兜底）
            OrderIdSegment segment = segmentMapper.fetchNewSegment("order");
            currentId = segment.getStartId();
            maxId = segment.getMaxId();
            
            asyncPreloadNextSegment();
            return currentId++;
        } finally {
            lock.unlock();
        }
    }
    
    // 异步预加载（线程池）
    private void asyncPreloadNextSegment() {
        preloadExecutor.execute(() -> {
            OrderIdSegment segment = segmentMapper.fetchNewSegment("order");
            nextSegment = segment;
        });
    }
}
```

### 5.3 架构层优化（后续迭代）

| 优化点 | 方案 |
|--------|------|
| **号段调大** | 从1000 → 100,000，减少DB访问频率 |
| **本地缓存** | 号段缓存在本地内存 + 定时异步刷新 |
| **雪花算法** | 长期演进为雪花算法（无需号段，无锁生成） |
| **独立服务** | 将ID生成抽离为独立服务（Leaf架构），业务侧纯内存自增 |

---

## 六、优化效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| CPU使用率 | 94% | 18% |
| P99 RT | 3200ms | 12ms |
| DB QPS（号段表） | 2000/s | 0.2/s |
| 线程池使用率 | 198/200 | 15/200 |
| 锁竞争时间（arthas） | 占比85% | 占比<1% |

---

## 七、复盘总结

### 7.1 锁竞争导致CPU飙高的特征

1. **多线程CPU分布均匀**：top -Hp 看到多个线程各占10~15%，不是单线程100%
2. **线程状态诡异**：大量RUNNABLE，但堆栈显示在 `park/unpark` 或 `Unsafe.park`
3. **火焰图尖刺**：`AQS`、`LockSupport.park` 占据大头
4. **业务耗时极低**：真正业务代码占比<10%

### 7.2 排查口诀

```
CPU高，先看堆栈；
多线程，均匀占CPU；
RUNNABLE，却在park；
八成是，锁竞争；
看持有锁的线程，在干啥；
八成是，锁里藏了慢IO。
```

### 7.3 设计原则

1. **锁内不IO**：任何网络/磁盘操作都不应放在锁内
2. **锁粒度最小化**：只保护真正需要保护的状态变更
3. **无锁优先**：能用CAS、原子类、ThreadLocal解决的，不加锁
4. **预加载**：对可能耗时的资源，提前异步准备好
5. **号段要足够大**：减少DB访问频率，降低锁竞争概率
6. **多版本要统一发号规则**：避免格式混乱 + 竞争放大

### 7.4 不同发号实现的CPU表现对比

| 实现方式 | CPU表现 | 根因 | 适用场景 |
|---------|---------|------|---------|
| **每次直连DB** | CPU不高（20~30%），但线程池耗尽 | 线程IO等待，不耗CPU | 低并发内部工具 |
| **号段 + `synchronized`（号段小）** | **持续飙高（90%+）** | 锁竞争自旋 | ❌ 生产环境禁用 |
| **号段 + `synchronized`（号段大）** | 偶发尖刺（号段切换时） | 切换瞬间线程涌入 | 中小并发 |
| **号段 + CAS + 双Buffer** | CPU正常（<20%） | 无锁快速路径 | ✅ 推荐方案 |
| **雪花算法** | CPU极低（<5%） | 纯内存位运算，无锁无DB | ✅ 高并发首选 |
| **DB自增 + 多版本混用** | **脉冲式尖刺**（突发流量时） | 各版本竞争DB连接池 + 行锁 | ❌ 架构负债 |

> 这个案例在面试中讲出来，能体现你的**实战排查能力**（工具链使用）+ **系统优化思维**（从代码到架构）。建议结合自己的真实项目改编细节（类名、业务场景），让它更自然。
