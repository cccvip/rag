package com.core.agent.checkpoint.infrastructure;

import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.domain.CheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 MyBatis-Plus 的 Checkpoint 持久化实现。
 */
public class MyBatisPlusCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisPlusCheckpointStore.class);

    private final CheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;

    public MyBatisPlusCheckpointStore(CheckpointMapper checkpointMapper, ObjectMapper objectMapper) {
        this.checkpointMapper = checkpointMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String save(Checkpoint checkpoint) {
        String token = checkpoint.getToken() != null ? checkpoint.getToken() : UUID.randomUUID().toString();
        try {
            String stateJson = objectMapper.writeValueAsString(checkpoint.getState());
            CheckpointEntity entity = CheckpointEntity.builder()
                    .token(token)
                    .stateJson(stateJson)
                    .createdAt(checkpoint.getCreatedAt() != null ? checkpoint.getCreatedAt() : Instant.now())
                    .decision(checkpoint.getDecision())
                    .comment(checkpoint.getComment())
                    .build();
            checkpointMapper.insert(entity);
            return token;
        } catch (Exception e) {
            log.error("Failed to save checkpoint: {}", token, e);
            throw new IllegalStateException("Failed to save checkpoint", e);
        }
    }

    @Override
    public Optional<Checkpoint> find(String token) {
        return Optional.ofNullable(checkpointMapper.selectById(token)).map(this::toDomain);
    }

    @Override
    public Checkpoint updateDecision(String token, String decision, String comment) {
        CheckpointEntity entity = checkpointMapper.selectById(token);
        if (entity == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + token);
        }
        entity.setDecision(decision);
        entity.setComment(comment);
        checkpointMapper.updateById(entity);
        return toDomain(entity);
    }

    private Checkpoint toDomain(CheckpointEntity entity) {
        try {
            return Checkpoint.builder()
                    .token(entity.getToken())
                    .state(objectMapper.readValue(entity.getStateJson(), AgentState.class))
                    .createdAt(entity.getCreatedAt())
                    .decision(entity.getDecision())
                    .comment(entity.getComment())
                    .build();
        } catch (Exception e) {
            log.error("Failed to deserialize checkpoint: {}", entity.getToken(), e);
            throw new IllegalStateException("Failed to deserialize checkpoint", e);
        }
    }
}
