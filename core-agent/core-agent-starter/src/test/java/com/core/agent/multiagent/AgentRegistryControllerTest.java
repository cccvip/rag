package com.core.agent.multiagent;

import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.AgentCard;
import com.core.agent.multiagent.infrastructure.InMemoryAgentRegistry;
import com.core.agent.multiagent.interfaces.AgentRegistryController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentRegistryController.class)
@ContextConfiguration(classes = {AgentRegistryController.class, AgentRegistryControllerTest.TestConfig.class})
class AgentRegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRegisterAndListAgents() throws Exception {
        AgentCard card = AgentCard.builder()
                .agentId("weather-agent")
                .name("Weather Agent")
                .capabilities(List.of("weather"))
                .endpoint("http://localhost:8081")
                .build();

        mockMvc.perform(post("/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(card)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentId").value("weather-agent"));

        mockMvc.perform(get("/agents").param("capability", "weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public AgentRegistry agentRegistry() {
            return new InMemoryAgentRegistry();
        }

        @org.springframework.context.annotation.Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
