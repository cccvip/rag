package com.core.agent.multiagent.infrastructure;

import com.core.agent.multiagent.domain.A2aGateway;
import com.core.agent.multiagent.domain.A2aTask;
import com.core.agent.multiagent.domain.SubTask;
import com.core.agent.multiagent.domain.Worker;
import com.core.agent.multiagent.domain.WorkerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 远程 A2A Agent Worker 实现。
 */
public class A2aAgentWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentWorker.class);

    private final String agentId;
    private final String endpoint;
    private final A2aGateway a2aGateway;

    public A2aAgentWorker(String agentId, String endpoint, A2aGateway a2aGateway) {
        this.agentId = agentId;
        this.endpoint = endpoint;
        this.a2aGateway = a2aGateway;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public WorkerResult execute(SubTask subTask) {
        try {
            A2aTask task = a2aGateway.sendTask(endpoint, subTask.getDescription());
            return WorkerResult.builder()
                    .subTaskId(subTask.getId())
                    .agentId(agentId)
                    .output(task.getAnswer())
                    .success("completed".equalsIgnoreCase(task.getStatus()))
                    .build();
        } catch (Exception e) {
            log.error("A2aAgentWorker failed for subTask={} endpoint={}", subTask.getId(), endpoint, e);
            return WorkerResult.builder()
                    .subTaskId(subTask.getId())
                    .agentId(agentId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
