package com.core.agent.memory;

import java.util.List;

/**
 * 记忆持久化层接口，定义短期/长期记忆的存取契约。
 *
 * 生产环境可扩展为：
 * - RedisMemoryStore：短期工作记忆，带 TTL
 * - DatabaseMemoryStore：长期记忆与审计日志
 */
public interface MemoryStore {

    void save(String sessionId, MemoryMessage message);

    List<MemoryMessage> getHistory(String sessionId, int maxTokens);

    void clear(String sessionId);
}
