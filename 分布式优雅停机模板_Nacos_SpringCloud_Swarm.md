# 分布式优雅停机模板（Nacos + Spring Cloud + Docker Swarm）

> 技术栈：Nacos 注册中心、Spring Cloud、OpenFeign、Docker Swarm
> 目标：滚动更新时零宕机、零消息丢失、零分布式锁死锁

---

## 一、Java 端优雅停机组件

### 1.1 SwarmGracefulShutdown.java（核心）

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
 * Docker Swarm 环境下的分布式优雅停机管理器
 * 
 * 核心流程：
 * 1. 健康检查返回 503，让 Swarm/Traefik 剔除当前实例
 * 2. 从 Nacos 注册中心注销
 * 3. 等待 Swarm VIP / Feign 客户端缓存过期（15秒）
 * 4. Spring Boot graceful shutdown 处理完当前 HTTP 请求
 * 5. 停止内部组件（Kafka、线程池、定时任务）
 * 6. 释放分布式资源（Redis 锁等）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SwarmGracefulShutdown implements ApplicationListener<ContextClosedEvent> {

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
     * 健康检查端点 - Swarm 用此判断容器是否可用
     * 停机时返回 503，让 Swarm 从 VIP 后端剔除此实例
     */
    @GetMapping("/actuator/health")
    public ResponseEntity<String> health() {
        if (shuttingDown) {
            log.warn("健康检查返回 503，实例正在停机");
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
        log.info("========== 开始分布式优雅停机（Swarm 环境）==========");
        
        try {
            // Step 1: 健康检查返回 503，让 Swarm/Traefik 不再路由流量
            step1_markShuttingDown();
            
            // Step 2: 从 Nacos 注册中心注销（阻止 Feign 客户端发现此实例）
            step2_deregisterFromNacos();
            
            // Step 3: 等待 Swarm VIP 和 Feign 客户端缓存过期
            // Swarm VIP 刷新有延迟，Feign/Ribbon 默认缓存 30 秒
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
        log.info("[Step 1/6] 标记停机状态，健康检查将返回 503");
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
        log.info("[Step 3/6] 等待 {}ms，让 Swarm VIP 和 Feign 缓存过期...", millis);
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

### 1.2 GracefulStoppable.java（接口）

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

### 1.3 KafkaConsumerStoppable.java（示例组件）

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

### 1.4 ThreadPoolStoppable.java（线程池组件）

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

## 二、application.yml 配置

```yaml
server:
  port: 8080
  # 【关键】启用 Spring Boot 优雅停机
  shutdown: graceful

spring:
  application:
    name: your-service
  
  lifecycle:
    # 停机时等待组件关闭的最大时间（需小于 stop_grace_period）
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

## 三、Dockerfile

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

# 健康检查（Swarm 用）
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
```

> ⚠️ **注意**：ENTRYPOINT 必须用 `exec` 格式（JSON 数组），如果用 `ENTRYPOINT java -jar app.jar`（shell 格式），SIGTERM 会被 shell 截断，Java 进程收不到！

---

## 四、docker-compose.yml（Swarm Stack）

```yaml
version: '3.8'

services:
  app:
    image: registry.local/your-service:${VERSION:-latest}
    
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
    
    deploy:
      replicas: 3
      
      # 【核心】滚动更新配置
      update_config:
        parallelism: 1          # 一次只更新1个实例
        delay: 45s              # 每个实例更新间隔（需大于启动时间）
        order: start-first      # 先启动新容器，再停旧容器
        failure_action: rollback  # 失败自动回滚
        monitor: 60s            # 监控新容器60秒
      
      rollback_config:
        parallelism: 1
        delay: 10s
        order: stop-first
      
      restart_policy:
        condition: any
        delay: 5s
        max_attempts: 3
        window: 120s
      
      # 资源限制
      resources:
        limits:
          cpus: '2.0'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M
      
      # 部署标签（可选）
      labels:
        - "traefik.enable=true"
        - "traefik.http.routers.app.rule=Host(`api.yourcompany.com`)"
        - "traefik.http.services.app.loadbalancer.server.port=8080"
    
    # 【关键】优雅停机等待时间，必须大于 timeout-per-shutdown-phase
    stop_grace_period: 60s
    
    # 网络
    networks:
      - backend
    
    # 健康检查（Swarm 用此判断容器是否健康）
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 60s       # 启动后60秒内不计入失败

  # 可选：Traefik 作为入口网关，比 Swarm 内置 VIP 更可控
  traefik:
    image: traefik:v2.10
    command:
      - "--api.dashboard=true"
      - "--providers.docker.swarmMode=true"
      - "--providers.docker.exposedbydefault=false"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.web.transport.respondingTimeouts.idleTimeout=30s"
      - "--ping=true"
    ports:
      - target: 80
        published: 80
        mode: host
      - target: 8080
        published: 8080
        mode: host
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks:
      - backend
    deploy:
      mode: global
      placement:
        constraints:
          - node.role == manager
      labels:
        - "traefik.enable=true"
        - "traefik.http.routers.traefik.rule=Host(`traefik.yourcompany.com`)"
        - "traefik.http.routers.traefik.service=api@internal"

networks:
  backend:
    driver: overlay
    attachable: true
```

---

## 五、部署命令

```bash
# 1. 构建镜像
docker build -t registry.local/your-service:1.2.0 .
docker push registry.local/your-service:1.2.0

# 2. 部署/更新 Swarm Stack
export VERSION=1.2.0
docker stack deploy -c docker-compose.yml your-app-stack

# 3. 查看滚动更新进度
watch -n 1 docker service ps your-app-stack_app

# 4. 查看服务日志（实时）
docker service logs -f your-app-stack_app

# 5. 手动触发滚动更新（强制重新部署）
docker service update --force your-app-stack_app
```

---

## 六、验证优雅停机是否生效

```bash
# 1. 找到要停止的容器
CONTAINER_ID=$(docker ps -q -f name=your-app-stack_app)

# 2. 优雅停止（发送 SIGTERM）
docker stop -t 60 $CONTAINER_ID

# 3. 查看日志（应该在 60 秒内完成停机，而不是被 kill -9）
docker logs $CONTAINER_ID

# 预期日志输出：
# [Step 1/6] 标记停机状态...
# [Step 2/6] 从 Nacos 注册中心注销...
# [Step 3/6] 等待 15000ms，让 Swarm VIP 和 Feign 缓存过期...
# [Step 4/6] 等待当前 HTTP 请求处理完成...
# [Step 5/6] 停止内部组件...
# [Step 6/6] 释放分布式资源...
# ========== 分布式优雅停机完成 ==========
```

---

## 七、关键配置速查表

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `server.shutdown` | `graceful` | Spring Boot 优雅停机 |
| `timeout-per-shutdown-phase` | `45s` | 组件关闭超时 |
| `stop_grace_period` | `60s` | Docker 发送 SIGKILL 前的等待 |
| `update_config.delay` | `45s` | Swarm 滚动更新间隔 |
| `healthcheck.start_period` | `60s` | 启动宽限期 |
| Nacos `heart-beat-timeout` | `10s` | Nacos 判定实例死亡时间 |
| Feign `ttl` | `5s` | 负载均衡缓存刷新 |

---

## 八、常见问题排查

### Q1: 容器被 SIGKILL（exit code 137）
**原因**：`stop_grace_period` 内没完成停机  
**解决**：增大 `stop_grace_period` 或检查组件停止逻辑是否阻塞

### Q2: 停机后 Feign 还调用旧实例
**原因**：Feign/Ribbon 客户端缓存未过期  
**解决**：缩短 `spring.cloud.loadbalancer.cache.ttl`，或增加 `step3_waitForTrafficDrain` 时间

### Q3: Nacos 注销了但服务还在被调用
**原因**：Swarm VIP 刷新比 Nacos 慢  
**解决**：健康检查返回 503 配合 `stop_grace_period` 双保险

---

> 将此模板中的 `com.yourcompany` 替换为你的包名，`your-service` 替换为你的服务名，即可直接使用。
