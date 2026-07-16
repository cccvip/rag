package com.core.agent.multiagent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Supervisor 拆分的子任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubTask {

    private String id;
    private String description;
    private String assignedAgentId;
    private String workerType;
    private String status;
    private String result;
}
