# 分布式优雅停机模板（Nacos + Spring Cloud + Docker 双机部署）

> 技术栈：Nacos 注册中心、Spring Cloud、OpenFeign、Docker
> 架构原则：**入口网关层（OpenResty）与业务服务层解耦**，独立部署、独立运维
> 目标：滚动更新时零宕机、零消息丢失、零分布式锁死锁

---

## 一、架构拓扑

> 部署模式：一台微服务部署在两台独立 Docker 宿主机上，OpenResty 作为统一入口网关。

```
┌─────────────────────────────────────────────┐
│          独立 OpenResty 入口网关             │
│   （负载均衡 + 故障转移 + 可选动态发现）     │
└───────────────┬─────────────────────────────┘
                │
        ┌───────┴───────┐
        ▼               ▼
┌───────────────┐ ┌───────────────┐
│  Docker Host 1│ │  Docker Host 2│
│  10.0.1.10    │ │  10.0.1.11    │
│  :8080        │ │  :8080        │
│               │ │               │
│ ┌───────────┐ │ │ ┌───────────┐ │
│ │ App 容器   │ │ │ │ App 容器   │ │
│ │ (实例1)   │ │ │ │ (实例2)   │ │
│ └───────────┘ │ │ └───────────┘ │
│               │ │               │
│  Nacos Client │ │  Nacos Client │
└───────────────┘ └───────────────┘
                │
                ▼
        Nacos 注册中心（独立部署）
```

### 核心原则

1. **OpenResty 作为独立入口网关**：部署在微服务集群外部，通过宿主机 IP + 端口访问后端实例。
2. **服务发现分层**：
   - **东西向（服务间）**：通过 Nacos 注册中心 + Feign 客户端发现。
   - **南北向（外部入口）**：OpenResty 通过**静态 Upstream** 或 **Nacos 动态发现** 路由到两台 Docker 宿主机的实例。
3. **流量摘除双保险**：
   - **第一层**：应用健康检查返回 503 → OpenResty `proxy_next_upstream` 自动将请求重试到另一台健康机器。
   - **第二层**：从 Nacos 注销 → Feign 客户端不再调用该实例。

> **为什么要用 OpenResty 而不是直接暴露服务？**  
> OpenResty 提供统一的流量入口、SSL 终止、负载均衡和故障转移。当某台机器上的实例正在停机时，OpenResty 可以在网关层拦截 503 并快速重试到另一台机器，避免客户端感知中断。

---

## 二、Java 端优雅停机组件

### 2.1 GracefulShutdown.java（核心）

```java
package com.yourcompany.infra.shutdown;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Docker 环境下的分布式优雅停机管理器
 * 
 * 核心流程：
 * 1. 健康检查返回 503，让 OpenResty 感知并自动重试到另一台机器
 * 2. 从 Nacos 注册中心注销（阻止 Feign 客户端发现此实例）
 * 3. 等待 OpenResty 状态同步和 Feign 客户端缓存过期（15秒）
 * 4. Spring Boot graceful shutdown 处理完当前 HTTP 请求
 * 5. 停止内部组件（Kafka、线程池、定时任务）
 * 6. 释放分布式资源（Redis 锁等）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GracefulShutdown implements ApplicationListener<ContextClosedEvent> {

    private final NacosServiceRegistry nacosRegistry;
    private final NacosRegistration nacosRegistration;
    
    private volatile boolean shuttingDown = false;
    private final List<GracefulStoppable> stoppables = new ArrayList<>();
    
    @Autowired(required = false)
    private List<GracefulStoppable> injectedStoppables;
    
    @PostConstruct
    public void init() {
        if (injectedStoppables != null) {
            stoppables.addAll(injectedStoppables);
        }
    }
    
    /**
     * 健康检查端点 - OpenResty / 负载均衡器用此判断实例是否可用
     * 停机时返回 503，OpenResty 会将请求重试到另一台健康机器
     */
    @GetMapping("/actuator/health")
    public ResponseEntity<String> health() {
        if (shuttingDown) {
            log.warn("健康检查返回 503，实例正在停机，OpenResty 将流量导向另一台机器");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("SHUTTING_DOWN");
        }
        return ResponseEntity.ok("UP");
    }
    
    /**
     * 停机端点 - 供运维手动触发优雅停机（可选）
     */
    @GetMapping("/actuator/graceful-shutdown")
    public ResponseEntity<String> triggerShutdown() {
        log.info("收到手动停机请求");
        shuttingDown = true;
        return ResponseEntity.ok("GRACEFUL_SHUTDOWN_TRIGGERED");
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("========== 开始分布式优雅停机 ==========");
        
        try {
            // Step 1: 健康检查返回 503，让 OpenResty 感知并自动重试到另一台机器
            step1_markShuttingDown();
            
            // Step 2: 从 Nacos 注册中心注销（阻止 Feign 客户端发现此实例）
            step2_deregisterFromNacos();
            
            // Step 3: 等待 OpenResty 状态同步和 Feign 客户端缓存过期
            // Feign/Ribbon 默认缓存 30 秒，此处配置已缩短为 5 秒
            step3_waitForTrafficDrain(15000);
            
            // Step 4: 等待 Spring Boot 处理完当前 HTTP 请求
            // 由 server.shutdown=graceful 自动处理，此处等待即可
            step4_waitForHttpRequests(5000);
            
            // Step 5: 按依赖顺序倒序停止内部组件
            step5_stopComponents();
            
            // Step 6: 释放分布式资源
            step6_releaseDistributedResources();
            
        } catch (Exception e) {
            log.error("优雅停机过程中发生异常", e);
        }
        
        log.info("========== 分布式优雅停机完成 ==========");
    }
    
    private void step1_markShuttingDown() {
        log.info("[Step 1/6] 标记停机状态，健康检查将返回 503，OpenResty 将流量导向另一台机器");
        shuttingDown = true;
    }
    
    private void step2_deregisterFromNacos() {
        log.info("[Step 2/6] 从 Nacos 注册中心注销...");
        try {
            nacosRegistry.deregister(nacosRegistration);
            log.info("已从 Nacos 注销实例: {}", nacosRegistration.getServiceId());
        } catch (Exception e) {
            log.error("Nacos 注销失败", e);
        }
    }
    
    private void step3_waitForTrafficDrain(long millis) {
        log.info("[Step 3/6] 等待 {}ms，让 OpenResty 状态同步与 Feign 缓存过期...", millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待被中断");
        }
    }
    
    private void step4_waitForHttpRequests(long millis) {
        log.info("[Step 4/6] 等待 {}ms，让当前 HTTP 请求处理完成...", millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void step5_stopComponents() {
        log.info("[Step 5/6] 停止内部组件（{}个）...", stoppables.size());
        
        // 倒序停止：先停依赖别人的，再停被依赖的
        for (int i = stoppables.size() - 1; i >= 0; i--) {
            GracefulStoppable stoppable = stoppables.get(i);
            try {
                log.info("正在停止组件: {}", stoppable.getName());
                stoppable.stop();
                
                boolean terminated = stoppable.awaitTermination(30, TimeUnit.SECONDS);
                if (!terminated) {
                    log.warn("组件 {} 未在30秒内停止，执行强制停止", stoppable.getName());
                    stoppable.forceStop();
                } else {
                    log.info("组件 {} 已安全停止", stoppable.getName());
                }
            } catch (Exception e) {
                log.error("停止组件 {} 失败", stoppable.getName(), e);
            }
        }
    }
    
    private void step6_releaseDistributedResources() {
        log.info("[Step 6/6] 释放分布式资源...");
        // Redisson 看门狗会自动释放过期的锁
        // 如果有显式持有的锁，在这里释放
    }
    
    public void registerStoppable(GracefulStoppable stoppable) {
        stoppables.add(stoppable);
    }
}
```

### 2.2 GracefulStoppable.java（接口）

```java
package com.yourcompany.infra.shutdown;

import java.util.concurrent.TimeUnit;

/**
 * 需要优雅停机的组件接口
 * 所有内部组件（Kafka消费者、线程池、定时任务等）实现此接口
 */
public interface GracefulStoppable {
    
    /**
     * 组件名称
     */
    String getName();
    
    /**
     * 发起停止请求
     */
    void stop();
    
    /**
     * 等待组件完全停止
     * @return true: 正常停止, false: 超时
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * 强制停止（超时后的兜底）
     */
    void forceStop();
}
```

### 2.3 KafkaConsumerStoppable.java（示例组件）

```java
package com.yourcompany.infra.shutdown;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class KafkaConsumerStoppable implements GracefulStoppable {
    
    private final String name;
    private final KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;
    private final ExecutorService executor;
    
    public KafkaConsumerStoppable(String name, KafkaConsumer<String, String> consumer) {
        this.name = name;
        this.consumer = consumer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kafka-consumer-" + name);
            t.setDaemon(true);
            return t;
        });
        startConsuming();
    }
    
    private void startConsuming() {
        executor.submit(() -> {
            while (running) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    
                    for (ConsumerRecord<String, String> record : records) {
                        if (!running) break;
                        processRecord(record);
                    }
                    
                    if (!records.isEmpty()) {
                        consumer.commitSync();
                    }
                } catch (WakeupException e) {
                    log.info("Kafka consumer {} 被唤醒", name);
                } catch (Exception e) {
                    log.error("消费异常", e);
                }
            }
            
            // 再 poll 一次，处理已拉取但未消费的消息
            try {
                ConsumerRecords<String, String> lastRecords = consumer.poll(Duration.ofSeconds(3));
                for (ConsumerRecord<String, String> record : lastRecords) {
                    processRecord(record);
                }
                consumer.commitSync();
            } catch (Exception e) {
                log.error("最后一批消息处理失败", e);
            }
            
            consumer.close();
            log.info("Kafka consumer {} 已安全关闭", name);
        });
    }
    
    private void processRecord(ConsumerRecord<String, String> record) {
        // 业务处理逻辑
    }
    
    @Override
    public String getName() {
        return "kafka-consumer-" + name;
    }
    
    @Override
    public void stop() {
        log.info("停止 Kafka consumer {}...", name);
        running = false;
        consumer.wakeup();
    }
    
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }
    
    @Override
    public void forceStop() {
        log.warn("强制停止 Kafka consumer {}", name);
        consumer.close();
        executor.shutdownNow();
    }
}
```

### 2.4 ThreadPoolStoppable.java（线程池组件）

```java
package com.yourcompany.infra.shutdown;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ThreadPoolStoppable implements GracefulStoppable {
    
    private final String name;
    private final ExecutorService executor;
    
    public ThreadPoolStoppable(String name, ExecutorService executor) {
        this.name = name;
        this.executor = executor;
    }
    
    @Override
    public String getName() {
        return "thread-pool-" + name;
    }
    
    @Override
    public void stop() {
        log.info("停止线程池 {}...", name);
        executor.shutdown();
    }
    
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }
    
    @Override
    public void forceStop() {
        log.warn("强制停止线程池 {}", name);
        executor.shutdownNow();
    }
}
```

---

## 三、application.yml 配置

```yaml
server:
  port: 8080
  # 【关键】启用 Spring Boot 优雅停机
  shutdown: graceful

spring:
  application:
    name: your-service
  
  lifecycle:
    # 停机时等待组件关闭的最大时间
    timeout-per-shutdown-phase: 45s
  
  cloud:
    nacos:
      discovery:
        server-addr: nacos:8848
        namespace: prod
        group: DEFAULT_GROUP
        # 【关键】心跳间隔短一些，让 Nacos 更快感知下线
        heart-beat-interval: 3000
        heart-beat-timeout: 10000
        # 删除超时实例（服务停止后 Nacos 多久剔除）
        ip-delete-timeout: 30000
    
    # Feign 配置
    openfeign:
      client:
        config:
          default:
            connectTimeout: 5000
            readTimeout: 10000
      # 启用重试，当某个实例下线时可以自动换实例
      retry:
        enabled: true
      # 负载均衡缓存刷新
      loadbalancer:
        cache:
          enabled: true
          ttl: 5  # 缓存5秒，比默认30秒短，更快感知实例变化

# 日志配置
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

---

## 四、Dockerfile

```dockerfile
# 必须使用 exec 格式 ENTRYPOINT，确保 Java 作为 PID 1 能收到 SIGTERM
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 创建非 root 用户（安全最佳实践）
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser

# JVM 参数建议：
# -XX:+UseContainerSupport: 让 JVM 识别容器内存限制
# -Djava.security.egd: 加速启动（使用 /dev/urandom）
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.backgroundpreinitializer.ignore=true"

# 【关键】exec 格式，Java 是 PID 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# 健康检查（Docker / OpenResty 用此判断容器是否健康）
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
```

> ⚠️ **注意**：ENTRYPOINT 必须用 `exec` 格式（JSON 数组），如果用 `ENTRYPOINT java -jar app.jar`（shell 格式），SIGTERM 会被 shell 截断，Java 进程收不到！

---

## 五、Docker 部署脚本（双机部署）

> 部署模式：两台独立宿主机（Host-1: `10.0.1.10`，Host-2: `10.0.1.11`），每台运行一个 Docker 容器。
> OpenResty 作为独立网关，**不包含在微服务部署中**，由运维团队单独部署维护。

### 5.1 单台宿主机部署命令

```bash
# 构建镜像
docker build -t registry.local/your-service:1.2.0 .
docker push registry.local/your-service:1.2.0

# 运行容器（每台机器执行一次）
docker run -d \
  --name your-service \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
  --memory=2g \
  --cpus=2.0 \
  registry.local/your-service:1.2.0
```

### 5.2 可选：docker-compose.yml（单宿主机）

```yaml
version: '3.8'

services:
  app:
    image: registry.local/your-service:${VERSION:-latest}
    container_name: your-service
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
    deploy:
      resources:
        limits:
          cpus: '2.0'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 60s
```

### 5.3 滚动更新策略（双机手动滚动）

由于没有 Swarm 自动编排，滚动更新需要**人工按顺序**执行：

```bash
# 1. 更新 Host-2（让一台先承载全部流量）
ssh host-2 "docker stop -t 60 your-service && docker rm your-service"
ssh host-2 "docker run -d --name your-service ... registry.local/your-service:1.3.0"

# 2. 验证 Host-2 新容器健康
sleep 30
curl http://10.0.1.11:8080/actuator/health

# 3. 更新 Host-1
ssh host-1 "docker stop -t 60 your-service && docker rm your-service"
ssh host-1 "docker run -d --name your-service ... registry.local/your-service:1.3.0"
```

> **为什么先停一台再启新容器？**  
> 因为每台宿主机只运行一个容器，不存在"同台多实例"的情况。先停止旧容器，再启动新容器，期间该机器无服务，OpenResty 会将流量全部导向另一台机器。确保 `timeout-per-shutdown-phase` + `waitForTrafficDrain` 总时间小于你对单台机器无服务的容忍度。

---

## 六、独立 OpenResty 部署与配置

### 6.1 部署原则

- OpenResty 部署在微服务集群外部（独立服务器、独立容器或独立集群）。
- OpenResty 直接通过**宿主机 IP + 端口**访问后端实例。
- 两台 Docker 宿主机即为 OpenResty 的 upstream 后端。

### 6.2 nginx.conf（静态 Upstream + 双机故障转移）

OpenResty 完全兼容 Nginx 配置，以下配置可直接使用：

```nginx
# nginx.conf - OpenResty 独立部署，双机负载均衡 + 故障转移
# 核心原理：OpenResty 直接感知两台 Docker 宿主机，一台停机时自动重试到另一台

events {
    worker_connections 1024;
    use epoll;
    multi_accept on;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for" '
                    'upstream=$upstream_addr response_time=$upstream_response_time';

    access_log /var/log/nginx/access.log main;
    error_log /var/log/nginx/error.log warn;

    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    types_hash_max_size 2048;

    # 上游配置：指向两台 Docker 宿主机
    upstream app_backend {
        least_conn;
        
        # 两台 Docker 宿主机的物理/虚拟机 IP
        server 10.0.1.10:8080 weight=5 max_fails=3 fail_timeout=30s;
        server 10.0.1.11:8080 weight=5 max_fails=3 fail_timeout=30s;
        
        keepalive 64;
    }

    server {
        listen 80;
        server_name api.yourcompany.com;

        # 健康检查端点（OpenResty 自身状态）
        location /openresty-health {
            access_log off;
            return 200 "healthy\n";
            add_header Content-Type text/plain;
        }

        location / {
            proxy_pass http://app_backend;

            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;

            proxy_connect_timeout 5s;
            proxy_send_timeout 10s;
            proxy_read_timeout 10s;

            # 【关键】当后端返回 503（应用正在停机）或连接失败时，自动重试到另一台机器
            # 由于是两台宿主机各自独立部署，没有 Swarm 自动摘除机制，
            # proxy_next_upstream 是停机期间请求不中断的核心保障
            proxy_next_upstream error timeout http_503 http_502 non_idempotent;
            proxy_next_upstream_tries 2;
        }
    }

    # 可选：静态资源缓存层
    server {
        listen 80;
        server_name static.yourcompany.com;

        location / {
            root /var/www/static;
            expires 30d;
            add_header Cache-Control "public, immutable";
        }
    }
}
```

> **为什么不需要 `resolver` 和变量？**  
> 因为 OpenResty 直接连接的是**固定的 Docker 宿主机 IP**（`10.0.1.10` 和 `10.0.1.11`），不是动态变化的容器 IP。只要宿主机 IP 不变，静态 upstream 即可稳定工作。

### 6.3 进阶：OpenResty + Lua 对接 Nacos 实现动态 Upstream

OpenResty 的核心优势在于可通过 Lua 脚本实现动态服务发现。以下示例展示如何用 Lua 定时从 Nacos 拉取实例列表，动态构建 upstream：

#### 6.3.1 nacos_discovery.lua

```lua
-- /usr/local/openresty/lualib/nacos_discovery.lua
-- Nacos 服务发现模块：定时拉取实例列表，更新共享字典

local _M = {}
local http = require "resty.http"
local cjson = require "cjson"
local timer = require "ngx.timer"

-- Nacos 配置
local NACOS_HOST = os.getenv("NACOS_HOST") or "nacos.yourcompany.com"
local NACOS_PORT = os.getenv("NACOS_PORT") or "8848"
local NAMESPACE_ID = os.getenv("NACOS_NAMESPACE") or "prod"
local GROUP_NAME = os.getenv("NACOS_GROUP") or "DEFAULT_GROUP"
local SERVICE_NAME = os.getenv("NACOS_SERVICE") or "your-service"

-- 共享字典（需在 nginx.conf 中定义：lua_shared_dict nacos_upstreams 10m）
local shared_dict = ngx.shared.nacos_upstreams

-- 拉取 Nacos 实例列表
local function fetch_instances()
    local httpc = http.new()
    httpc:set_timeout(5000)

    local url = string.format(
        "http://%s:%s/nacos/v1/ns/instance/list?serviceName=%s&groupName=%s&namespaceId=%s&healthyOnly=true",
        NACOS_HOST, NACOS_PORT, SERVICE_NAME, GROUP_NAME, NAMESPACE_ID
    )

    local res, err = httpc:request_uri(url, { method = "GET" })
    if not res then
        ngx.log(ngx.ERR, "failed to fetch from Nacos: ", err)
        return nil
    end

    if res.status ~= 200 then
        ngx.log(ngx.ERR, "Nacos returned status: ", res.status)
        return nil
    end

    local data = cjson.decode(res.body)
    if not data or not data.hosts then
        ngx.log(ngx.WARN, "no hosts found in Nacos response")
        return nil
    end

    local upstreams = {}
    for _, host in ipairs(data.hosts) do
        if host.healthy then
            table.insert(upstreams, host.ip .. ":" .. host.port)
        end
    end

    return upstreams
end

-- 更新共享字典
local function update_upstreams()
    local upstreams = fetch_instances()
    if not upstreams or #upstreams == 0 then
        ngx.log(ngx.WARN, "no healthy upstreams found, keeping existing")
        return
    end

    local upstream_str = table.concat(upstreams, ",")
    shared_dict:set("upstreams", upstream_str)
    shared_dict:set("last_update", ngx.time())
    ngx.log(ngx.INFO, "updated upstreams: ", upstream_str)
end

-- 定时任务入口
function _M.start_background_update(premature)
    if premature then
        return
    end

    update_upstreams()

    -- 每 5 秒刷新一次
    local ok, err = timer.every(5, _M.start_background_update)
    if not ok then
        ngx.log(ngx.ERR, "failed to create timer: ", err)
    end
end

-- 获取当前可用 upstream 列表
function _M.get_upstreams()
    local upstream_str = shared_dict:get("upstreams")
    if not upstream_str then
        return nil
    end

    local upstreams = {}
    for addr in string.gmatch(upstream_str, "([^,]+)") do
        table.insert(upstreams, addr)
    end
    return upstreams
end

return _M
```

#### 6.3.2 动态 Upstream 的 nginx.conf

```nginx
# nginx.conf - OpenResty 动态服务发现版
# 核心原理：Lua 定时从 Nacos 拉取实例，balancer_by_lua 动态选择后端

user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
    use epoll;
    multi_accept on;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    # Lua 共享字典（存储 Nacos 拉取的实例列表）
    lua_shared_dict nacos_upstreams 10m;
    lua_shared_dict healthcheck 1m;

    # Lua 包路径
    lua_package_path "/usr/local/openresty/lualib/?.lua;;";

    # init_worker 阶段启动 Nacos 定时拉取
    init_worker_by_lua_block {
        local nacos = require "nacos_discovery"
        -- 立即执行一次，然后每 5 秒刷新
        nacos.start_background_update()
    }

    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for" '
                    'upstream=$upstream_addr response_time=$upstream_response_time';

    access_log /var/log/nginx/access.log main;

    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;

    # 动态 upstream：不硬编码 server 列表，由 Lua 在请求时选择
    upstream nacos_backend {
        server 0.0.0.1;   # 占位，不会被使用
        balancer_by_lua_block {
            local balancer = require "ngx.balancer"
            local nacos = require "nacos_discovery"

            local upstreams = nacos.get_upstreams()
            if not upstreams or #upstreams == 0 then
                ngx.log(ngx.ERR, "no upstreams available")
                return ngx.exit(ngx.HTTP_SERVICE_UNAVAILABLE)
            end

            -- 简单的轮询（也可用 consistent_hash 等策略）
            local upstream_dict = ngx.shared.healthcheck
            local index = upstream_dict:incr("round_robin_index", 1, 0)
            local target = upstreams[(index - 1) % #upstreams + 1]

            local ok, err = balancer.set_current_peer(target)
            if not ok then
                ngx.log(ngx.ERR, "failed to set peer: ", err)
                return ngx.exit(ngx.HTTP_SERVICE_UNAVAILABLE)
            end
        }

        keepalive 64;
    }

    server {
        listen 80;
        server_name api.yourcompany.com;

        # 健康检查端点
        location /openresty-health {
            access_log off;
            return 200 "healthy\n";
            add_header Content-Type text/plain;
        }

        # Nacos 实例状态查看（调试用）
        location /upstreams {
            access_log off;
            content_by_lua_block {
                local nacos = require "nacos_discovery"
                local cjson = require "cjson"
                local upstreams = nacos.get_upstreams()
                ngx.header["Content-Type"] = "application/json"
                ngx.say(cjson.encode(upstreams or {}))
            }
        }

        location / {
            # 使用动态 upstream
            proxy_pass http://nacos_backend;

            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;

            proxy_connect_timeout 5s;
            proxy_send_timeout 10s;
            proxy_read_timeout 10s;

            proxy_next_upstream error timeout http_503 http_502 non_idempotent;
            proxy_next_upstream_tries 2;
        }
    }
}
```

#### 6.3.3 Dockerfile（OpenResty）

```dockerfile
FROM openresty/openresty:1.25.3.1-0-alpine

# 安装 Lua 依赖（若镜像未内置 lua-resty-http）
RUN apk add --no-cache curl \
    && opm get ledgetech/lua-resty-http

# 拷贝配置和 Lua 脚本
COPY nginx.conf /usr/local/openresty/nginx/conf/nginx.conf
COPY nacos_discovery.lua /usr/local/openresty/lualib/

EXPOSE 80

CMD ["/usr/local/openresty/bin/openresty", "-g", "daemon off;"]
```

> **方案对比**：
> - **静态 Upstream（6.2）**：简单稳定，OpenResty 仅作为高性能网关，静态配置两台宿主机 IP。推荐大多数场景使用。
> - **动态 Upstream（6.3）**：OpenResty 直接从 Nacos 获取实例，可在不重启 OpenResty 的情况下感知实例上下线，适合做更细粒度的流量控制（如金丝雀发布、灰度路由）。适合对网关灵活性要求高的场景。
> - **注意**：若采用动态直连容器 IP 的方案，必须确保 OpenResty 到容器网络的连通性（宿主机端口映射或容器网络互通）。

---

## 七、Nacos 部署说明（独立部署）

Nacos 作为注册中心，应与微服务集群独立部署（或至少暴露端口供外部访问）：

```yaml
# nacos-docker-compose.yml（独立部署示例）
version: '3.8'
services:
  nacos:
    image: nacos/nacos-server:v2.2.3
    ports:
      - "8848:8848"
      - "9848:9848"
    environment:
      - MODE=standalone
      - PREFER_HOST_MODE=hostname
      - SPRING_DATASOURCE_PLATFORM=mysql
      - MYSQL_SERVICE_HOST=mysql
      - MYSQL_SERVICE_DB_NAME=nacos
      - MYSQL_SERVICE_PORT=3306
      - MYSQL_SERVICE_USER=nacos
      - MYSQL_SERVICE_PASSWORD=nacos
```

微服务中的 Nacos 地址应配置为**可外部访问的地址**：
```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: nacos.yourcompany.com:8848  # 独立部署的 Nacos 地址
```

---

## 八、部署命令

```bash
# 1. 构建镜像
docker build -t registry.local/your-service:1.2.0 .
docker push registry.local/your-service:1.2.0

# 2. 在 Host-1 上部署
docker stop -t 60 your-service || true
docker rm your-service || true
docker run -d \
  --name your-service \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  registry.local/your-service:1.2.0

# 3. 在 Host-2 上部署（同上命令）
# ...

# 4. 查看容器日志
docker logs -f your-service

# 5. OpenResty 配置更新后 reload
/usr/local/openresty/bin/openresty -s reload
```

> **OpenResty 部署**：OpenResty 由运维团队独立部署，配置好 upstream 指向两台 Docker 宿主机后 reload 即可。OpenResty 的变更频率远低于应用服务，无需跟随应用一起更新。

---

## 九、验证优雅停机是否生效

```bash
# 1. 在 Host-1 上找到容器 ID
CONTAINER_ID=$(docker ps -q -f name=your-service)

# 2. 从另一台机器持续请求（观察停机期间是否丢请求）
while true; do curl -s http://10.0.1.10:8080/actuator/health; sleep 1; done

# 3. 优雅停止（发送 SIGTERM）
docker stop -t 60 $CONTAINER_ID

# 4. 查看日志（应该在 60 秒内完成停机，而不是被 kill -9）
docker logs $CONTAINER_ID

# 预期日志输出：
# [Step 1/6] 标记停机状态，健康检查将返回 503...
# [Step 2/6] 从 Nacos 注册中心注销...
# [Step 3/6] 等待 15000ms，让 OpenResty 状态同步与 Feign 缓存过期...
# [Step 4/6] 等待当前 HTTP 请求处理完成...
# [Step 5/6] 停止内部组件...
# [Step 6/6] 释放分布式资源...
# ========== 分布式优雅停机完成 ==========
```

---

## 十、关键配置速查表

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `server.shutdown` | `graceful` | Spring Boot 优雅停机 |
| `timeout-per-shutdown-phase` | `45s` | 组件关闭超时 |
| Docker `stop -t` | `60s` | Docker 发送 SIGKILL 前的等待 |
| `healthcheck.start_period` | `60s` | 启动宽限期 |
| Nacos `heart-beat-timeout` | `10s` | Nacos 判定实例死亡时间 |
| Feign `ttl` | `5s` | 负载均衡缓存刷新 |
| OpenResty `proxy_next_upstream` | `error timeout http_502 http_503` | 后端故障时自动重试 |

---

## 十一、常见问题排查

### Q1: 容器被 SIGKILL（exit code 137）
**原因**：`docker stop -t 60` 的 60 秒内没完成停机  
**解决**：增大 `docker stop -t` 的时间，或检查组件停止逻辑是否阻塞

### Q2: 停机后 Feign 还调用旧实例
**原因**：Feign/Ribbon 客户端缓存未过期  
**解决**：缩短 `spring.cloud.loadbalancer.cache.ttl`，或增加 `step3_waitForTrafficDrain` 时间

### Q3: Nacos 注销了但 OpenResty 侧还有流量
**原因**：OpenResty 直接连接 Docker 宿主机，不经过 Nacos。如果 OpenResty 还没感知到实例下线，流量仍可能进入正在停机的机器。  
**解决**：
1. 确认 `step3_waitForTrafficDrain` 的等待时间足够长（建议 15s 以上），让 OpenResty 的 `fail_timeout` 和 Feign 缓存过期。
2. 确认 `docker stop -t` 的时间大于 `timeout-per-shutdown-phase + drain_wait_time`，确保容器有足够时间处理完已有请求。
3. OpenResty 侧配置 `proxy_next_upstream http_503` 作为兜底重试，确保即使请求打到正在停机的机器，也会自动转投另一台。

### Q4: OpenResty 无法连接 Docker 宿主机端口
**原因**：Docker 宿主机防火墙未开放端口，或容器未正常启动  
**解决**：
1. 确认容器已启动且端口映射正确（`docker ps` 查看）。
2. 确认宿主机防火墙放行 8080 端口。
3. 从 OpenResty 机器直接 `curl http://<宿主机IP>:8080/actuator/health` 测试连通性。

### Q5: 以后扩容到三台机器怎么办？
**原因**：目前是两台宿主机静态配置，新增机器需要修改 OpenResty upstream。  
**解决**：
1. **简单方案**：修改 `nginx.conf` 新增 `server` 行，执行 `openresty -s reload`（零中断重载配置）。
2. **进阶方案（推荐）**：使用 6.3 节的 Lua 动态服务发现方案，OpenResty 直接从 Nacos 获取可用实例，扩容时只需在新机器启动容器并注册到 Nacos，OpenResty 自动感知，无需改配置。

### Q6: 两台机器都宕机了怎么办？
**原因**：目前架构只有两台机器，没有更多冗余。  
**解决**：
1. **短期**：OpenResty 配置中保留一台 `backup` 机器，平时不承载流量，主机器故障时自动接管。
2. **长期**：如果业务要求更高可用性，应扩容到 3+ 台机器，或引入云厂商负载均衡 + 自动伸缩组。

---

> 将此模板中的 `com.yourcompany` 替换为你的包名，`your-service` 替换为你的服务名，Docker 宿主机 IP（`10.0.1.10`、`10.0.1.11`）替换为实际地址，即可直接使用。
