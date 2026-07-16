package com.core.agent.multiagent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Worker 执行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerResult {

    private String subTaskId;
    private String agentId;
    private String output;
    private boolean success;
    private String errorMessage;
}
