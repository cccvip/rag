package com.core.agent.multiagent.infrastructure.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aSendTaskRequest {

    private String query;
}
