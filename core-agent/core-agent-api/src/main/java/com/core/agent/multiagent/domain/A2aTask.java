package com.core.agent.multiagent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A2A 任务领域模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aTask {

    private String taskId;
    private String query;
    private String status;
    private String answer;
    private List<A2aArtifact> artifacts;
    private Map<String, Object> metadata;
}
