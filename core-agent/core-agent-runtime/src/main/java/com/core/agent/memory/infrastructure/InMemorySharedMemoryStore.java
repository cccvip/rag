package com.core.agent.memory.infrastructure;

import com.core.agent.memory.domain.MemoryScope;
import com.core.agent.memory.domain.SharedMemoryMessage;
import com.core.agent.memory.domain.SharedMemoryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存版共享记忆存储实现。
 */
public class InMemorySharedMemoryStore implements SharedMemoryStore {

    private final List<SharedMemoryMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void save(SharedMemoryMessage message) {
        messages.add(message);
    }

    @Override
    public List<SharedMemoryMessage> findByScope(MemoryScope scope, String scopeKey, String agentId, int limit) {
        List<SharedMemoryMessage> filtered = messages.stream()
                .filter(m -> m.getScope() == scope)
                .filter(m -> scopeKey == null || scopeKey.equals(m.getScopeKey()))
                .filter(m -> agentId == null || agentId.equals(m.getAgentId()))
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(filtered);
        return filtered.stream().limit(limit).collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void delete(String id) {
        messages.removeIf(m -> m.getId().equals(id));
    }
}
