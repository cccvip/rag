package com.core.agent.graph;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentMessage;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.graph.domain.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentGraph 状态图执行引擎单元测试。
 */
class AgentGraphTest {

    private Object emptyContext() {
        return new Object();
    }

    @Test
    void shouldExecuteLinearGraph() {
        AgentGraph<Object> graph = AgentGraph.<Object>builder()
                .startNode("start")
                .addNode("start", node((state, ctx) -> state.withMessage(AgentMessage.system("start"))
                        .withVariable("step", "start")))
                .addNode("middle", node((state, ctx) -> state.withMessage(AgentMessage.system("middle"))
                        .withVariable("step", "middle")))
                .addNode("end", node((state, ctx) -> state.withMessage(AgentMessage.system("end"))
                        .withVariable("step", "end")))
                .addEdge("start", "middle")
                .addEdge("middle", "end")
                .endNode("end")
                .maxSteps(10)
                .build();

        AgentState initial = AgentState.initial("trace-1", "tenant-1", "user-1", "test")
                .withCurrentNode("start");

        GraphResult result = graph.execute(initial, emptyContext());

        assertTrue(result.isCompleted());
        assertEquals("end", result.getFinalState().getVariable("step"));
        assertEquals(3, result.getFinalState().getMessages().size());
    }

    @Test
    void shouldRouteByConditionalEdge() {
        AgentGraph<Object> graph = AgentGraph.<Object>builder()
                .startNode("start")
                .addNode("start", node((state, ctx) -> state)) // no-op
                .addNode("hot", node((state, ctx) -> state.withVariable("branch", "hot")))
                .addNode("cold", node((state, ctx) -> state.withVariable("branch", "cold")))
                .addNode("end", node((state, ctx) -> state))
                .addConditionalEdge("start", "hot", s -> Boolean.TRUE.equals(s.getVariable("isHot")))
                .addConditionalEdge("start", "cold", s -> !Boolean.TRUE.equals(s.getVariable("isHot")))
                .addEdge("hot", "end")
                .addEdge("cold", "end")
                .endNode("end")
                .maxSteps(10)
                .build();

        AgentState hotInitial = AgentState.initial("trace-2", "tenant-1", "user-1", "test")
                .withCurrentNode("start")
                .withVariable("isHot", true);

        GraphResult hotResult = graph.execute(hotInitial, emptyContext());
        assertTrue(hotResult.isCompleted());
        assertEquals("hot", hotResult.getFinalState().getVariable("branch"));

        AgentState coldInitial = AgentState.initial("trace-3", "tenant-1", "user-1", "test")
                .withCurrentNode("start")
                .withVariable("isHot", false);

        GraphResult coldResult = graph.execute(coldInitial, emptyContext());
        assertTrue(coldResult.isCompleted());
        assertEquals("cold", coldResult.getFinalState().getVariable("branch"));
    }

    @Test
    void shouldPauseAndResumeAtCheckpoint() {
        AgentGraph<Object> graph = AgentGraph.<Object>builder()
                .startNode("start")
                .addNode("start", node((state, ctx) -> state.withVariable("step", "start")))
                .addNode("approval", node((state, ctx) -> {
                    if (Boolean.TRUE.equals(state.getVariable("approved"))) {
                        return state.withVariable("step", "approval");
                    }
                    return state.withVariable("step", "approval")
                            .awaitingApproval("ckpt-1");
                }))
                .addNode("end", node((state, ctx) -> state.withVariable("step", "end")))
                .addEdge("start", "approval")
                .addEdge("approval", "end")
                .endNode("end")
                .maxSteps(10)
                .build();

        AgentState initial = AgentState.initial("trace-4", "tenant-1", "user-1", "test")
                .withCurrentNode("start");

        GraphResult firstResult = graph.execute(initial, emptyContext());

        assertFalse(firstResult.isCompleted());
        assertTrue(firstResult.isAwaitingApproval());
        assertEquals("ckpt-1", firstResult.getCheckpointToken());
        assertEquals("approval", firstResult.getFinalState().getVariable("step"));

        AgentState approvedState = firstResult.getFinalState()
                .resumeFromCheckpoint()
                .withVariable("approved", true);
        Checkpoint checkpoint = Checkpoint.pending("ckpt-1", approvedState);
        GraphResult resumedResult = graph.resume(checkpoint, emptyContext());

        assertTrue(resumedResult.isCompleted());
        assertEquals("end", resumedResult.getFinalState().getVariable("step"));
    }

    @Test
    void shouldHaltWhenNoOutgoingEdge() {
        AgentGraph<Object> graph = AgentGraph.<Object>builder()
                .startNode("start")
                .addNode("start", node((state, ctx) -> state.withVariable("step", "start")))
                .maxSteps(10)
                .build();

        AgentState initial = AgentState.initial("trace-5", "tenant-1", "user-1", "test")
                .withCurrentNode("start");

        GraphResult result = graph.execute(initial, emptyContext());

        assertFalse(result.isCompleted());
        assertEquals(AgentState.Status.HALTED, result.getFinalState().getStatus());
    }

    @Test
    void shouldErrorOnMaxStepsExceeded() {
        AgentGraph<Object> graph = AgentGraph.<Object>builder()
                .startNode("loop")
                .addNode("loop", node((state, ctx) -> state.withVariable("count",
                        state.getVariable("count") == null ? 1 : (int) state.getVariable("count") + 1)))
                .addEdge("loop", "loop")
                .maxSteps(3)
                .build();

        AgentState initial = AgentState.initial("trace-6", "tenant-1", "user-1", "test")
                .withCurrentNode("loop");

        GraphResult result = graph.execute(initial, emptyContext());

        assertFalse(result.isCompleted());
        assertTrue(result.getErrorMessage().contains("max graph execution steps"));
    }

    private AgentNode<Object> node(java.util.function.BiFunction<AgentState, Object, AgentState> fn) {
        return fn::apply;
    }
}
