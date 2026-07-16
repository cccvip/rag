package com.core.agent.checkpoint;

import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.infrastructure.InMemoryCheckpointStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCheckpointStoreTest {

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    @Test
    void shouldSaveAndFindCheckpoint() {
        AgentState state = AgentState.initial("t-1", "tenant", "user", "scene")
                .withVariable("query", "test");
        Checkpoint checkpoint = Checkpoint.pending(null, state);

        String token = store.save(checkpoint);

        assertThat(token).isNotBlank();
        Optional<Checkpoint> found = store.find(token);
        assertTrue(found.isPresent(), "checkpoint should be found");
        Checkpoint c = found.get();
        assertThat(c.getToken()).isEqualTo(token);
        assertThat((String) c.getState().getVariable("query")).isEqualTo("test");
        assertThat(c.getDecision()).isEqualTo("pending");
    }

    @Test
    void shouldUpdateDecision() {
        AgentState state = AgentState.initial("t-1", "tenant", "user", "scene");
        String token = store.save(Checkpoint.pending(null, state));

        Checkpoint approved = store.updateDecision(token, "approved", "ok");

        assertThat(approved.getDecision()).isEqualTo("approved");
        assertThat(approved.getComment()).isEqualTo("ok");
        assertThat(store.find(token).get().getDecision()).isEqualTo("approved");
    }

    @Test
    void shouldThrowWhenUpdatingMissingCheckpoint() {
        assertThatThrownBy(() -> store.updateDecision("missing", "approved", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }
}
