package com.core.agent.agent.strategy.infrastructure.supervisor;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.infrastructure.SupervisorStrategy;
import com.core.agent.multiagent.domain.SubTask;
import com.core.agent.multiagent.domain.Worker;
import com.core.agent.multiagent.domain.WorkerResolver;
import com.core.agent.multiagent.domain.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorStrategyTest {

    @Test
    void shouldRunSupervisorWorkflow() {
        SupervisorStrategy strategy = new SupervisorStrategy(
                "supervisor",
                query -> List.of(
                        SubTask.builder().id("t1").description("part-1").assignedAgentId("worker-1").build(),
                        SubTask.builder().id("t2").description("part-2").assignedAgentId("worker-2").build()
                ),
                new StaticWorkerResolver(),
                results -> results.stream()
                        .map(WorkerResult::getOutput)
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("")
        );

        AgentGraph<NodeContext> graph = strategy.compile();
        AgentState initial = AgentState.initial("t-1", "tenant", "user", "scene")
                .withVariable("query", "complex question")
                .withCurrentNode(graph.getStartNode());

        GraphResult result = graph.execute(initial, NodeContext.builder().build());

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done: part-1 | done: part-2");
    }

    static class StaticWorkerResolver implements WorkerResolver {
        @Override
        public Worker resolve(SubTask subTask) {
            return new Worker() {
                @Override
                public String agentId() {
                    return subTask.getAssignedAgentId();
                }

                @Override
                public WorkerResult execute(SubTask task) {
                    return WorkerResult.builder()
                            .subTaskId(task.getId())
                            .agentId(agentId())
                            .output("done: " + task.getDescription())
                            .success(true)
                            .build();
                }
            };
        }
    }
}
