# MCP Gateway 第一阶段：Session 创建与会话管理中的 Reactor 解析

## 一、前言

在 MCP Gateway 项目的第一阶段中，核心任务是完成 **Session 的创建以及会话管理**。  
为了让服务端能够主动向客户端推送消息（SSE 模式），代码中引入了 **Project Reactor** 的 `Sinks.Many` 作为每个会话的消息通道。

本文聚焦解释以下两段 Reactor 代码：

```java
// SessionManagementService#createSession
Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

sink.tryEmitNext(ServerSentEvent.<String>builder()
        .event("endpoint")
        .data(messageEndpoint)
        .build());
```

```java
// SessionConfigVO
private Sinks.Many<ServerSentEvent<String>> sink;
```

---

## 二、项目背景：为什么这里需要 Reactor？

MCP（Model Context Protocol）的传输层可以基于 HTTP SSE（Server-Sent Events）实现：

- 客户端先发起一个长连接请求（如 `GET /{gatewayId}/mcp/sse`）。
- 服务端创建一个 Session，并返回一个 SSE 流。
- 服务端通过该流实时向客户端推送事件，比如 `endpoint` 事件告诉客户端后续发消息的请求地址。
- 客户端再通过另一个端点 `POST` 消息，服务端处理后再通过 SSE 流返回结果。

这种"服务端主动推"的模型，天然适合 **Reactive Streams**。

---

## 三、Reactor 核心概念速览

| 概念 | 含义 |
|------|------|
| `Flux<T>` | 0..N 个元素的异步序列 |
| `Mono<T>` | 0 或 1 个元素的异步序列 |
| `Sinks` | Reactor 提供的**生产者侧 API**，允许手动向流中推送数据 |
| `Sinks.Many<T>` | 可以推送多条数据的 Sink |
| `Sinks.One<T>` | 只能推送一条数据（类似 Mono）的 Sink |
| `ServerSentEvent<T>` | Spring WebFlux 对 SSE 事件的封装 |

---

## 四、代码逐行解析

### 4.1 创建 Sink

```java
Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
```

#### 拆解

- **`Sinks.many()`**：声明要创建一个可以发送**多条消息**的 Sink。
- **`.multicast()`**：创建**多播/热流（Hot Publisher）**。  
  多个订阅者共享同一个数据流，而不是每个订阅者独立从头消费。
- **`.onBackpressureBuffer()`**：开启**背压缓冲**。  
  当消费者处理速度跟不上，或者暂时没有消费者时，数据先进入缓冲区，避免丢失。

#### 一句话总结

> 为当前 Session 创建了一个**支持多播和背压缓冲的服务端消息发布通道**。

---

### 4.2 发送 endpoint 事件

```java
String messageEndpoint = "/" + gatewayId + "/mcp/message?sessionId=" + sessionId;
sink.tryEmitNext(ServerSentEvent.<String>builder()
        .event("endpoint")
        .data(messageEndpoint)
        .build());
```

#### `ServerSentEvent.builder()`

`ServerSentEvent` 是 Spring WebFlux 对 SSE 规范事件的封装。最终输出到 HTTP 响应中形如：

```text
event: endpoint
data: /gateway_xxx/mcp/message?sessionId=xxxxx

```

| 字段 | 说明 |
|------|------|
| `event` | SSE 事件名称，客户端通过 `EventSource.addEventListener("endpoint", ...)` 监听 |
| `data` | 事件携带的数据，此处为客户端后续应请求的 message 地址 |
| `id` / `retry` / `comment` | 其他可选 SSE 字段 |

#### `tryEmitNext`

```java
sink.tryEmitNext(event);
```

- **同步尝试**向 Sink 中推送一条消息。
- 返回 `Sinks.EmitResult`，表示成功或失败原因。
- 与 `emitNext` 不同，`tryEmitNext` 不会自动重试，失败立即返回。

`EmitResult` 常见取值：

| 结果 | 含义 |
|------|------|
| `OK` | 推送成功 |
| `FAIL_NON_SERIALIZED` | 并发调用，未串行化 |
| `FAIL_OVERFLOW` | 缓冲区溢出 |
| `FAIL_CANCELLED` | Sink 已被取消 |
| `FAIL_TERMINATED` | Sink 已结束 |

> 当前代码未处理 `tryEmitNext` 的返回值，后续生产环境建议补充异常处理。

---

## 五、Sink 在会话管理中的角色

```java
public class SessionConfigVO {
    private String sessionId;
    private Sinks.Many<ServerSentEvent<String>> sink;
    private Instant createTime;
    private volatile Instant lastAccessedTime;
    private volatile boolean active;
}
```

`SessionConfigVO` 持有 `sink`，相当于把每个 Session 的**消息出口**保存了下来。

后续流程：

```text
1. 客户端 GET /{gatewayId}/mcp/sse
2. SessionManagementService.createSession(gatewayId)
3. 生成 sessionId，创建 Sinks.Many
4. 立即推送 event=endpoint 事件
5. SessionConfigVO 存入 activeSessions
6. Controller 返回 sink.asFlux()，客户端建立 SSE 长连接
7. 客户端通过 endpoint 地址 POST 消息
8. 服务端处理完后，通过同一个 sink 推送结果给客户端
```

---

## 六、Sink 如何交给 HTTP 层？

`Sinks.Many` 可以转成 `Flux`：

```java
sessionConfigVO.getSink().asFlux();
```

在 WebFlux Controller 中通常这样使用：

```java
@GetMapping(value = "/{gatewayId}/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> sse(@PathVariable String gatewayId) {
    SessionConfigVO session = sessionManagementService.createSession(gatewayId);
    return session.getSink().asFlux();
}
```

返回 `Flux<ServerSentEvent<String>>` 后，Spring WebFlux 会自动将其序列化为 SSE 流。

---

## 七、需要注意的坑

### 7.1 endpoint 事件是否会在客户端订阅前丢失？

当前代码在创建 Sink 后**立即**发送 `endpoint` 事件，此时 HTTP 客户端可能还没有订阅：

```java
Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
sink.tryEmitNext(...); // 可能暂无订阅者
```

由于使用的是 `multicast().onBackpressureBuffer()`，消息会先进入缓冲区，等待第一个订阅者连上后再消费，因此正常情况下不会丢失。

但如果将来换成不带缓冲的多播 Sink，这类"先发后订阅"的消息就会丢失。

---

### 7.2 `tryEmitNext` 的并发安全

`Sinks` 的 `tryEmitXxx` 方法要求调用者保证**串行调用**。如果多个线程同时往同一个 Session 的 Sink 推消息，可能得到 `FAIL_NON_SERIALIZED`。

后续如果存在并发推送，可以使用：

```java
sink.emitNext(event, Sinks.EmitFailureHandler.FAIL_FAST);
```

或：

```java
sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
```

也可以在外层通过队列/单线程调度器保证串行化。

---

### 7.3 当前代码未处理 `tryEmitNext` 返回值

建议改进为：

```java
Sinks.EmitResult result = sink.tryEmitNext(ServerSentEvent.<String>builder()
        .event("endpoint")
        .data(messageEndpoint)
        .build());

if (result.isFailure()) {
    log.warn("发送 endpoint 事件失败, sessionId={}, result={}", sessionId, result);
}
```

---

### 7.4 技术栈选择：Spring MVC vs Spring WebFlux

当前 `app` 和 `trigger` 模块依赖的是：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

这是 Spring MVC（Servlet 阻塞模型），而 `ServerSentEvent` 来自 Spring WebFlux。

如果想在 Controller 中直接返回 `Flux<ServerSentEvent<String>>`，建议将依赖切换为：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

WebFlux 与 Reactor + SSE 的配合更自然，也更符合 MCP Gateway 的实时推送场景。

---

## 八、一句话总结

> 用 `Sinks.Many<ServerSentEvent<String>>` 为每个 Session 创建一个**服务端可控、支持背压缓冲、可多播的消息流**，是实现 MCP SSE 长连接推送的核心设计。

需要掌握的关键点：

- `Sinks.Many` = 手动推消息的发布器
- `multicast().onBackpressureBuffer()` = 热流 + 缓冲
- `tryEmitNext` = 同步尝试推消息，需要处理返回值
- `sink.asFlux()` = 交给 HTTP 层返回 SSE 流
