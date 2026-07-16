package com.core.agent.multiagent.infrastructure;

import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.AgentCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 Agent Registry 实现。
 */
public class InMemoryAgentRegistry implements AgentRegistry {

    private final Map<String, AgentCard> agents = new ConcurrentHashMap<>();

    @Override
    public void register(AgentCard card) {
        agents.put(card.getAgentId(), card);
    }

    @Override
    public Optional<AgentCard> find(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public List<AgentCard> list() {
        return new ArrayList<>(agents.values());
    }

    @Override
    public List<AgentCard> findByCapability(String capability) {
        return agents.values().stream()
                .filter(card -> card.getCapabilities() != null && card.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }
}
