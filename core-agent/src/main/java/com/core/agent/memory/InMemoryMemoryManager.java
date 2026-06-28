package com.core.agent.memory;

import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.List;

/**
 * 第一版 MemoryManager 实现：基于内存存储 + Spring AI TokenCountEstimator。
 */
public class InMemoryMemoryManager implements MemoryManager {

    private final MemoryStore store;
    private final TokenCountEstimator tokenCountEstimator;
    private final int defaultMaxTokens;

    public InMemoryMemoryManager(MemoryStore store, TokenCountEstimator tokenCountEstimator) {
        this(store, tokenCountEstimator, 2000);
    }

    public InMemoryMemoryManager(MemoryStore store, TokenCountEstimator tokenCountEstimator, int defaultMaxTokens) {
        this.store = store;
        this.tokenCountEstimator = tokenCountEstimator;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @Override
    public void save(String sessionId, String role, String content) {
        int tokens = tokenCountEstimator.estimate(content);
        MemoryMessage message = new MemoryMessage(role, content, System.currentTimeMillis(), tokens);
        store.save(sessionId, message);
    }

    @Override
    public List<MemoryMessage> getHistory(String sessionId, int maxTokens) {
        return store.getHistory(sessionId, maxTokens);
    }

    @Override
    public void clear(String sessionId) {
        store.clear(sessionId);
    }

    public int getDefaultMaxTokens() {
        return defaultMaxTokens;
    }
}
