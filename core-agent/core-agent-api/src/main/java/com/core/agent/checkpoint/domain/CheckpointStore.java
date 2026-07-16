package com.core.agent.checkpoint.domain;

import com.core.agent.agent.graph.domain.Checkpoint;

import java.util.Optional;

/**
 * Checkpoint 持久化 SPI。
 *
 * <p>用于保存和恢复状态图执行快照，支撑 HITL 人工审批、故障恢复、长任务断点续跑。</p>
 */
public interface CheckpointStore {

    /**
     * 保存 checkpoint，返回全局唯一 token。
     */
    String save(Checkpoint checkpoint);

    /**
     * 根据 token 查询 checkpoint。
     */
    Optional<Checkpoint> find(String token);

    /**
     * 更新审批决策。
     *
     * @param token    checkpoint token
     * @param decision approved / rejected
     * @param comment  审批备注，可选
     * @return 更新后的 checkpoint
     */
    Checkpoint updateDecision(String token, String decision, String comment);
}
