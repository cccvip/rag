package com.core.agent.memory;

import com.core.agent.memory.application.SharedMemoryManager;
import com.core.agent.memory.domain.MemoryScope;
import com.core.agent.memory.domain.SharedMemoryMessage;
import com.core.agent.memory.infrastructure.InMemorySharedMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SharedMemoryManagerTest {

    private final InMemorySharedMemoryStore store = new InMemorySharedMemoryStore();
    private final SharedMemoryManager manager = new SharedMemoryManager(store, String::length);

    @Test
    void shouldSaveAndRetrieveByScope() {
        manager.save(MemoryScope.USER, "user-1", "agent-1", "user", "hello");
        manager.save(MemoryScope.USER, "user-1", "agent-1", "assistant", "hi");
        manager.save(MemoryScope.USER, "user-2", "agent-1", "user", "other");

        List<SharedMemoryMessage> history = manager.getHistory(MemoryScope.USER, "user-1", "agent-1", 100);

        assertThat(history).hasSize(2);
        assertThat(history).extracting(SharedMemoryMessage::getContent).containsExactly("hello", "hi");
    }

    @Test
    void shouldRespectTokenBudget() {
        manager.save(MemoryScope.TENANT, "tenant-1", "agent-1", "user", "aaaa"); // 4 tokens
        manager.save(MemoryScope.TENANT, "tenant-1", "agent-1", "user", "bbbb"); // 4 tokens
        manager.save(MemoryScope.TENANT, "tenant-1", "agent-1", "user", "cccc"); // 4 tokens

        List<SharedMemoryMessage> history = manager.getHistory(MemoryScope.TENANT, "tenant-1", "agent-1", 10);

        assertThat(history).hasSize(2);
        assertThat(history).extracting(SharedMemoryMessage::getContent).contains("bbbb", "cccc");
    }
}
