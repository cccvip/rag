# HashMap 扩容完整流程（面试级详解）

---

## 一、触发条件

HashMap 的扩容由 `resize()` 方法完成，触发时机有两个：

| 时机 | 条件 | 说明 |
|------|------|------|
| **插入时触发** | `size >= threshold` | threshold = capacity * loadFactor，默认 16 * 0.75 = 12 |
| **首次 put 时** | `table == null` | 延迟初始化，第一次插入时才创建数组 |

```java
// HashMap.putVal() 中的触发逻辑
if (++size > threshold)
    resize();
```

---

## 二、扩容核心参数

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `DEFAULT_INITIAL_CAPACITY` | 16 | 默认初始容量（必须是 2 的幂） |
| `MAXIMUM_CAPACITY` | 1 << 30 | 最大容量 |
| `DEFAULT_LOAD_FACTOR` | 0.75f | 默认负载因子 |
| `TREEIFY_THRESHOLD` | 8 | 链表转红黑树的阈值 |
| `UNTREEIFY_THRESHOLD` | 6 | 红黑树转链表的阈值 |

---

## 三、resize() 方法的完整流程（JDK 1.8）

```java
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;
    
    // ========== Step 1: 计算新容量和新阈值 ==========
    if (oldCap > 0) {
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return oldTab;  // 已达最大容量，不再扩容
        }
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                 oldCap >= DEFAULT_INITIAL_CAPACITY)
            newThr = oldThr << 1;  // 容量翻倍，阈值翻倍
    }
    else if (oldThr > 0)
        newCap = oldThr;  // 首次初始化，使用指定的初始容量
    else {
        newCap = DEFAULT_INITIAL_CAPACITY;  // 16
        newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);  // 12
    }
    
    if (newThr == 0) {
        float ft = (float)newCap * loadFactor;
        newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                  (int)ft : Integer.MAX_VALUE);
    }
    threshold = newThr;
    
    // ========== Step 2: 创建新数组 ==========
    @SuppressWarnings({"rawtypes","unchecked"})
    Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
    table = newTab;
    
    // ========== Step 3: 数据迁移（rehash）==========
    if (oldTab != null) {
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e;
            if ((e = oldTab[j]) != null) {
                oldTab[j] = null;  // 帮助GC
                
                if (e.next == null)
                    // Case 1: 只有一个节点，直接 rehash 到新位置
                    newTab[e.hash & (newCap - 1)] = e;
                    
                else if (e instanceof TreeNode)
                    // Case 2: 红黑树节点，拆分树
                    ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                    
                else {
                    // Case 3: 链表节点，拆分成两条链表
                    Node<K,V> loHead = null, loTail = null;  // 低位链表：位置不变
                    Node<K,V> hiHead = null, hiTail = null;  // 高位链表：位置 = 原索引 + oldCap
                    Node<K,V> next;
                    
                    do {
                        next = e.next;
                        
                        // 核心判断：hash & oldCap == 0 说明高位没变化
                        if ((e.hash & oldCap) == 0) {
                            if (loTail == null)
                                loHead = e;
                            else
                                loTail.next = e;  // 尾插法！
                            loTail = e;
                        }
                        else {
                            if (hiTail == null)
                                hiHead = e;
                            else
                                hiTail.next = e;  // 尾插法！
                            hiTail = e;
                        }
                    } while ((e = next) != null);
                    
                    if (loTail != null) {
                        loTail.next = null;
                        newTab[j] = loHead;  // 低位链表放原位置
                    }
                    if (hiTail != null) {
                        hiTail.next = null;
                        newTab[j + oldCap] = hiHead;  // 高位链表放原位置 + oldCap
                    }
                }
            }
        }
    }
    return newTab;
}
```

---

## 四、Rehash 的核心原理：为什么位置要么不变，要么 + oldCap？

### 4.1 关键公式

```
新位置 = hash & (newCap - 1)
       = hash & (2 * oldCap - 1)
       = hash & (oldCap * 2 - 1)
```

因为 `newCap` 是 `oldCap` 的 2 倍，所以 `newCap - 1` 比 `oldCap - 1` 多了一个最高位的 1。

### 4.2 举例说明

```
oldCap = 16,  newCap = 32
oldCap - 1 = 0000 1111  (15)
newCap - 1 = 0001 1111  (31)

hash = 0001 0101  (21)

旧位置：hash & 15 = 0001 0101 & 0000 1111 = 0000 0101 = 5
新位置：hash & 31 = 0001 0101 & 0001 1111 = 0001 0101 = 21

21 = 5 + 16 ✓
```

**判断依据**：
- `hash & oldCap == 0`：新增的高位是 0，位置不变
- `hash & oldCap != 0`：新增的高位是 1，位置 = 原位置 + oldCap

这就是 `e.hash & oldCap == 0` 判断的来源。

---

## 五、JDK 1.7 vs JDK 1.8 扩容的区别

| 维度 | JDK 1.7 | JDK 1.8 |
|------|---------|---------|
| **插入方式** | 头插法（头插到链表头部） | 尾插法（插入到链表尾部） |
| **死循环问题** | 并发扩容时可能形成环，导致死循环 | 尾插法不会形成环，无死循环 |
| **rehash 方式** | 每个节点重新计算 hash & (newCap-1) | 拆分成两条链表（lo/hi），一次遍历完成 |
| **数据结构** | 纯链表 | 链表 + 红黑树（节点数≥8时转树） |
| **树化处理** | 无 | 扩容时红黑树可能拆分成树或链表 |

### 5.1 为什么 JDK 1.8 改用尾插法？

**JDK 1.7 头插法的并发死循环**：

```
线程 T1 和 T2 同时扩容，链表 A → B → C

T1 执行到：e = B, next = C，然后挂起
T2 完成扩容（头插法）：链表变成 C → B → A
T1 恢复：
    - e = B, next = C（但此时 C.next 已经是 B 了！）
    - 处理 B：B.next = A（新表头）
    - e = C（next）
    - 处理 C：C.next = B（新表头）
    - e = B（next = C）
    - 再次处理 B... 形成环 B ↔ C
```

**JDK 1.8 尾插法为什么安全？**

尾插法保持链表顺序不变（A → B → C 迁移后仍是 A → B → C），不会因为并发导致链表反转形成环。但注意：**HashMap 本身仍然不是线程安全的**，1.8 只是避免了死循环，数据覆盖问题依然存在。

---

## 六、链表转红黑树与扩容的关系

```java
// 触发树化的条件（treeifyBin 方法）
if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
    resize();  // 容量小于 64 时，优先扩容而不是转树
else
    // 容量 >= 64 且链表长度 >= 8，才转红黑树
```

**设计意图**：
- 小容量时，扩容比树化更高效（减少哈希冲突）
- 只有当容量 >= 64 且链表长度 >= 8 时，才认为哈希冲突严重到需要树化

---

## 七、初始容量和负载因子的设置

### 7.1 公式

```java
// 已知要放 n 个元素，计算合适的初始容量
capacity = (int) ((expectedSize / loadFactor) + 1);

// 取大于等于 capacity 的最小 2 的幂
static final int tableSizeFor(int cap) {
    int n = cap - 1;
    n |= n >>> 1;
    n |= n >>> 2;
    n |= n >>> 4;
    n |= n >>> 8;
    n |= n >>> 16;
    return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
}
```

### 7.2 实例

```java
// 预计放 1000 个元素
int expectedSize = 1000;
int capacity = (int) (1000 / 0.75f) + 1;  // 1334
int initialCapacity = tableSizeFor(1334);   // 2048

// 如果不设置初始容量，默认 16
// 1000 个元素的插入过程：16 → 32 → 64 → 128 → 256 → 512 → 1024 → 2048
// 会触发 7 次扩容！每次扩容都要 rehash，性能极低
```

### 7.3 负载因子为什么不建议改？

| 负载因子 | 空间利用率 | 哈希冲突概率 | 适用场景 |
|----------|-----------|-------------|----------|
| 0.5 | 低（50%） | 低 | 追求极致查询性能，内存不敏感 |
| **0.75** | 中（75%） | 中 | **默认，时间与空间的平衡** |
| 1.0 | 高（100%） | 高 | 内存极度紧张，查询性能不敏感 |

---

## 八、面试口述版（2 分钟）

> "HashMap 的扩容由 resize() 方法完成，触发条件是 `size > threshold`，也就是 `capacity * loadFactor`，默认 16 * 0.75 = 12。
>
> 扩容流程分三步：第一步计算新容量和阈值，都是翻倍；第二步创建新数组；第三步是核心——数据迁移。
>
> JDK 1.8 的 rehash 优化很巧妙：它不需要对每个节点重新计算 hash，而是判断 `hash & oldCap` 的值。如果为 0，说明 hash 的高位没有变化，节点留在原位置；如果为 1，节点移到 `原位置 + oldCap`。这样一次遍历就能把链表拆成两条，分别放到新位置。
>
> 1.8 改用尾插法，避免了 1.7 头插法在并发扩容时的死循环问题。但 HashMap 仍然不是线程安全的，并发场景要用 ConcurrentHashMap。
>
> 如果数据量很大，建议预设初始容量。比如预计放 1000 个元素，公式是 `expectedSize / loadFactor + 1 ≈ 1334`，取 2 的幂次即 2048。否则默认 16 会触发 7 次扩容，性能很差。"

---

## 九、速记图

```
put(key, value)
    │
    ▼
hash = hash(key)
    │
    ▼
index = hash & (cap - 1)
    │
    ▼
插入到 table[index] 的链表/红黑树
    │
    ▼
size++
    │
    ▼
size > threshold? ──► 否 ──► 结束
    │
    ▼ 是
resize()
    │
    ├──► 计算 newCap = oldCap << 1
    │
    ├──► 计算 newThr = oldThr << 1
    │
    ├──► 创建新数组 newTab[2 * oldCap]
    │
    └──► 遍历旧数组，迁移数据
            │
            ├──► 单个节点：newTab[hash & (newCap-1)] = node
            │
            ├──► 红黑树：split() 拆分成两棵树
            │
            └──► 链表：拆成 lo/hi 两条链表
                    │
                    ├──► hash & oldCap == 0 ──► 放原位置 j
                    │
                    └──► hash & oldCap != 0 ──► 放 j + oldCap
```

---

*整理时间：2026-06-07*  
*适用：Java集合框架、HashMap原理面试*
