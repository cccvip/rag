package com.core.agent.agent.strategy.infrastructure.supervisor;

import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.multiagent.domain.SubTask;
import com.core.agent.multiagent.domain.Worker;
import com.core.agent.multiagent.domain.WorkerResolver;
import com.core.agent.multiagent.domain.WorkerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Supervisor 派发节点：并行调用多个 Worker 执行子任务。
 */
public class WorkerDispatchNode implements AgentNode<NodeContext> {

    private static final Logger log = LoggerFactory.getLogger(WorkerDispatchNode.class);

    private final String name;
    private final WorkerResolver workerResolver;

    public WorkerDispatchNode(String name, WorkerResolver workerResolver) {
        this.name = name;
        this.workerResolver = workerResolver;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentState invoke(AgentState state, NodeContext ctx) {
        List<SubTask> subTasks = (List<SubTask>) state.getVariable("subTasks");
        if (subTasks == null || subTasks.isEmpty()) {
            return state.withVariable("workerResults", List.of());
        }

        List<CompletableFuture<WorkerResult>> futures = new ArrayList<>();
        for (SubTask subTask : subTasks) {
            Worker worker = workerResolver.resolve(subTask);
            futures.add(CompletableFuture.supplyAsync(() -> worker.execute(subTask)));
        }

        List<WorkerResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        log.info("Worker dispatch completed: {} subTasks, {} results", subTasks.size(), results.size());
        return state.withVariable("workerResults", results);
    }
}
