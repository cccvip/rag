package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.agent.strategy.infrastructure.supervisor.AggregateNode;
import com.core.agent.agent.strategy.infrastructure.supervisor.DecomposeNode;
import com.core.agent.agent.strategy.infrastructure.supervisor.WorkerDispatchNode;
import com.core.agent.multiagent.domain.SubTask;
import com.core.agent.multiagent.domain.WorkerResolver;

import java.util.List;
import java.util.function.Function;

/**
 * Supervisor + Workers 多 Agent 执行策略。
 *
 * <p>状态图：decompose → dispatch → aggregate → end</p>
 */
public class SupervisorStrategy implements ExecutionStrategy<NodeContext> {

    private final String name;
    private final Function<String, List<SubTask>> decomposer;
    private final WorkerResolver workerResolver;
    private final Function<List<com.core.agent.multiagent.domain.WorkerResult>, String> aggregator;

    public SupervisorStrategy(String name,
                               Function<String, List<SubTask>> decomposer,
                               WorkerResolver workerResolver,
                               Function<List<com.core.agent.multiagent.domain.WorkerResult>, String> aggregator) {
        this.name = name;
        this.decomposer = decomposer;
        this.workerResolver = workerResolver;
        this.aggregator = aggregator;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentGraph<NodeContext> compile() {
        return AgentGraph.<NodeContext>builder()
                .startNode("decompose")
                .addNode(new DecomposeNode("decompose", decomposer))
                .addNode(new WorkerDispatchNode("dispatch", workerResolver))
                .addNode(new AggregateNode("aggregate", aggregator))
                .addNode("end", new EndNode())
                .addEdge("decompose", "dispatch")
                .addEdge("dispatch", "aggregate")
                .addEdge("aggregate", "end")
                .endNode("end")
                .maxSteps(100)
                .build();
    }

    private static class EndNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "end";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            return state;
        }
    }
}
