package com.core.agent.agent;

import com.core.agent.TestCoreAgentApp;
import com.core.agent.agent.domain.Agent;
import com.core.agent.agent.domain.AgentResult;
import com.core.agent.agent.interfaces.AgentExecutionController;
import com.core.agent.agent.interfaces.RunRequest;
import com.core.agent.checkpoint.application.CheckpointService;
import com.core.agent.context.domain.ContextStrategy;
import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.AgentCard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentExecutionController.class)
@ContextConfiguration(classes = {TestCoreAgentApp.class, AgentExecutionController.class})
class AgentExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentRegistry agentRegistry;

    @MockBean
    private Agent agent;

    @MockBean
    private CheckpointService checkpointService;

    @MockBean
    private ContextStrategy contextStrategy;

    @Test
    void shouldRunAgent() throws Exception {
        when(agentRegistry.find("agent-1")).thenReturn(Optional.of(AgentCard.builder()
                .agentId("agent-1")
                .name("Test Agent")
                .description("A test agent")
                .endpoint("http://localhost:8080")
                .build()));
        when(agent.getTenantId()).thenReturn("tenant-A");
        when(agent.getUserId()).thenReturn("user-001");
        when(agent.getScene()).thenReturn("rag");
        when(agent.run(any())).thenReturn(AgentResult.builder()
                .answer("final answer")
                .completed(true)
                .success(true)
                .confidence("HIGH")
                .build());

        RunRequest request = new RunRequest();
        request.setSessionId("session-1");
        request.setScene("rag");
        request.setQuery("hello");

        mockMvc.perform(post("/agents/agent-1/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.answer").value("final answer"));
    }

    @Test
    void shouldReturn404ForUnknownAgent() throws Exception {
        when(agentRegistry.find("unknown")).thenReturn(Optional.empty());

        RunRequest request = new RunRequest();
        request.setQuery("hello");

        mockMvc.perform(post("/agents/unknown/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldQueryTaskStatus() throws Exception {
        when(agentRegistry.find("agent-1")).thenReturn(Optional.of(AgentCard.builder()
                .agentId("agent-1")
                .name("Test Agent")
                .description("A test agent")
                .endpoint("http://localhost:8080")
                .build()));
        when(agent.getTenantId()).thenReturn("tenant-A");
        when(agent.getUserId()).thenReturn("user-001");
        when(agent.getScene()).thenReturn("rag");
        when(agent.run(any())).thenReturn(AgentResult.builder()
                .answer("final answer")
                .completed(true)
                .success(true)
                .build());

        RunRequest request = new RunRequest();
        request.setQuery("hello");

        String response = mockMvc.perform(post("/agents/agent-1/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String taskId = objectMapper.readTree(response).get("taskId").asText();

        mockMvc.perform(get("/agents/agent-1/tasks/" + taskId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
