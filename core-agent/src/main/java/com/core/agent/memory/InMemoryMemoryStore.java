package com.core.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 MemoryStore，仅用于原型验证。
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, List<MemoryMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void save(String sessionId, MemoryMessage message) {
        store.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>())).add(message);
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        List<MemoryMessage> messages = store.getOrDefault(sessionId, Collections.emptyList());
        return trimByTokens(messages, maxTokens);
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }

    /**
     * 按 token 预算从最近消息向前裁剪，优先保留最新内容。
     */
    protected List<MemoryMessage> trimByTokens(List<MemoryMessage> messages, int maxTokens) {
        List<MemoryMessage> result = new ArrayList<>();
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MemoryMessage msg = messages.get(i);
            if (total + msg.getTokenCount() > maxTokens) {
                break;
            }
            total += msg.getTokenCount();
            result.add(0, msg);
        }
        return result;
    }
}
