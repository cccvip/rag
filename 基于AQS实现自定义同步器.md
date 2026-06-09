# 基于 AQS 实现自定义同步器（面试级详解）

> **核心结论**：AQS 的模板方法模式让自定义同步器变得简单——你只需要重写 `tryAcquire/tryRelease`（独占）或 `tryAcquireShared/tryReleaseShared`（共享），AQS 帮你管排队、阻塞、唤醒。

---

## 一、什么时候需要自定义同步器？

| 场景 | JDK 已有工具 | 是否需要自定义 |
|------|-------------|--------------|
| 互斥锁 | ReentrantLock | ❌ 直接用 |
| 信号量 | Semaphore | ❌ 直接用 |
| 倒计时门闩 | CountDownLatch | ❌ 直接用 |
| **带超时优先级的资源池** | 无 | ✅ 自定义 |
| **按用户维度的限流** | 无 | ✅ 自定义 |
| **一次性开关（不可重置）** | 无 | ✅ 自定义 |

---

## 二、自定义同步器的两种模式

| 模式 | 重写方法 | 代表类 | 场景 |
|------|----------|--------|------|
| **独占模式** | `tryAcquire(int arg)`<br>`tryRelease(int arg)` | ReentrantLock | 一次只允许一个线程获取 |
| **共享模式** | `tryAcquireShared(int arg)`<br>`tryReleaseShared(int arg)` | Semaphore | 允许多个线程同时获取 |

---

## 三、实战示例 1：并发限流器（共享模式）

**需求**：限制同时最多 N 个线程执行业务，超出线程阻塞等待。

```java
/**
 * 基于 AQS 的并发限流器
 * 类似 Semaphore，但支持超时获取和优先队列
 */
public class FlowLimiter {
    
    private final Sync sync;
    
    public FlowLimiter(int permits) {
        this.sync = new Sync(permits);
    }
    
    /**
     * 获取一个许可证，如果没有就阻塞
     */
    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }
    
    /**
     * 获取一个许可证，带超时
     */
    public boolean acquire(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }
    
    /**
     * 释放一个许可证
     */
    public void release() {
        sync.releaseShared(1);
    }
    
    /**
     * 当前剩余许可证数
     */
    public int availablePermits() {
        return sync.getPermits();
    }
    
    /**
     * AQS 核心实现
     */
    private static class Sync extends AbstractQueuedSynchronizer {
        
        Sync(int permits) {
            setState(permits);  // state = 剩余许可证数
        }
        
        int getPermits() {
            return getState();
        }
        
        /**
         * 共享模式获取：判断剩余许可证是否够用
         * 
         * @return >= 0: 获取成功，返回值表示剩余共享资源数
         *         < 0: 获取失败，进入队列阻塞
         */
        @Override
        protected int tryAcquireShared(int acquires) {
            for (;;) {
                int available = getState();
                int remaining = available - acquires;
                
                // remaining < 0: 许可证不够，获取失败
                // remaining >= 0: 许可证够，CAS 扣减
                if (remaining < 0 || compareAndSetState(available, remaining)) {
                    return remaining;
                }
                // CAS 失败，说明被其他线程修改了，自旋重试
            }
        }
        
        /**
         * 共享模式释放：归还许可证
         */
        @Override
        protected boolean tryReleaseShared(int releases) {
            for (;;) {
                int current = getState();
                int next = current + releases;
                
                if (next < current)  // 溢出检查
                    throw new Error("Maximum permit count exceeded");
                    
                if (compareAndSetState(current, next)) {
                    return true;  // 返回 true 表示释放成功，AQS 会唤醒等待线程
                }
                // CAS 失败，自旋重试
            }
        }
    }
}
```

### 使用方式

```java
public class OrderService {
    // 最多允许 100 个并发
    private final FlowLimiter limiter = new FlowLimiter(100);
    
    public void createOrder(OrderDTO order) {
        try {
            // 获取许可证，如果没有就阻塞
            limiter.acquire();
            
            // 执行业务
            doCreateOrder(order);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("请求被中断");
        } finally {
            // 一定要释放！
            limiter.release();
        }
    }
}
```

---

## 四、实战示例 2：一次性开关（共享模式）

**需求**：某个初始化操作完成后，所有等待的线程才能继续执行。且**不可重置**（比 CountDownLatch 更严格）。

```java
/**
 * 一次性开关
 * 类似于 CountDownLatch(1)，但不可重置，且支持查询状态
 */
public class OneShotLatch {
    
    private final Sync sync = new Sync();
    
    /**
     * 触发开关（只能触发一次）
     */
    public void signal() {
        sync.releaseShared(0);
    }
    
    /**
     * 等待开关触发
     */
    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(0);
    }
    
    /**
     * 是否已触发
     */
    public boolean isSignaled() {
        return sync.isSignaled();
    }
    
    private static class Sync extends AbstractQueuedSynchronizer {
        
        /**
         * state = 0: 未触发
         * state = 1: 已触发
         */
        
        @Override
        protected int tryAcquireShared(int ignored) {
            // 如果 state == 1，获取成功（返回值 >= 0）
            // 如果 state == 0，获取失败（返回值 < 0），线程阻塞
            return getState() == 1 ? 1 : -1;
        }
        
        @Override
        protected boolean tryReleaseShared(int ignored) {
            // CAS 把 state 从 0 改成 1
            // 如果已经是 1，返回 false（不可重置）
            return compareAndSetState(0, 1);
        }
        
        boolean isSignaled() {
            return getState() == 1;
        }
    }
}
```

### 使用方式

```java
public class ConfigLoader {
    private final OneShotLatch latch = new OneShotLatch();
    
    // 配置加载线程
    public void loadConfig() {
        // 耗时加载配置...
        loadFromRemote();
        
        // 配置加载完成，通知所有等待线程
        latch.signal();
    }
    
    // 业务线程
    public void handleRequest() {
        // 等待配置加载完成
        latch.await();
        
        // 使用配置处理请求
        processWithConfig();
    }
}
```

---

## 五、实战示例 3：独占锁（不可重入）

**需求**：实现一个最简单的互斥锁，不支持重入（类似早期的 synchronized）。

```java
/**
 * 不可重入的独占锁
 * 类似 Lock 接口，但不可重入（同一个线程获取两次会死锁）
 */
public class SimpleMutex implements Lock {
    
    private final Sync sync = new Sync();
    
    @Override
    public void lock() {
        sync.acquire(1);
    }
    
    @Override
    public void unlock() {
        sync.release(1);
    }
    
    @Override
    public boolean tryLock() {
        return sync.tryAcquire(1);
    }
    
    @Override
    public Condition newCondition() {
        return sync.newCondition();
    }
    
    @Override
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }
    
    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }
    
    private static class Sync extends AbstractQueuedSynchronizer {
        
        /**
         * 独占获取：state = 0 表示空闲，state = 1 表示占用
         */
        @Override
        protected boolean tryAcquire(int acquires) {
            if (compareAndSetState(0, 1)) {
                // 设置当前线程为独占线程（用于判断是否是持有线程）
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
        
        /**
         * 独占释放
         */
        @Override
        protected boolean tryRelease(int releases) {
            if (getState() == 0)
                throw new IllegalMonitorStateException();
            setExclusiveOwnerThread(null);
            setState(0);  // volatile 写，保证可见性
            return true;
        }
        
        /**
         * 是否被当前线程持有
         */
        @Override
        protected boolean isHeldExclusively() {
            return getState() == 1 && getExclusiveOwnerThread() == Thread.currentThread();
        }
        
        Condition newCondition() {
            return new ConditionObject();  // AQS 内置的条件队列
        }
    }
}
```

---

## 六、AQS 的模板方法速查表

| 方法 | 作用 | 是否需要重写 |
|------|------|-------------|
| `tryAcquire(int)` | 独占获取 | ✅ 必须重写 |
| `tryRelease(int)` | 独占释放 | ✅ 必须重写 |
| `tryAcquireShared(int)` | 共享获取 | ✅ 必须重写 |
| `tryReleaseShared(int)` | 共享释放 | ✅ 必须重写 |
| `isHeldExclusively()` | 是否被当前线程独占 | ✅ 可选（用于 Condition） |
| `acquire(int)` | 获取锁（阻塞，不响应中断） | ❌ AQS 已实现 |
| `acquireInterruptibly(int)` | 获取锁（响应中断） | ❌ AQS 已实现 |
| `tryAcquireNanos(int, long)` | 获取锁（带超时） | ❌ AQS 已实现 |
| `release(int)` | 释放锁 | ❌ AQS 已实现 |
| `hasQueuedThreads()` | 是否有线程在等待 | ❌ AQS 已实现 |
| `getQueueLength()` | 等待队列长度 | ❌ AQS 已实现 |

---

## 七、实际项目中的应用场景

### 场景 1：接口按用户限流

```java
/**
 * 每个用户独立的限流器
 * 基于 AQS + ConcurrentHashMap 实现
 */
public class UserRateLimiter {
    
    private final ConcurrentHashMap<String, FlowLimiter> limiters = new ConcurrentHashMap<>();
    private final int maxPermitsPerUser;
    
    public boolean tryAcquire(String userId) {
        FlowLimiter limiter = limiters.computeIfAbsent(
            userId, 
            k -> new FlowLimiter(maxPermitsPerUser)
        );
        return limiter.acquire(100, TimeUnit.MILLISECONDS);
    }
}
```

### 场景 2：数据库连接池的简单实现

```java
/**
 * 简易连接池：最大连接数 = 10
 * 获取连接 = acquire，归还连接 = release
 */
public class SimpleConnectionPool {
    
    private final FlowLimiter connectionLimiter = new FlowLimiter(10);
    private final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
    
    public Connection borrowConnection(long timeout, TimeUnit unit) throws Exception {
        if (!connectionLimiter.acquire(timeout, unit)) {
            throw new RuntimeException("获取连接超时");
        }
        return pool.take();
    }
    
    public void returnConnection(Connection conn) {
        pool.offer(conn);
        connectionLimiter.release();
    }
}
```

### 场景 3：微服务启动依赖管理

```java
/**
 * 服务启动顺序控制
 * 服务 B 依赖服务 A 初始化完成
 */
public class ServiceStarter {
    
    private final Map<String, OneShotLatch> dependencies = new ConcurrentHashMap<>();
    
    public void registerDependency(String serviceName) {
        dependencies.put(serviceName, new OneShotLatch());
    }
    
    public void markReady(String serviceName) {
        dependencies.get(serviceName).signal();
    }
    
    public void waitFor(String serviceName) throws InterruptedException {
        dependencies.get(serviceName).await();
    }
}
```

---

## 八、面试 2 分钟口述版

> "我在项目中基于 AQS 实现过一个**并发限流器**，用来控制接口的最大并发数。
>
> 核心思路是继承 AbstractQueuedSynchronizer，重写 `tryAcquireShared` 和 `tryReleaseShared`。用 AQS 的 `state` 表示剩余许可证数，初始值设为最大并发数比如 100。`tryAcquireShared` 里用 CAS 把 state 减 1，如果减完还大于等于 0 就获取成功；如果小于 0 就返回负数，AQS 会自动把线程放进 FIFO 队列阻塞。`tryReleaseShared` 里用 CAS 把 state 加 1，加成功后 AQS 会唤醒队列里的等待线程。
>
> 这个限流器比 Semaphore 更轻量，因为我们还加了超时获取的功能，防止线程无限阻塞。实际用在订单接口上，把并发控制在数据库连接池的最大连接数以内，避免连接池耗尽。"

---

## 九、AQS 自定义同步器速记图

```
自定义同步器
    │
    ├── 继承 AbstractQueuedSynchronizer
    │
    ├── 定义 state 含义
    │       ├── 独占锁: 0=空闲, 1=占用
    │       ├── 信号量: state=剩余许可证数
    │       └── 开关: 0=关闭, 1=打开
    │
    ├── 重写获取方法
    │       ├── 独占: tryAcquire() → CAS 抢 state
    │       └── 共享: tryAcquireShared() → CAS 减 state, 返回剩余数
    │
    ├── 重写释放方法
    │       ├── 独占: tryRelease() → 改 state + 唤醒后继
    │       └── 共享: tryReleaseShared() → 改 state + 级联唤醒
    │
    └── 封装对外 API
            ├── acquire() / release()
            ├── acquire(timeout) / release()
            └── 自定义业务方法
```

---

*整理时间：2026-06-07*  
*适用：Java并发、AQS、自定义同步器面试*
