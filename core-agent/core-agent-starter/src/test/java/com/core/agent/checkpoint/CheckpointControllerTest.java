package com.core.agent.checkpoint;

import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.application.CheckpointService;
import com.core.agent.checkpoint.infrastructure.InMemoryCheckpointStore;
import com.core.agent.checkpoint.interfaces.CheckpointController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CheckpointController.class)
@org.springframework.test.context.ContextConfiguration(classes = {CheckpointController.class, CheckpointControllerTest.TestConfig.class})
class CheckpointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CheckpointService checkpointService;

    @Test
    void shouldGetCheckpoint() throws Exception {
        String token = checkpointService.save(Checkpoint.pending(null,
                AgentState.initial("t-1", "tenant", "user", "scene")));

        mockMvc.perform(get("/checkpoints/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.decision").value("pending"));
    }

    @Test
    void shouldApproveCheckpoint() throws Exception {
        String token = checkpointService.save(Checkpoint.pending(null,
                AgentState.initial("t-1", "tenant", "user", "scene")));

        mockMvc.perform(post("/checkpoints/{token}/approve", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("approved"))
                .andExpect(jsonPath("$.comment").value("ok"));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public CheckpointService checkpointService() {
            return new CheckpointService(new InMemoryCheckpointStore());
        }
    }
}
