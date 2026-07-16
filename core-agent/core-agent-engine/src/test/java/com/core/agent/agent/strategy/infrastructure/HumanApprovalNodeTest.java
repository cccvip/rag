package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.checkpoint.application.CheckpointService;
import com.core.agent.checkpoint.infrastructure.InMemoryCheckpointStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanApprovalNodeTest {

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    private final CheckpointService checkpointService = new CheckpointService(store);
    private final HumanApprovalNode node = new HumanApprovalNode(
            "human_approval", "请确认是否继续执行？", checkpointService);

    @Test
    void shouldCreateCheckpointAndAwaitApproval() {
        AgentState state = AgentState.initial("t-1", "tenant", "user", "scene")
                .withVariable("query", "敏感操作");
        NodeContext ctx = NodeContext.builder().build();

        AgentState result = node.invoke(state, ctx);

        assertThat(result.getStatus()).isEqualTo(AgentState.Status.AWAITING_APPROVAL);
        assertThat(result.getCheckpointToken()).isNotBlank();
        assertThat((String) result.getVariable("checkpointToken")).isEqualTo(result.getCheckpointToken());
        Optional<Checkpoint> found = store.find(result.getCheckpointToken());
        assertTrue(found.isPresent(), "checkpoint should be persisted");
    }
}
