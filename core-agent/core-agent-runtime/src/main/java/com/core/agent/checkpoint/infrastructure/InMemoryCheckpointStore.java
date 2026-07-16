package com.core.agent.checkpoint.infrastructure;

import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.domain.CheckpointStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Checkpoint 存储实现。
 *
 * <p>适合单元测试和开发环境；生产环境应替换为 JPA / Redis 等持久化实现。</p>
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Checkpoint> store = new ConcurrentHashMap<>();

    @Override
    public String save(Checkpoint checkpoint) {
        String token = checkpoint.getToken() != null ? checkpoint.getToken() : UUID.randomUUID().toString();
        Checkpoint toSave = checkpoint.toBuilder().token(token).build();
        store.put(token, toSave);
        return token;
    }

    @Override
    public Optional<Checkpoint> find(String token) {
        return Optional.ofNullable(store.get(token));
    }

    @Override
    public Checkpoint updateDecision(String token, String decision, String comment) {
        Checkpoint existing = store.get(token);
        if (existing == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + token);
        }
        Checkpoint updated = existing.toBuilder()
                .decision(decision)
                .comment(comment)
                .build();
        store.put(token, updated);
        return updated;
    }
}
