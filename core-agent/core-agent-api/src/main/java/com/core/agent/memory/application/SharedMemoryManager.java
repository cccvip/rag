package com.core.agent.memory.application;

import com.core.agent.memory.domain.MemoryScope;
import com.core.agent.memory.domain.SharedMemoryMessage;
import com.core.agent.memory.domain.SharedMemoryStore;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 跨 Agent 共享记忆管理器。
 *
 * <p>在会话记忆之上提供 USER / TENANT / AGENT 级别的共享记忆，支持 token 预算裁剪。</p>
 */
public class SharedMemoryManager {

    private final SharedMemoryStore store;
    private final java.util.function.Function<String, Integer> tokenEstimator;

    public SharedMemoryManager(SharedMemoryStore store) {
        this(store, content -> content.length() / 4);
    }

    public SharedMemoryManager(SharedMemoryStore store,
                               java.util.function.Function<String, Integer> tokenEstimator) {
        this.store = store;
        this.tokenEstimator = tokenEstimator;
    }

    public void save(MemoryScope scope, String scopeKey, String agentId, String role, String content) {
        SharedMemoryMessage message = SharedMemoryMessage.builder()
                .id(UUID.randomUUID().toString())
                .scope(scope)
                .scopeKey(scopeKey)
                .agentId(agentId)
                .role(role)
                .content(content)
                .tokenCount(tokenEstimator.apply(content))
                .createdAt(java.time.Instant.now())
                .build();
        store.save(message);
    }

    public List<SharedMemoryMessage> getHistory(MemoryScope scope, String scopeKey, String agentId, int maxTokens) {
        List<SharedMemoryMessage> all = store.findByScope(scope, scopeKey, agentId, Integer.MAX_VALUE);
        Collections.reverse(all); // chronological order

        int tokens = 0;
        java.util.ArrayList<SharedMemoryMessage> selected = new java.util.ArrayList<>();
        // 从最新开始选，满足预算后整体反转回 chronological
        for (int i = all.size() - 1; i >= 0; i--) {
            SharedMemoryMessage msg = all.get(i);
            tokens += msg.getTokenCount();
            if (tokens > maxTokens && !selected.isEmpty()) {
                break;
            }
            selected.add(msg);
        }
        Collections.reverse(selected);
        return selected;
    }
}
