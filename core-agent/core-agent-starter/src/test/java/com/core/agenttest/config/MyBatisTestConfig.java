package com.core.agenttest.config;

import com.core.agent.checkpoint.infrastructure.CheckpointMapper;
import com.core.agent.checkpoint.infrastructure.MyBatisPlusCheckpointStore;
import com.core.agent.memory.infrastructure.MyBatisPlusSharedMemoryStore;
import com.core.agent.memory.infrastructure.SharedMemoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 用于 MyBatis-Plus 集成测试的独立配置入口。
 * 放在 {@code com.core.agenttest.config} 包下，避免被生产代码的 {@code @SpringBootApplication(scanBasePackages="com.core.agent")} 组件扫描到。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan({
        "com.core.agent.checkpoint.infrastructure",
        "com.core.agent.memory.infrastructure"
})
public class MyBatisTestConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public MyBatisPlusCheckpointStore checkpointStore(CheckpointMapper checkpointMapper, ObjectMapper objectMapper) {
        return new MyBatisPlusCheckpointStore(checkpointMapper, objectMapper);
    }

    @Bean
    public MyBatisPlusSharedMemoryStore sharedMemoryStore(SharedMemoryMapper sharedMemoryMapper) {
        return new MyBatisPlusSharedMemoryStore(sharedMemoryMapper);
    }
}
