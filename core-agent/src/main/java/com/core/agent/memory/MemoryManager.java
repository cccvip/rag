package com.core.agent.memory;

import java.util.List;

/**
 * 记忆管理器，面向 Agent 提供会话历史的存取能力。
 *
 * 职责：
 * - 对消息做 token 估算
 * - 按 token 预算裁剪历史
 * - 屏蔽底层 MemoryStore 的差异
 */
public interface MemoryManager {

    void save(String sessionId, String role, String content);

    List<MemoryMessage> getHistory(String sessionId, int maxTokens);

    void clear(String sessionId);
}
