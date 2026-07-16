package com.core.agent.multiagent.application;

import com.core.agent.multiagent.domain.AgentCard;

import java.util.List;
import java.util.Optional;

/**
 * Agent Card 注册与发现 SPI。
 */
public interface AgentRegistry {

    void register(AgentCard card);

    Optional<AgentCard> find(String agentId);

    List<AgentCard> list();

    List<AgentCard> findByCapability(String capability);
}
