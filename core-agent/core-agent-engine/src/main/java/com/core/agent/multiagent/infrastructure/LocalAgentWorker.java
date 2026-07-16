package com.core.agent.multiagent.infrastructure;

import com.core.agent.agent.domain.Agent;
import com.core.agent.agent.domain.AgentResult;
import com.core.agent.multiagent.domain.SubTask;
import com.core.agent.multiagent.domain.Worker;
import com.core.agent.multiagent.domain.WorkerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 本地 Agent Worker 实现。
 */
public class LocalAgentWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentWorker.class);

    private final String agentId;
    private final Agent agent;

    public LocalAgentWorker(String agentId, Agent agent) {
        this.agentId = agentId;
        this.agent = agent;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public WorkerResult execute(SubTask subTask) {
        try {
            String sessionId = UUID.randomUUID().toString();
            AgentResult result = agent.runWithResult(sessionId, subTask.getDescription());
            return WorkerResult.builder()
                    .subTaskId(subTask.getId())
                    .agentId(agentId)
                    .output(result.getAnswer())
                    .success(result.isSuccess())
                    .build();
        } catch (Exception e) {
            log.error("LocalAgentWorker failed for subTask={}", subTask.getId(), e);
            return WorkerResult.builder()
                    .subTaskId(subTask.getId())
                    .agentId(agentId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
