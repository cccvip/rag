package com.core.agent.agent.strategy.infrastructure.supervisor;

import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.multiagent.domain.WorkerResult;

import java.util.List;
import java.util.function.Function;

/**
 * Supervisor 汇总节点：将 Worker 结果聚合成最终答案。
 */
public class AggregateNode implements AgentNode<NodeContext> {

    private final String name;
    private final Function<List<WorkerResult>, String> aggregator;

    public AggregateNode(String name, Function<List<WorkerResult>, String> aggregator) {
        this.name = name;
        this.aggregator = aggregator;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentState invoke(AgentState state, NodeContext ctx) {
        List<WorkerResult> results = (List<WorkerResult>) state.getVariable("workerResults");
        String finalAnswer = aggregator.apply(results != null ? results : List.of());
        return state.completed(finalAnswer);
    }
}
