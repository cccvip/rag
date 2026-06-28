package com.core.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMemoryManagerTest {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    @Test
    void shouldSaveAndRetrieveHistory() {
        MemoryStore store = new InMemoryMemoryStore();
        MemoryManager manager = new InMemoryMemoryManager(store, estimator, 2000);

        String sessionId = "session-test";
        manager.save(sessionId, "user", "What is the emergency procedure for chemical spill?");
        manager.save(sessionId, "assistant", "First, evacuate the area [doc-1001].");

        List<MemoryMessage> history = manager.getHistory(sessionId, 2000);
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("assistant", history.get(1).getRole());
        assertTrue(history.get(0).getTokenCount() > 0);
        assertTrue(history.get(1).getTokenCount() > 0);
    }

    @Test
    void shouldTrimByTokenBudget() {
        MemoryStore store = new InMemoryMemoryStore();
        MemoryManager manager = new InMemoryMemoryManager(store, estimator, 2000);

        String sessionId = "session-trim";
        manager.save(sessionId, "user", "This is a relatively long question that will consume many tokens.");
        manager.save(sessionId, "assistant", "This is a relatively long answer that will consume many tokens.");

        List<MemoryMessage> fullHistory = manager.getHistory(sessionId, 2000);
        int lastMessageTokens = fullHistory.get(fullHistory.size() - 1).getTokenCount();

        // 预算刚好等于最后一条消息，应只保留最近一条
        List<MemoryMessage> trimmed = manager.getHistory(sessionId, lastMessageTokens);
        assertEquals(1, trimmed.size());
        assertEquals("assistant", trimmed.get(0).getRole());
    }

    @Test
    void shouldIsolateSessions() {
        MemoryStore store = new InMemoryMemoryStore();
        MemoryManager manager = new InMemoryMemoryManager(store, estimator, 2000);

        manager.save("session-a", "user", "question A");
        manager.save("session-b", "user", "question B");

        assertEquals(1, manager.getHistory("session-a", 2000).size());
        assertEquals(1, manager.getHistory("session-b", 2000).size());
        assertEquals("question A", manager.getHistory("session-a", 2000).get(0).getContent());
    }

    @Test
    void shouldClearSession() {
        MemoryStore store = new InMemoryMemoryStore();
        MemoryManager manager = new InMemoryMemoryManager(store, estimator, 2000);

        manager.save("session-clear", "user", "hello");
        manager.clear("session-clear");

        assertTrue(manager.getHistory("session-clear", 2000).isEmpty());
    }
}
