package com.core.agent.multiagent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A2A 任务产物。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aArtifact {

    private String name;
    private String contentType;
    private String content;
}
