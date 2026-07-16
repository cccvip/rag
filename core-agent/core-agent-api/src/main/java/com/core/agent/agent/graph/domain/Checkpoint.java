package com.core.agent.agent.graph.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 执行 checkpoint，用于 HITL 暂停后恢复。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Checkpoint {

    /** checkpoint 唯一标识 */
    private String token;

    /** 保存的状态快照 */
    private AgentState state;

    /** 创建时间 */
    private Instant createdAt;

    /** 审批决策：approved / rejected / pending */
    private String decision;

    /** 审批备注 */
    private String comment;

    public static Checkpoint pending(String token, AgentState state) {
        return Checkpoint.builder()
                .token(token)
                .state(state)
                .createdAt(Instant.now())
                .decision("pending")
                .build();
    }
}
