package com.core.agent.memory;

import com.core.agenttest.config.MyBatisTestConfig;
import com.core.agent.memory.domain.MemoryScope;
import com.core.agent.memory.domain.SharedMemoryMessage;
import com.core.agent.memory.domain.SharedMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MyBatisTestConfig.class)
class MyBatisPlusSharedMemoryStoreTest {

    @Autowired
    private SharedMemoryStore sharedMemoryStore;

    @Test
    void shouldSaveAndFindByScope() {
        SharedMemoryMessage msg = SharedMemoryMessage.builder()
                .id(UUID.randomUUID().toString())
                .scope(MemoryScope.USER)
                .scopeKey("user-1")
                .agentId("agent-1")
                .role("user")
                .content("hello")
                .tokenCount(1)
                .createdAt(Instant.now())
                .build();

        sharedMemoryStore.save(msg);
        List<SharedMemoryMessage> found = sharedMemoryStore.findByScope(MemoryScope.USER, "user-1", "agent-1", 10);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getContent()).isEqualTo("hello");
    }
}
