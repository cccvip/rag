package com.core.agent.multiagent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent Card：描述一个可被其他 Agent 发现的智能体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCard {

    private String agentId;
    private String name;
    private String description;
    private List<String> capabilities;
    private String endpoint;
    private String version;
    private String status;
    private Map<String, Object> metadata;
}
