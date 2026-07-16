package com.core.agent.multiagent.infrastructure.a2a;

import com.core.agent.multiagent.domain.A2aArtifact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aTaskResponse {

    private String taskId;
    private String query;
    private String status;
    private String answer;
    private List<A2aArtifact> artifacts;
}
