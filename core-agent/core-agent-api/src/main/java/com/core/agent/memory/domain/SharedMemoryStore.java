package com.core.agent.memory.domain;

import java.util.List;

/**
 * 共享记忆持久化 SPI。
 */
public interface SharedMemoryStore {

    void save(SharedMemoryMessage message);

    List<SharedMemoryMessage> findByScope(MemoryScope scope, String scopeKey, String agentId, int limit);

    void delete(String id);
}
