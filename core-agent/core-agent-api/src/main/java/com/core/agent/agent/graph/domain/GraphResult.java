package com.core.agent.agent.graph.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 状态图执行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphResult {

    /** 最终状态 */
    private AgentState finalState;

    /** 是否成功完成 */
    private boolean completed;

    /** 最终答案（当 completed=true 时） */
    private String finalAnswer;

    /** 错误信息（当执行失败时） */
    private String errorMessage;

    /** 是否等待人工审批 */
    private boolean awaitingApproval;

    /** checkpoint token（当 awaitingApproval=true 时） */
    private String checkpointToken;

    public static GraphResult completed(AgentState state, String finalAnswer) {
        return GraphResult.builder()
                .finalState(state)
                .completed(true)
                .finalAnswer(finalAnswer)
                .awaitingApproval(false)
                .build();
    }

    public static GraphResult awaitingApproval(AgentState state, String checkpointToken) {
        return GraphResult.builder()
                .finalState(state)
                .completed(false)
                .awaitingApproval(true)
                .checkpointToken(checkpointToken)
                .build();
    }

    public static GraphResult error(AgentState state, String errorMessage) {
        return GraphResult.builder()
                .finalState(state)
                .completed(false)
                .errorMessage(errorMessage)
                .build();
    }

    public static GraphResult halted(AgentState state) {
        return GraphResult.builder()
                .finalState(state)
                .completed(false)
                .build();
    }
}
