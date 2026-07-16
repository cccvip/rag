package com.core.agent.multiagent.infrastructure;

import com.core.agent.agent.domain.Agent;
import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.A2aGateway;
import com.core.agent.multiagent.domain.AgentCard;
import com.core.agent.multiagent.domain.SubTask;
import com.core.agent.multiagent.domain.Worker;
import com.core.agent.multiagent.domain.WorkerResolver;

import java.util.Map;
import java.util.function.Function;

/**
 * 默认 Worker 解析器。
 *
 * <p>优先从本地 Agent 工厂查找，否则通过 AgentRegistry + A2A Gateway 调用远程 Agent。</p>
 */
public class DefaultWorkerResolver implements WorkerResolver {

    private final Map<String, Agent> localAgents;
    private final AgentRegistry agentRegistry;
    private final A2aGateway a2aGateway;

    public DefaultWorkerResolver(Map<String, Agent> localAgents,
                                  AgentRegistry agentRegistry,
                                  A2aGateway a2aGateway) {
        this.localAgents = localAgents != null ? localAgents : Map.of();
        this.agentRegistry = agentRegistry;
        this.a2aGateway = a2aGateway;
    }

    @Override
    public Worker resolve(SubTask subTask) {
        String agentId = subTask.getAssignedAgentId();
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("SubTask assignedAgentId is required");
        }

        Agent local = localAgents.get(agentId);
        if (local != null) {
            return new LocalAgentWorker(agentId, local);
        }

        if (agentRegistry != null) {
            AgentCard card = agentRegistry.find(agentId).orElse(null);
            if (card != null && a2aGateway != null) {
                return new A2aAgentWorker(agentId, card.getEndpoint(), a2aGateway);
            }
        }

        throw new IllegalStateException("Cannot resolve worker for agent: " + agentId);
    }
}
