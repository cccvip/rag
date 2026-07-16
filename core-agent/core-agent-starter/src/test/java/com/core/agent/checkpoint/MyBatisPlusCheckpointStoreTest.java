package com.core.agent.checkpoint;

import com.core.agenttest.config.MyBatisTestConfig;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.domain.CheckpointStore;
import com.core.agent.checkpoint.infrastructure.CheckpointMapper;
import com.core.agent.checkpoint.infrastructure.CheckpointEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MyBatisTestConfig.class)
class MyBatisPlusCheckpointStoreTest {

    @Autowired
    private CheckpointStore checkpointStore;

    @Autowired
    private CheckpointMapper checkpointMapper;

    @Test
    void shouldSaveAndFindCheckpoint() {
        AgentState state = AgentState.initial("t-1", "tenant", "user", "scene")
                .withVariable("query", "test");

        String token = checkpointStore.save(Checkpoint.pending(null, state));

        assertThat(token).isNotBlank();
        assertThat(checkpointStore.find(token))
                .isPresent()
                .hasValueSatisfying(c -> {
                    assertThat((String) c.getState().getVariable("query")).isEqualTo("test");
                    assertThat(c.getDecision()).isEqualTo("pending");
                });
    }

    @Test
    void shouldUpdateDecision() {
        String token = checkpointStore.save(Checkpoint.pending(null,
                AgentState.initial("t-1", "tenant", "user", "scene")));

        Checkpoint approved = checkpointStore.updateDecision(token, "approved", "looks good");

        assertThat(approved.getDecision()).isEqualTo("approved");
        assertThat(approved.getComment()).isEqualTo("looks good");
        CheckpointEntity entity = checkpointMapper.selectById(token);
        assertThat(entity.getDecision()).isEqualTo("approved");
    }
}
