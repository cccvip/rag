package com.core.agent.checkpoint.application;

import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.domain.CheckpointStore;

import java.util.Optional;

/**
 * Checkpoint 应用服务。
 *
 * <p>封装持久化与审批状态流转，供引擎和 REST 层调用。</p>
 */
public class CheckpointService {

    private final CheckpointStore store;

    public CheckpointService(CheckpointStore store) {
        this.store = store;
    }

    public String save(Checkpoint checkpoint) {
        return store.save(checkpoint);
    }

    public Optional<Checkpoint> find(String token) {
        return store.find(token);
    }

    public Checkpoint approve(String token, String comment) {
        return store.updateDecision(token, "approved", comment);
    }

    public Checkpoint reject(String token, String comment) {
        return store.updateDecision(token, "rejected", comment);
    }
}
